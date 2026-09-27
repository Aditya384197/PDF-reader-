package com.aditya.readaloud.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aditya.readaloud.data.BookMeta
import com.aditya.readaloud.data.BookStore
import com.aditya.readaloud.tts.TtsController
import com.aditya.readaloud.util.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@Composable
fun ReaderApp(onStartPlaybackService: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BookStore(context) }
    var books by remember { mutableStateOf(store.listBooks()) }
    var selected by remember { mutableStateOf<BookMeta?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val playback by TtsController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importError = null
            runCatching {
                val name = queryDisplayName(context, uri) ?: "पुस्तक.pdf"
                store.importFromUri(uri, name)
            }.onFailure {
                importError = it.message ?: "PDF जोड़ना असफल रहा"
            }.onSuccess {
                books = store.listBooks()
            }
            importing = false
        }
    }

    LaunchedEffect(selected) {
        while (selected == null) {
            books = store.listBooks()
            kotlinx.coroutines.delay(1200)
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (selected == null) {
                LibraryScreen(
                    books = books,
                    playback = playback,
                    onOpen = { selected = it },
                    onDelete = {
                        if (playback.bookId == it.id) TtsController.stop()
                        store.delete(it.id)
                        books = store.listBooks()
                    },
                    onImport = { launcher.launch(arrayOf("application/pdf")) },
                    onOpenPlaying = {
                        books.firstOrNull { it.id == playback.bookId }?.let { selected = it }
                    }
                )
            } else {
                ReaderScreen(
                    book = selected!!,
                    onBack = { selected = null; books = store.listBooks() },
                    onStartService = onStartPlaybackService
                )
            }
        }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("PDF सेव हो रही है") },
            text = { Text("फ़ाइल को पहले तेज़ी से डिवाइस में सेव किया जा रहा है। उसके बाद पाठ/OCR पृष्ठभूमि में तैयार होगा; आपको 200 पृष्ठ की PDF के लिए पूरा OCR खत्म होने का इंतज़ार नहीं करना पड़ेगा।") }
        )
    }

    importError?.let {
        AlertDialog(
            onDismissRequest = { importError = null },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("ठीक है") } },
            title = { Text("PDF नहीं जुड़ी") },
            text = { Text(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    books: List<BookMeta>,
    playback: TtsController.PlaybackState,
    onOpen: (BookMeta) -> Unit,
    onDelete: (BookMeta) -> Unit,
    onImport: () -> Unit,
    onOpenPlaying: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<BookMeta?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    val filtered = books.filter { it.title.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("पुस्तकालय", fontWeight = FontWeight.Bold)
                        Text("PDF पढ़ें और सुनें", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                actions = { IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, null) } }
            )
        },
        floatingActionButton = {
            SmallFloatingActionButton(onClick = onImport) { Icon(Icons.Default.Add, "PDF जोड़ें") }
        },
        bottomBar = {
            if (playback.bookId != null) MiniPlayer(playback, onOpenPlaying)
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("अपनी किताब खोजें…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                )
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Book, null, Modifier.size(52.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (books.isEmpty()) "अभी कोई PDF नहीं है" else "कोई किताब नहीं मिली")
                        Spacer(Modifier.height(6.dp))
                        Text("＋ दबाकर अपनी PDF किताब जोड़ें।")
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookCard(book, { onOpen(book) }, { deleteTarget = book })
                    }
                }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("ठीक है") } },
            title = { Text("Aditya Read Aloud") },
            text = {
                Text(
                    "तेज़ PDF आयात + पृष्ठभूमि OCR + लगातार वाचन। " +
                    "Gemini ऑनलाइन आवाज़ वैकल्पिक है; उसकी audio फ़ाइलें स्थानीय रूप से cache होने के बाद वही पृष्ठ बिना इंटरनेट भी चल सकते हैं।"
                )
            }
        )
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("किताब हटाएँ?") },
            text = { Text("“${book.title}” और उसका पढ़ने का डेटा हट जाएगा।") },
            confirmButton = {
                TextButton(onClick = { onDelete(book); deleteTarget = null }) { Text("हटाएँ") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("रद्द करें") } }
        )
    }
}

