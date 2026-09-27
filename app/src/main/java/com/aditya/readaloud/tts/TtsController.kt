package com.aditya.readaloud.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.edit
import com.aditya.readaloud.data.BookMeta
import com.aditya.readaloud.data.BookStore
import com.aditya.readaloud.util.TextUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TtsController {
    data class VoiceOption(val name: String, val localeTag: String, val offline: Boolean)

    data class PlaybackState(
        val bookId: String? = null,
        val page: Int = 0,
        val pageCount: Int = 0,
        val playing: Boolean = false,
        val paused: Boolean = false,
        val speed: Float = 0.95f,
        val languageMode: String = "auto",
        val voiceName: String? = null,
        val currentStart: Int = -1,
        val currentEnd: Int = -1,
        val currentTextLength: Int = 0,
        val ttsMode: String = "local",
        val message: String = "तैयार"
    )

    private const val PREFS = "read_aloud_tts"
    private const val KEY_MODE = "mode"
    private const val KEY_GEMINI_KEY = "gemini_key"
    private const val KEY_GEMINI_VOICE = "gemini_voice"

    private lateinit var appContext: Context
    private lateinit var store: BookStore
    private var initialized = false
    private var ttsReady = false
    private var tts: TextToSpeech? = null

    private var activeBook: BookMeta? = null
    private var runtimeMode = "local"
    private var currentText = ""
    private var chunks: List<TextUtil.SpeechChunk> = emptyList()
    private var nextChunk = 0
    private var selectedVoice: Voice? = null
    private var currentLanguage = Locale("hi", "IN")
    private var activeSpeed = 0.95f
    private var pausedChar = 0

    private var player: MediaPlayer? = null
    private var currentCloudChunk: TextUtil.SpeechChunk? = null
    private var pausedCloudPosition = 0
    private var cloudHighlightJob: Job? = null
    private var cloudGenerationJob: Job? = null
    private var cloudPrefetchJob: Job? = null
    private val pendingLocal = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    var onStateChanged: (() -> Unit)? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = BookStore(appContext)
        initialized = true
        val googleEngine = runCatching {
            appContext.packageManager.getApplicationInfo("com.google.android.tts", 0)
            "com.google.android.tts"
        }.getOrNull()
        tts = if (googleEngine != null) TextToSpeech(appContext, ttsInitListener, googleEngine)
        else TextToSpeech(appContext, ttsInitListener)
    }

    private val ttsInitListener = TextToSpeech.OnInitListener { result ->
        ttsReady = result == TextToSpeech.SUCCESS
        if (!ttsReady) {
            publish(_state.value.copy(message = "डिवाइस TTS उपलब्ध नहीं है"))
            return@OnInitListener
        }
        tts?.setOnUtteranceProgressListener(localListener)
        chooseBestLocalVoice()
        tts?.setSpeechRate(activeSpeed)
        tts?.setPitch(0.98f)
        publish(_state.value.copy(message = "आवाज़ तैयार है"))
    }

    fun ttsMode(): String = if (::appContext.isInitialized) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "local") ?: "local"
    } else "local"

    fun setTtsMode(mode: String) {
        val safe = if (mode == "gemini") "gemini" else "local"
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_MODE, safe) }
        if (activeBook != null) {
            stopPlaybackOnly()
            runtimeMode = if (safe == "gemini" && geminiApiKey().isNotBlank()) "gemini" else "local"
            publish(_state.value.copy(ttsMode = runtimeMode, playing = false, paused = true, message = if (runtimeMode == "gemini") "Gemini ऑनलाइन आवाज़ चुनी गई" else "डिवाइस आवाज़ चुनी गई"))
        } else {
            publish(_state.value.copy(ttsMode = safe, message = if (safe == "gemini") "Gemini ऑनलाइन आवाज़ चुनी गई" else "डिवाइस आवाज़ चुनी गई"))
        }
    }

    fun geminiApiKey(): String = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GEMINI_KEY, "").orEmpty()
    fun setGeminiApiKey(value: String) { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_GEMINI_KEY, value.trim()) } }
    fun geminiVoiceName(): String = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GEMINI_VOICE, "Orus") ?: "Orus"
    fun setGeminiVoiceName(value: String) { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_GEMINI_VOICE, value) } }

    fun voices(): List<VoiceOption> = tts?.voices
        ?.distinctBy { it.name }
        ?.sortedWith(compareByDescending<Voice> { !it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.locale.toLanguageTag() })
        ?.map { VoiceOption(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired) }
        ?: emptyList()

    fun useVoice(name: String?) {
        val match = tts?.voices?.firstOrNull { it.name == name } ?: return
        selectedVoice = match
        tts?.voice = match
        publish(_state.value.copy(voiceName = match.name, message = "डिवाइस आवाज़: ${match.name}"))
    }

    fun setSpeed(value: Float) {
        activeSpeed = value.coerceIn(0.5f, 2f)
        tts?.setSpeechRate(activeSpeed)
        runCatching { player?.playbackParams = player?.playbackParams?.setSpeed(activeSpeed) ?: return@runCatching }
        publish(_state.value.copy(speed = activeSpeed))
    }

    fun start(book: BookMeta, page: Int = book.lastPage) {
        stopInternal(clearBook = true)
        activeBook = book
        activeSpeed = book.lastSpeed.coerceIn(0.5f, 2f)
        pausedChar = 0
        runtimeMode = if (ttsMode() == "gemini" && geminiApiKey().isNotBlank()) "gemini" else "local"
        val safePage = page.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
        prioritize(book, safePage)
        publish(PlaybackState(
            bookId = book.id,
            page = safePage,
            pageCount = book.pageCount,
            speed = activeSpeed,
            languageMode = book.languageMode,
            voiceName = book.voiceName,
            ttsMode = runtimeMode,
            message = "पृष्ठ तैयार हो रहा है…"
        ))
        scope.launch {
            if (runtimeMode == "gemini") prepareGeminiPage(book, safePage, 0, true)
            else prepareLocalPage(book, safePage, 0, true)
        }
    }

    fun pause() {
        if (!_state.value.playing) return
        if (runtimeMode == "gemini" && player != null) {
            pausedCloudPosition = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            cloudHighlightJob?.cancel()
            runCatching { player?.pause() }
        } else {
            pausedChar = _state.value.currentStart.coerceAtLeast(0)
            tts?.stop()
            pendingLocal.clear()
        }
        publish(_state.value.copy(playing = false, paused = true, message = "रुका हुआ"))
        persistProgress()
    }

    fun resume() {
        val book = activeBook ?: return
        if (!_state.value.paused) { start(book, _state.value.page); return }
        if (runtimeMode == "gemini" && player != null) {
            runCatching {
                player?.seekTo(pausedCloudPosition)
                player?.start()
                startCloudHighlightLoop(currentCloudChunk?.text.orEmpty(), currentCloudChunk?.start ?: _state.value.currentStart)
            }.onSuccess {
                publish(_state.value.copy(playing = true, paused = false, message = "पढ़ रहा है…"))
            }.onFailure {
                scope.launch { prepareGeminiPage(book, _state.value.page, pausedChar, true) }
            }
        } else {
            scope.launch {
                if (runtimeMode == "gemini") prepareGeminiPage(book, _state.value.page, pausedChar, true)
                else prepareLocalPage(book, _state.value.page, pausedChar, true)
            }
        }
    }

    fun stop() {
        stopInternal(clearBook = true)
        publish(PlaybackState(message = "रुका हुआ"))
    }

    fun nextPage() {
        val book = activeBook ?: return
        val next = _state.value.page + 1
        if (next >= book.pageCount) { stop(); publish(PlaybackState(message = "किताब समाप्त हो गई")); return }
        val wasPlaying = _state.value.playing
        stopPlaybackOnly()
        prioritize(book, next)
        scope.launch {
            if (runtimeMode == "gemini") prepareGeminiPage(book, next, 0, wasPlaying)
            else prepareLocalPage(book, next, 0, wasPlaying)
        }
    }

    fun previousPage() {
        val book = activeBook ?: return
        val prev = (_state.value.page - 1).coerceAtLeast(0)
        val wasPlaying = _state.value.playing
        stopPlaybackOnly()
        prioritize(book, prev)
        scope.launch {
            if (runtimeMode == "gemini") prepareGeminiPage(book, prev, 0, wasPlaying)
            else prepareLocalPage(book, prev, 0, wasPlaying)
        }
    }

    fun jumpTo(page: Int) {
        val book = activeBook ?: return
        val target = page.coerceIn(0, book.pageCount - 1)
        val wasPlaying = _state.value.playing
        stopPlaybackOnly()
        prioritize(book, target)
        scope.launch {
            if (runtimeMode == "gemini") prepareGeminiPage(book, target, 0, wasPlaying)
            else prepareLocalPage(book, target, 0, wasPlaying)
        }
    }

    fun setLanguageMode(mode: String) {
        val book = activeBook ?: return
        activeBook = book.copy(languageMode = mode)
        publish(_state.value.copy(languageMode = mode))
        if (_state.value.playing || _state.value.paused) {
            jumpTo(_state.value.page)
        }
    }

    private suspend fun prepareLocalPage(book: BookMeta, page: Int, startChar: Int, autoPlay: Boolean) {
        val raw = store.ensurePageText(book.id, page)
        currentText = TextUtil.speechText(raw)
        chunks = TextUtil.makeChunks(currentText, TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1400) - 100)
        nextChunk = chunks.indexOfFirst { it.end > startChar }.takeIf { it >= 0 } ?: chunks.size
        val firstChar = chunks.getOrNull(nextChunk)?.start ?: 0
        setLanguageForPage(book, currentText)
        tts?.setSpeechRate(activeSpeed)
        tts?.setPitch(0.98f)
        publish(_state.value.copy(bookId = book.id, page = page, pageCount = book.pageCount, playing = autoPlay, paused = !autoPlay, speed = activeSpeed, currentStart = firstChar, currentEnd = firstChar, currentTextLength = currentText.length, ttsMode = "local", message = when { currentText.isBlank() -> "इस पृष्ठ पर पढ़ने योग्य पाठ नहीं मिला"; autoPlay -> "पढ़ रहा है…"; else -> "रुका हुआ" }))
        prioritize(book, page)
        if (autoPlay && currentText.isNotBlank() && ttsReady) queueLocalMore(2)
        else if (autoPlay && currentText.isBlank()) advanceToNextPage(book, page)
    }

    private suspend fun prepareGeminiPage(book: BookMeta, page: Int, startChar: Int, autoPlay: Boolean) {
        val raw = store.ensurePageText(book.id, page)
        currentText = TextUtil.speechText(raw)
        chunks = TextUtil.makeChunks(currentText, 1800)
        nextChunk = chunks.indexOfFirst { it.end > startChar }.takeIf { it >= 0 } ?: chunks.size
        val firstChar = chunks.getOrNull(nextChunk)?.start ?: 0
        publish(_state.value.copy(bookId = book.id, page = page, pageCount = book.pageCount, playing = autoPlay, paused = !autoPlay, speed = activeSpeed, currentStart = firstChar, currentEnd = firstChar, currentTextLength = currentText.length, ttsMode = "gemini", message = when { currentText.isBlank() -> "इस पृष्ठ पर पढ़ने योग्य पाठ नहीं मिला"; autoPlay -> "प्राकृतिक आवाज़ तैयार हो रही है…"; else -> "रुका हुआ" }))
        prioritize(book, page)
        if (autoPlay && currentText.isNotBlank() && geminiApiKey().isNotBlank()) playNextGeminiChunk()
        else if (autoPlay && currentText.isBlank()) advanceToNextPage(book, page)
    }

    private fun queueLocalMore(target: Int) {
        if (!ttsReady || currentText.isBlank()) return
        var count = pendingLocal.size
        while (count < target && nextChunk < chunks.size) {
            val chunk = chunks[nextChunk]
            val id = "${activeBook?.id}_${_state.value.page}_${nextChunk}_${UUID.randomUUID()}"
            pendingLocal[id] = chunk.start to chunk.end
            val mode = if (count == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts?.speak(chunk.text, mode, Bundle(), id) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) { pendingLocal.remove(id); publish(_state.value.copy(message = "डिवाइस TTS इस पृष्ठ को नहीं पढ़ सका")); break }
            nextChunk++; count++
        }
    }

    private fun playNextGeminiChunk() {
        val book = activeBook ?: return
        if (!_state.value.playing) return
        if (nextChunk >= chunks.size) { playGeminiCueThenAdvance(book); return }
        val chunk = chunks[nextChunk++]
        currentCloudChunk = chunk
        cloudGenerationJob?.cancel()
        cloudGenerationJob = scope.launch {
            try {
                val file = getOrCreateCloudAudio(book, _state.value.page, chunk)
                if (!_state.value.playing) return@launch
                startCloudPlayer(file, chunk)
                val future = chunks.getOrNull(nextChunk)
                if (future != null) {
                    cloudPrefetchJob?.cancel()
                    cloudPrefetchJob = scope.launch(Dispatchers.IO) { runCatching { getOrCreateCloudAudio(book, _state.value.page, future) } }
                }
            } catch (t: Throwable) {
                runtimeMode = "local"
                publish(_state.value.copy(ttsMode = "local", message = "Gemini उपलब्ध नहीं; डिवाइस आवाज़ पर जारी…"))
                prepareLocalPage(book, _state.value.page, chunk.start, true)
            }
        }
    }

    private suspend fun getOrCreateCloudAudio(book: BookMeta, page: Int, chunk: TextUtil.SpeechChunk): File {
        val voice = geminiVoiceName()
        val cache = store.audioCacheFile(book.id, page, chunk.start, voice, chunk.text)
        if (cache.exists() && cache.length() > 1000L) return cache
        val bytes = GeminiTtsClient.synthesize(geminiApiKey(), chunk.text, voice)
        withContext(Dispatchers.IO) { cache.outputStream().use { it.write(bytes) } }
        return cache
    }

    private fun startCloudPlayer(file: File, chunk: TextUtil.SpeechChunk) {
        releasePlayer()
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
        media.setDataSource(file.absolutePath)
        media.setOnPreparedListener {
            runCatching { it.playbackParams = it.playbackParams.setSpeed(activeSpeed) }
            if (_state.value.playing) {
                it.start()
                publish(_state.value.copy(currentStart = chunk.start, currentEnd = chunk.end, message = "पढ़ रहा है…"))
                startCloudHighlightLoop(chunk.text, chunk.start)
            }
        }
        media.setOnCompletionListener {
            cloudHighlightJob?.cancel()
            releasePlayer()
            if (!_state.value.playing) return@setOnCompletionListener
            if (nextChunk < chunks.size) playNextGeminiChunk()
            else activeBook?.let { playGeminiCueThenAdvance(it) }
        }
        media.setOnErrorListener { _, _, _ ->
            releasePlayer()
            runtimeMode = "local"
            publish(_state.value.copy(ttsMode = "local", playing = false, paused = true, message = "ऑनलाइन ऑडियो में त्रुटि"))
            true
        }
        media.prepareAsync()
    }

    private fun startCloudHighlightLoop(chunkText: String, globalStart: Int) {
        cloudHighlightJob?.cancel()
        cloudHighlightJob = scope.launch {
            while (_state.value.playing && player != null) {
                val media = player ?: break
                val duration = media.duration.coerceAtLeast(1)
                val fraction = media.currentPosition.coerceAtLeast(0).toFloat() / duration.toFloat()
                TextUtil.rangeAtProgress(chunkText, fraction)?.let { range ->
                    publish(_state.value.copy(currentStart = globalStart + range.first, currentEnd = globalStart + range.last + 1))
                }
                delay(80)
            }
        }
    }

    private fun playGeminiCueThenAdvance(book: BookMeta) {
        val nextPage = _state.value.page + 1
        if (nextPage >= book.pageCount) {
            publish(_state.value.copy(playing = false, paused = false, message = "किताब समाप्त हो गई"))
            persistProgress()
            return
        }
        scope.launch {
            runCatching {
                val cueText = if (TextUtil.containsDevanagari(currentText)) "अगला पृष्ठ ${nextPage + 1}" else "Next page ${nextPage + 1}"
                val cue = TextUtil.SpeechChunk(0, cueText.length, cueText)
                val file = getOrCreateCloudAudio(book, nextPage, cue)
                val cuePlayer = MediaPlayer()
                player = cuePlayer
                cuePlayer.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                cuePlayer.setDataSource(file.absolutePath)
                cuePlayer.setOnPreparedListener { it.start() }
                cuePlayer.setOnCompletionListener {
                    releasePlayer()
                    if (_state.value.playing) scope.launch { prioritize(book, nextPage); prepareGeminiPage(book, nextPage, 0, true) }
                }
                cuePlayer.prepareAsync()
            }.onFailure {
                scope.launch { prioritize(book, nextPage); prepareGeminiPage(book, nextPage, 0, true) }
            }
        }
    }

    private fun advanceToNextPage(book: BookMeta, page: Int) {
        val next = page + 1
        if (next >= book.pageCount) {
            publish(_state.value.copy(playing = false, paused = false, message = "किताब समाप्त हो गई"))
            persistProgress(); return
        }
        prioritize(book, next)
        scope.launch {
            delay(80)
            if (_state.value.playing) {
                if (runtimeMode == "gemini") prepareGeminiPage(book, next, 0, true)
                else prepareLocalPage(book, next, 0, true)
            }
        }
    }

    private fun prioritize(book: BookMeta, page: Int) {
        store.requestPagePriority(book.id, page, 5)
    }

    private fun setLanguageForPage(book: BookMeta, text: String) {
        val locale = when (book.languageMode) { "hi" -> Locale("hi", "IN"); "en" -> Locale("en", "IN"); else -> TextUtil.autoLocale(text) }
        currentLanguage = locale
        val preferred = selectedVoice?.takeIf { it.locale.language == locale.language }
        if (preferred != null) { tts?.voice = preferred; return }
        val best = tts?.voices
            ?.filter { it.locale.language == locale.language }
            ?.sortedWith(compareByDescending<Voice> { it.name.contains("male", true) }.thenByDescending { !it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
            ?.firstOrNull()
        if (best != null) { selectedVoice = best; tts?.voice = best } else tts?.language = locale
    }

    private fun chooseBestLocalVoice() { setLanguageForPage(activeBook ?: return, currentText) }

    private fun persistProgress() {
        val book = activeBook ?: return
        runCatching { store.updateProgress(book, _state.value.page, activeSpeed, _state.value.languageMode, selectedVoice?.name) }
    }

    private fun stopPlaybackOnly() {
        tts?.stop()
        pendingLocal.clear()
        cloudGenerationJob?.cancel()
        cloudPrefetchJob?.cancel()
        cloudHighlightJob?.cancel()
        releasePlayer()
    }

    private fun stopInternal(clearBook: Boolean) {
        if (activeBook != null) persistProgress()
        stopPlaybackOnly()
        currentCloudChunk = null
        pausedCloudPosition = 0
        if (clearBook) {
            activeBook = null
            currentText = ""
            chunks = emptyList()
            nextChunk = 0
            pausedChar = 0
        }
    }

    private fun releasePlayer() {
        cloudHighlightJob?.cancel()
        runCatching {
            player?.setOnCompletionListener(null)
            player?.setOnPreparedListener(null)
            player?.setOnErrorListener(null)
            player?.release()
        }
        player = null
    }

    private val localListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            pendingLocal[utteranceId ?: return]?.let { publish(_state.value.copy(currentStart = it.first, currentEnd = it.second, message = "पढ़ रहा है…")) }
        }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val range = pendingLocal[utteranceId ?: return] ?: return
            publish(_state.value.copy(currentStart = (range.first + start).coerceIn(0, currentText.length), currentEnd = (range.first + end).coerceIn(0, currentText.length), message = "पढ़ रहा है…"))
        }
        override fun onDone(utteranceId: String?) {
            pendingLocal.remove(utteranceId ?: return)
            if (!_state.value.playing) return
            if (pendingLocal.isEmpty() && nextChunk >= chunks.size) activeBook?.let { advanceToNextPage(it, _state.value.page) }
            else queueLocalMore(2)
        }
        override fun onError(utteranceId: String?) { publish(_state.value.copy(playing = false, paused = true, message = "डिवाइस TTS में त्रुटि")) }
        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
    }

    private fun publish(newState: PlaybackState) {
        _state.value = newState
        onStateChanged?.invoke()
    }
}
