package com.aditya.readaloud.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.aditya.readaloud.data.BookMeta
import com.aditya.readaloud.data.BookStore
import com.aditya.readaloud.util.TextUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TtsController {
    data class VoiceOption(val name: String, val localeTag: String, val offline: Boolean)

    data class PlaybackState(
        val bookId: String? = null,
        val page: Int = 0,
        val pageCount: Int = 0,
        val playing: Boolean = false,
        val paused: Boolean = false,
        val speed: Float = 1.0f,
        val languageMode: String = "auto",
        val voiceName: String? = null,
        val currentStart: Int = -1,
        val currentEnd: Int = -1,
        val currentTextLength: Int = 0,
        val message: String = "तैयार"
    )

    private lateinit var appContext: Context
    private lateinit var store: BookStore
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var ttsReady = false
    private var currentText = ""
    private var chunks: List<TextUtil.SpeechChunk> = emptyList()
    private var nextChunk = 0
    private var activeBook: BookMeta? = null
    private var selectedVoice: Voice? = null
    private var currentLanguage = Locale("en", "IN")
    private var pausedChar = 0
    private var cueQueuedForPage = false
    private var activeSpeed = 1.0f
    private val pending = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    var onStateChanged: (() -> Unit)? = null

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = BookStore(appContext)
        initialized = true
        tts = TextToSpeech(appContext) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(listener)
                chooseBestOfflineVoice(null)
                publish(_state.value.copy(message = "आवाज़ तैयार है"))
                val current = _state.value
                if (current.bookId != null && current.playing) {
                    activeBook?.let { book ->
                        scope.launch { preparePage(book, current.page, current.currentStart.coerceAtLeast(0), autoPlay = true) }
                    }
                }
            } else {
                publish(_state.value.copy(message = "TTS इंजन उपलब्ध नहीं है"))
            }
        }
    }

    fun voices(): List<VoiceOption> = tts?.voices
        ?.distinctBy { it.name }
        ?.sortedWith(compareByDescending<Voice> { !it.isNetworkConnectionRequired }.thenBy { it.locale.toLanguageTag() }.thenBy { it.name })
        ?.map { VoiceOption(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired) }
        ?: emptyList()

    fun useVoice(name: String?) {
        val match = tts?.voices?.firstOrNull { it.name == name }
        if (match != null) {
            selectedVoice = match
            tts?.voice = match
            publish(_state.value.copy(voiceName = match.name, message = "आवाज़: ${match.name}"))
        }
    }

    fun setSpeed(value: Float) {
        activeSpeed = value.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(activeSpeed)
        publish(_state.value.copy(speed = activeSpeed))
    }

    fun start(book: BookMeta, page: Int = book.lastPage) {
        if (!initialized) return
        tts?.stop()
        pending.clear()
        cueQueuedForPage = false
        activeBook = book
        selectedVoice = book.voiceName?.let { saved -> tts?.voices?.firstOrNull { it.name == saved } }
        selectedVoice?.let { tts?.voice = it }
        activeSpeed = book.lastSpeed.coerceIn(0.5f, 2.0f)
        pausedChar = 0
        publish(
            PlaybackState(
                bookId = book.id,
                page = page.coerceIn(0, book.pageCount - 1),
                pageCount = book.pageCount,
                speed = activeSpeed,
                languageMode = book.languageMode,
                voiceName = book.voiceName,
                message = "पन्ना लोड हो रहा है…"
            )
        )
        scope.launch {
            preparePage(book, _state.value.page, 0, autoPlay = true)
        }
    }

    fun pause() {
        if (!state.value.playing) return
        pausedChar = state.value.currentStart.coerceAtLeast(pausedChar).coerceAtLeast(0)
        tts?.stop()
        pending.clear()
        publish(_state.value.copy(playing = false, paused = true, message = "रुका हुआ"))
        persistProgress()
    }

    fun resume() {
        val book = activeBook ?: return
        if (!state.value.paused) {
            start(book, state.value.page)
            return
        }
        scope.launch {
            preparePage(book, state.value.page, pausedChar, autoPlay = true)
        }
    }

    fun stop() {
        tts?.stop()
        pending.clear()
        persistProgress()
        activeBook = null
        currentText = ""
        chunks = emptyList()
        nextChunk = 0
        publish(PlaybackState(message = "रुका हुआ"))
    }

    fun nextPage() {
        val book = activeBook ?: return
        val next = state.value.page + 1
        if (next >= book.pageCount) {
            stop()
            publish(PlaybackState(message = "किताब समाप्त हो गई"))
            return
        }
        tts?.stop()
        pending.clear()
        cueQueuedForPage = false
        pausedChar = 0
        scope.launch { preparePage(book, next, 0, autoPlay = state.value.playing) }
    }

    fun previousPage() {
        val book = activeBook ?: return
        val prev = (state.value.page - 1).coerceAtLeast(0)
        tts?.stop()
        pending.clear()
        cueQueuedForPage = false
        pausedChar = 0
        scope.launch { preparePage(book, prev, 0, autoPlay = state.value.playing) }
    }

    fun jumpTo(page: Int) {
        val book = activeBook ?: return
        val target = page.coerceIn(0, book.pageCount - 1)
        tts?.stop()
        pending.clear()
        cueQueuedForPage = false
        pausedChar = 0
        scope.launch { preparePage(book, target, 0, autoPlay = state.value.playing) }
    }

    fun setLanguageMode(mode: String) {
        val book = activeBook ?: return
        activeBook = book.copy(languageMode = mode)
        publish(_state.value.copy(languageMode = mode))
        if (state.value.playing || state.value.paused) {
            tts?.stop()
            pending.clear()
            cueQueuedForPage = false
            val updatedBook = activeBook ?: return
            scope.launch { preparePage(updatedBook, state.value.page, state.value.currentStart.coerceAtLeast(0), autoPlay = state.value.playing) }
        }
    }

    fun pageText(): String = currentText

    private suspend fun preparePage(book: BookMeta, page: Int, startChar: Int, autoPlay: Boolean) {
        val raw = store.readPageText(book.id, page)
        currentText = TextUtil.speechText(raw)
        cueQueuedForPage = false
        chunks = TextUtil.makeChunks(currentText, maxOf(800, (TextToSpeech.getMaxSpeechInputLength() - 300)))
        nextChunk = chunks.indexOfFirst { it.end > startChar }.takeIf { it >= 0 } ?: chunks.size
        val firstChar = if (chunks.isNotEmpty() && nextChunk < chunks.size) maxOf(startChar, chunks[nextChunk].start) else 0
        setLanguageForPage(book, currentText)
        setSpeed(book.lastSpeed.coerceIn(0.5f, 2.0f))
        publish(
            _state.value.copy(
                bookId = book.id,
                page = page,
                pageCount = book.pageCount,
                paused = !autoPlay,
                playing = autoPlay,
                currentStart = firstChar.coerceIn(0, currentText.length),
                currentEnd = firstChar.coerceIn(0, currentText.length),
                currentTextLength = currentText.length,
                message = if (currentText.isBlank()) "इस पन्ने पर पढ़ने योग्य टेक्स्ट नहीं मिला" else if (autoPlay) "पढ़ रहा है…" else "रुका हुआ"
            )
        )
        if (autoPlay && currentText.isNotBlank() && ttsReady) {
            queueMore(2)
        } else if (autoPlay && currentText.isBlank()) {
            val nextPage = page + 1
            if (nextPage < book.pageCount) {
                scope.launch { delay(180); preparePage(book, nextPage, 0, true) }
            }
        }
    }

    private fun queueMore(target: Int) {
        if (!ttsReady || currentText.isBlank()) return
        var count = pending.size
        while (count < target && nextChunk < chunks.size) {
            val chunk = chunks[nextChunk]
            val id = "${activeBook?.id}_${state.value.page}_${nextChunk}_${UUID.randomUUID()}"
            pending[id] = chunk.start to chunk.end
            val queueMode = if (count == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
            val result = tts?.speak(chunk.text, queueMode, params, id) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) {
                pending.remove(id)
                publish(_state.value.copy(message = "TTS ने इस पन्ने को पढ़ने से मना किया"))
                break
            }
            nextChunk++
            count++
        }
        if (nextChunk >= chunks.size && pending.isNotEmpty() && !cueQueuedForPage) {
            val nextPage = state.value.page + 1
            val book = activeBook
            if (book != null && nextPage < book.pageCount) {
                val cueId = "${book.id}_${state.value.page}_cue_${UUID.randomUUID()}"
                pending[cueId] = currentText.length to currentText.length
                cueQueuedForPage = true
                val params = Bundle()
                tts?.speak(pageCue(nextPage, currentText), TextToSpeech.QUEUE_ADD, params, cueId)
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val offsets = pending[utteranceId ?: return] ?: return
            publish(_state.value.copy(currentStart = offsets.first, currentEnd = offsets.second, message = "पढ़ रहा है…"))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val id = utteranceId ?: return
            val offsets = pending[id] ?: return
            val globalStart = (offsets.first + start).coerceIn(0, currentText.length)
            val globalEnd = (offsets.first + end).coerceIn(globalStart, currentText.length)
            publish(_state.value.copy(currentStart = globalStart, currentEnd = globalEnd, message = "पढ़ रहा है…"))
        }

        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            val isCue = id.contains("_cue_")
            pending.remove(id)
            if (isCue) {
                cueQueuedForPage = false
                val book = activeBook ?: return
                val nextPage = state.value.page + 1
                if (nextPage < book.pageCount && state.value.playing) {
                    scope.launch { preparePage(book, nextPage, 0, autoPlay = true) }
                }
                return
            }
            if (state.value.playing) {
                if (pending.isEmpty() && nextChunk >= chunks.size) {
                    val book = activeBook
                    val nextPage = state.value.page + 1
                    if (book == null || nextPage >= book.pageCount) {
                        publish(_state.value.copy(playing = false, paused = false, message = "किताब समाप्त हो गई"))
                        persistProgress()
                    } else {
                        scope.launch { preparePage(book, nextPage, 0, autoPlay = true) }
                    }
                } else {
                    queueMore(2)
                }
            }
        }

        override fun onError(utteranceId: String?) {
            publish(_state.value.copy(playing = false, paused = true, message = "TTS में त्रुटि आई"))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onError(utteranceId)
        }
    }

    private fun setLanguageForPage(book: BookMeta, text: String) {
        val locale = when (book.languageMode) {
            "hi" -> Locale("hi", "IN")
            "en" -> Locale("en", "IN")
            else -> TextUtil.autoLocale(text)
        }
        currentLanguage = locale
        val localVoice = selectedVoice?.takeIf { it.locale.language == locale.language }
        if (localVoice != null) {
            tts?.voice = localVoice
        } else {
            val best = tts?.voices
                ?.filter { it.locale.language == locale.language }
                ?.sortedWith(compareByDescending<Voice> { !it.isNetworkConnectionRequired }.thenBy { it.name })
                ?.firstOrNull()
            if (best != null) {
                selectedVoice = best
                tts?.voice = best
            } else {
                tts?.language = locale
            }
        }
    }

    private fun chooseBestOfflineVoice(language: String?) {
        val target = language ?: currentLanguage.language
        val best = tts?.voices
            ?.filter { it.locale.language == target }
            ?.sortedWith(compareByDescending<Voice> { !it.isNetworkConnectionRequired }.thenBy { it.name })
            ?.firstOrNull()
        if (best != null) {
            selectedVoice = best
            tts?.voice = best
        }
    }

    private fun pageCue(nextPage: Int, pageText: String): String =
        if (TextUtil.containsDevanagari(pageText)) "अगला पृष्ठ $nextPage" else "Next page $nextPage"

    private fun persistProgress() {
        val book = activeBook ?: return
        runCatching {
            store.updateProgress(book, state.value.page, activeSpeed, state.value.languageMode, selectedVoice?.name)
        }
    }

    private fun publish(newState: PlaybackState) {
        _state.value = newState
        onStateChanged?.invoke()
    }
}