@Composable
private fun BookCard(book: BookMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    val store = remember { BookStore(LocalContext.current) }
    val bitmap = remember(book.id, book.lastOpenedAt) {
        BitmapFactory.decodeFile(store.thumbnailFile(book.id).absolutePath)
    }
    val progress = if (book.pageCount == 0) 0f else book.indexedPages.toFloat() / book.pageCount

    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(74.dp).clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.Book, null, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("${book.pageCount} पृष्ठ", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                if (book.indexing) {
                    Text("पाठ/OCR तैयार हो रहा है: ${book.indexedPages}/${book.pageCount}", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                } else {
                    val pct = if (book.pageCount == 0) 0 else ((book.lastPage + 1) * 100 / book.pageCount)
                    Text("पढ़ने की स्थिति: $pct%", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "हटाएँ") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    book: BookMeta,
    onBack: () -> Unit,
    onStartService: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { BookStore(context) }
    val playback by TtsController.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(book.id) { mutableIntStateOf(book.lastPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))) }
    var showText by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showGemini by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }

    LaunchedEffect(playback.page, playback.bookId) {
        if (playback.bookId == book.id) page = playback.page
    }

    LaunchedEffect(page, book.id) {
        store.requestPagePriority(book.id, page, 5)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("पृष्ठ ${page + 1} / ${book.pageCount}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "वापस") } },
                actions = {
                    IconButton(onClick = { showText = !showText }) { Icon(Icons.Default.TextFields, "पाठ") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.MoreVert, "अधिक") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PdfPageView(book, page, Modifier.fillMaxWidth().weight(if (showText) .60f else 1f))
            if (showText) LiveTextPanel(book, page, playback, Modifier.fillMaxWidth().weight(.40f))
            ReaderControls(
                playback = playback,
                page = page,
                pageCount = book.pageCount,
                onPrevious = { TtsController.previousPage(); page = max(0, page - 1) },
                onNext = { TtsController.nextPage(); page = min(book.pageCount - 1, page + 1) },
                onPlayPause = {
                    onStartService()
                    when {
                        playback.bookId == book.id && playback.playing -> TtsController.pause()
                        playback.bookId == book.id && playback.paused -> TtsController.resume()
                        else -> TtsController.start(book, page)
                    }
                },
                onSpeed = { showSpeed = true },
                onJump = { showJump = true },
                onPageSlide = { page = it },
                onPageSlideFinished = { TtsController.jumpTo(page) }
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("वाचन सेटिंग", style = MaterialTheme.typography.headlineSmall)

                Text("आवाज़ प्रणाली", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = TtsController.ttsMode() == "local",
                        onClick = { TtsController.setTtsMode("local") },
                        label = { Text("डिवाइस") }
                    )
                    FilterChip(
                        selected = TtsController.ttsMode() == "gemini",
                        onClick = { TtsController.setTtsMode("gemini") },
                        label = { Text("Gemini ऑनलाइन") }
                    )
                }

                if (TtsController.ttsMode() == "gemini") {
                    Text("Gemini आवाज़ ${TtsController.geminiVoiceName()} • पहली बार इंटरनेट आवश्यक", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { showGemini = true }) { Text("Gemini आवाज़ सेट करें") }
                }

                Text("भाषा", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { TtsController.setLanguageMode("auto") }, label = { Text("स्वतः") })
                    AssistChip(onClick = { TtsController.setLanguageMode("hi") }, label = { Text("हिंदी") })
                    AssistChip(onClick = { TtsController.setLanguageMode("en") }, label = { Text("English") })
                }

                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("गति ${"%.2f".format(playback.speed)}x") },
                    leadingIcon = { Icon(Icons.Default.Speed, null) },
                    onClick = { showSettings = false; showSpeed = true }
                )
                DropdownMenuItem(
                    text = { Text("डिवाइस आवाज़ चुनें") },
                    leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null) },
                    onClick = { showSettings = false; showVoice = true }
                )
                DropdownMenuItem(
                    text = { Text(if (showText) "लाइव पाठ छिपाएँ" else "लाइव पाठ दिखाएँ") },
                    leadingIcon = { Icon(Icons.Default.TextFields, null) },
                    onClick = { showText = !showText; showSettings = false }
                )
            }
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("वाचन गति") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${"%.2f".format(playback.speed)}x")
                    Slider(value = playback.speed, onValueChange = TtsController::setSpeed, valueRange = .5f..2f, steps = 5)
                }
            },
            confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("हो गया") } }
        )
    }

    if (showVoice) VoiceDialog { showVoice = false }
    if (showGemini) GeminiDialog { showGemini = false }

    if (showJump) {
        var value by rememberSaveable { mutableStateOf((page + 1).toString()) }
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("पृष्ठ पर जाएँ") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    label = { Text("पृष्ठ संख्या") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    value.toIntOrNull()?.minus(1)?.let {
                        page = it.coerceIn(0, book.pageCount - 1)
                        TtsController.jumpTo(page)
                    }
                    showJump = false
                }) { Text("जाएँ") }
            },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("रद्द") } }
        )
    }
}

@Composable
private fun PdfPageView(book: BookMeta, page: Int, modifier: Modifier) {
    val context = LocalContext.current
    var scale by remember(book.id, page) { mutableFloatStateOf(1f) }
    var tx by remember(book.id, page) { mutableFloatStateOf(0f) }
    var ty by remember(book.id, page) { mutableFloatStateOf(0f) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.id, page) {
        value = withContext(Dispatchers.IO) { renderPage(context, book, page) }
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(book.id, page) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 3f)
                    tx += pan.x
                    ty += pan.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
        else Image(
            bitmap!!.asImageBitmap(),
            "PDF पृष्ठ ${page + 1}",
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = tx; translationY = ty
            },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun LiveTextPanel(book: BookMeta, page: Int, playback: TtsController.PlaybackState, modifier: Modifier) {
    val store = remember { BookStore(LocalContext.current) }
    val rawText by produceState("", book.id, page) {
        value = withContext(Dispatchers.IO) { store.ensurePageText(book.id, page) }
    }
    val text = remember(rawText) { TextUtil.speechText(rawText) }
    val active = playback.bookId == book.id && playback.page == page
    val start = if (active) playback.currentStart else -1
    val end = if (active) playback.currentEnd else -1
    val scroll = rememberScrollState()
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    LaunchedEffect(start, text, layoutResult) {
        val layout = layoutResult ?: return@LaunchedEffect
        if (start >= 0 && start < text.length) {
            val line = layout.getLineForOffset(start.coerceAtMost(text.length - 1))
            val top = layout.getLineTop(line).toInt()
            scroll.animateScrollTo((top - 70).coerceAtLeast(0))
        }
    }

    val annotated = buildAnnotatedString {
        append(if (text.isBlank()) "पृष्ठ का पाठ अभी तैयार हो रहा है…" else text)
        if (start >= 0 && end > start && start < text.length) {
            addStyle(
                SpanStyle(
                    background = MaterialTheme.colorScheme.primaryContainer,
                    fontWeight = FontWeight.SemiBold
                ),
                start,
                min(end, text.length)
            )
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("लाइव वाचन", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (active) playback.message else if (rawText.isBlank()) "तैयार किया जा रहा है…" else "पाठ तैयार",
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            annotated,
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = { layoutResult = it }
        )
    }
}

@Composable
private fun ReaderControls(
    playback: TtsController.PlaybackState,
    page: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onSpeed: () -> Unit,
    onJump: () -> Unit,
    onPageSlide: (Int) -> Unit,
    onPageSlideFinished: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(top = 6.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    ) {
        Slider(
            value = page.toFloat(),
            onValueChange = { onPageSlide(it.toInt()) },
            onValueChangeFinished = onPageSlideFinished,
            valueRange = 0f..max(0, pageCount - 1).toFloat(),
            steps = 0,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onPrevious, enabled = page > 0) { Icon(Icons.Default.ArrowBack, "पिछला") }
            OutlinedButton(onClick = onSpeed) { Text("${"%.2f".format(playback.speed)}x") }
            FilledIconButton(onClick = onPlayPause, Modifier.size(56.dp)) {
                Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "चलाएँ/रोकें")
            }
            OutlinedButton(onClick = onJump) { Text("पृष्ठ") }
            IconButton(onClick = onNext, enabled = page < pageCount - 1) { Icon(Icons.Default.ArrowForward, "अगला") }
        }
    }
}

@Composable
private fun MiniPlayer(playback: TtsController.PlaybackState, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = remember(playback.bookId) {
        playback.bookId?.let { BookStore(context).loadMeta(it)?.title } ?: "पुस्तक"
    }
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), tonalElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeUp, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("पृष्ठ ${playback.page + 1}/${playback.pageCount} • ${playback.message}", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { if (playback.playing) TtsController.pause() else TtsController.resume() }) {
                Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
            IconButton(onClick = TtsController::stop) { Icon(Icons.Default.Stop, "बंद") }
        }
    }
}

@Composable
private fun VoiceDialog(onDismiss: () -> Unit) {
    val options = remember { TtsController.voices() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("डिवाइस आवाज़") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(options, key = { it.name }) { voice ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            TtsController.useVoice(voice.name)
                            onDismiss()
                        }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeUp, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(voice.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${voice.localeTag} • ${if (voice.offline) "ऑफलाइन" else "इंटरनेट संभवतः आवश्यक"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("बंद") } }
    )
}

@Composable
private fun GeminiDialog(onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(TtsController.geminiApiKey()) }
    var voice by remember { mutableStateOf(TtsController.geminiVoiceName()) }
    val voices = listOf(
        "Orus" to "सख्त/परिपक्व",
        "Algieba" to "मुलायम",
        "Gacrux" to "परिपक्व",
        "Charon" to "सूचनात्मक"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini ऑनलाइन वाचन") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "यह उच्च-गुणवत्ता ऑनलाइन आवाज़ है। API key आपके डिवाइस में स्थानीय रूप से रखी जाएगी। " +
                        "पहली बार किसी chunk के लिए इंटरनेट चाहिए; बनी हुई WAV audio फिर cache से ऑफलाइन चल सकती है।",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Gemini API key") },
                    singleLine = true
                )
                Text("आवाज़", fontWeight = FontWeight.SemiBold)
                voices.forEach { (name, label) ->
                    FilterChip(
                        selected = voice == name,
                        onClick = { voice = name },
                        label = { Text("$name — $label") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                TtsController.setGeminiApiKey(key)
                TtsController.setGeminiVoiceName(voice)
                TtsController.setTtsMode("gemini")
                onDismiss()
            }) { Text("सेव करें") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("रद्द") }
        }
    )
}

private suspend fun queryDisplayName(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun renderPage(context: Context, book: BookMeta, pageIndex: Int): android.graphics.Bitmap? = runCatching {
    val file = BookStore(context).pdfFile(book.id)
    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val width = 1500
                val height = (width * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also {
                    page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
}.getOrNull()
