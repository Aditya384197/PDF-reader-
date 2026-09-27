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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var refreshTick by remember { mutableIntStateOf(0) }
    val playback by TtsController.state.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var importDone by remember { mutableIntStateOf(0) }
    var importTotal by remember { mutableIntStateOf(0) }
    var importOcr by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importDone = 0
            importTotal = 0
            importError = null
            val name = queryDisplayName(context, uri) ?: "पुस्तक.pdf"
            runCatching {
                store.importFromUri(uri, name) { done, total, ocr ->
                    withContext(Dispatchers.Main) {
                        importDone = done
                        importTotal = total
                        importOcr = ocr
                    }
                }
            }.onFailure { importError = it.message ?: "PDF जोड़ना असफल रहा" }
            books = store.listBooks()
            importing = false
        }
    }

    LaunchedEffect(refreshTick) { books = store.listBooks() }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (selected == null) {
                LibraryScreen(
                    books = books,
                    playback = playback,
                    onOpen = { selected = it },
                    onDelete = { book ->
                        if (playback.bookId == book.id) TtsController.stop()
                        store.delete(book.id)
                        books = store.listBooks()
                    },
                    onImport = { launcher.launch(arrayOf("application/pdf")) },
                    onOpenPlaying = {
                        books.firstOrNull { it.id == playback.bookId }?.let { selected = it }
                    }
                )
            } else {
                val current = selected!!
                ReaderScreen(
                    book = current,
                    onBack = { selected = null; books = store.listBooks() },
                    onStartService = onStartPlaybackService,
                    onChanged = { refreshTick++ }
                )
            }
        }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("PDF तैयार हो रही है") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (importTotal > 0) "पृष्ठ $importDone / $importTotal" else "PDF पढ़ी जा रही है…")
                    if (importTotal > 0) LinearProgressIndicator(progress = { importDone.toFloat() / importTotal.toFloat() })
                    if (importOcr) Text("स्कैन पृष्ठ के लिए OCR चल रहा है…", style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }

    importError?.let { error ->
        AlertDialog(
            onDismissRequest = { importError = null },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("ठीक है") } },
            title = { Text("PDF नहीं जुड़ी") },
            text = { Text(error) }
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
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("पुस्तकालय", fontWeight = FontWeight.Bold)
                        Text("PDF पढ़ें और सुनें", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    Icon(Icons.Default.MenuBook, contentDescription = null)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, contentDescription = "ऐप जानकारी") }
                }
            )
        },
        floatingActionButton = {
            SmallFloatingActionButton(onClick = onImport) { Icon(Icons.Default.Add, contentDescription = "PDF जोड़ें") }
        },
        bottomBar = {
            if (playback.bookId != null) {
                MiniPlayer(playback = playback, onClick = onOpenPlaying)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
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
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (books.isEmpty()) "अभी कोई PDF नहीं है" else "कोई किताब नहीं मिली", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("+ दबाकर अपनी PDF किताब जोड़ें।", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 110.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookCard(book, onOpen = { onOpen(book) }, onDelete = { deleteTarget = book })
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
            text = { Text("ऑफलाइन PDF रीडर और टेक्स्ट-टू-स्पीच वाचन ऐप। PDF आपकी डिवाइस में ही रहती है। स्कैन पन्नों के लिए bundled OCR दिया गया है।") }
        )
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("किताब हटाएँ?") },
            text = { Text("“${book.title}” और उसका पढ़ने का डेटा हट जाएगा।") },
            confirmButton = {
                TextButton(onClick = {
                    if (playback.bookId == book.id) TtsController.stop()
                    onDelete(book)
                    deleteTarget = null
                }) { Text("हटाएँ") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("रद्द करें") } }
        )
    }
}

@Composable
private fun BookCard(book: BookMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BookStore(context) }
    val bitmap = remember(book.id, book.lastOpenedAt) { BitmapFactory.decodeFile(store.thumbnailFile(book.id).absolutePath) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(74.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val pct = if (book.pageCount == 0) 0 else ((book.lastPage + 1) * 100 / book.pageCount)
                Text("${book.pageCount} पृष्ठ • $pct%", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (book.pageCount == 0) 0f else (book.lastPage + 1f) / book.pageCount }, modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "हटाएँ") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    book: BookMeta,
    onBack: () -> Unit,
    onStartService: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { BookStore(context) }
    val playback by TtsController.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(book.id) { mutableIntStateOf(book.lastPage.coerceIn(0, book.pageCount - 1)) }
    var showText by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }

    LaunchedEffect(playback.page, playback.bookId) {
        if (playback.bookId == book.id) page = playback.page
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("पृष्ठ ${page + 1} / ${book.pageCount}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "वापस") } },
                actions = {
                    IconButton(onClick = { showText = !showText }) { Icon(Icons.Default.TextFields, contentDescription = "पाठ") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.MoreVert, contentDescription = "अधिक") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PdfPageView(book, page, Modifier.fillMaxWidth().weight(if (showText) 0.62f else 1f))
            if (showText) {
                LiveTextPanel(book, page, playback, Modifier.fillMaxWidth().weight(0.38f))
            }
            ReaderControls(
                playback = playback,
                page = page,
                pageCount = book.pageCount,
                onPrevious = { TtsController.previousPage(); page = max(0, page - 1); onChanged() },
                onNext = { TtsController.nextPage(); page = min(book.pageCount - 1, page + 1); onChanged() },
                onPlayPause = {
                    onStartService()
                    if (playback.bookId == book.id && playback.paused) TtsController.resume()
                    else if (playback.bookId == book.id && playback.playing) TtsController.pause()
                    else TtsController.start(book, page)
                    onChanged()
                },
                onSpeed = { showSpeed = true },
                onJump = { showJump = true },
                onPageSlide = { target -> page = target },
                onPageSlideFinished = { TtsController.jumpTo(page) }
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("वाचन सेटिंग", style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("भाषा", fontWeight = FontWeight.SemiBold)
                        Text(when (playback.languageMode) { "hi" -> "हिंदी"; "en" -> "English"; else -> "स्वतः पहचान" })
                    }
                    Row {
                        AssistChip(onClick = { TtsController.setLanguageMode("auto") }, label = { Text("स्वतः") })
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = { TtsController.setLanguageMode("en") }, label = { Text("EN") })
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = { TtsController.setLanguageMode("hi") }, label = { Text("HI") })
                    }
                }
                Divider()
                DropdownMenuItem(text = { Text("गति ${"%.2f".format(playback.speed)}x") }, leadingIcon = { Icon(Icons.Default.Speed, null) }, onClick = { showSettings = false; showSpeed = true })
                DropdownMenuItem(text = { Text("आवाज़ चुनें") }, leadingIcon = { Icon(Icons.Default.VolumeUp, null) }, onClick = { showSettings = false; showVoice = true })
                DropdownMenuItem(text = { Text(if (showText) "लाइव पाठ छिपाएँ" else "लाइव पाठ दिखाएँ") }, leadingIcon = { Icon(Icons.Default.TextFields, null) }, onClick = { showText = !showText; showSettings = false })
            }
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("वाचन गति") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${"%.2f".format(playback.speed)}x")
                    Slider(value = playback.speed, onValueChange = { TtsController.setSpeed(it) }, valueRange = 0.5f..2.0f, steps = 5)
                    Text("0.5x — 2.0x", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("हो गया") } }
        )
    }

    if (showVoice) {
        VoiceDialog(onDismiss = { showVoice = false })
    }

    if (showJump) {
        var value by rememberSaveable { mutableStateOf((page + 1).toString()) }
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("पृष्ठ पर जाएँ") },
            text = {
                androidx.compose.material3.OutlinedTextField(value = value, onValueChange = { value = it.filter(Char::isDigit) }, label = { Text("पृष्ठ संख्या") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = value.toIntOrNull()?.minus(1)
                    if (target != null) { page = target.coerceIn(0, book.pageCount - 1); TtsController.jumpTo(page) }
                    showJump = false
                }) { Text("जाएँ") }
            },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("रद्द") } }
        )
    }
}

@Composable
private fun PdfPageView(book: BookMeta, page: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var scale by remember(book.id, page) { mutableFloatStateOf(1f) }
    var tx by remember(book.id, page) { mutableFloatStateOf(0f) }
    var ty by remember(book.id, page) { mutableFloatStateOf(0f) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.id, page) {
        value = withContext(Dispatchers.IO) { renderPage(context, book, page, 1500) }
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(book.id, page) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 3f)
                    tx += pan.x
                    ty += pan.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(28.dp))
        } else {
            Image(
                bitmap!!.asImageBitmap(),
                contentDescription = "PDF पृष्ठ ${page + 1}",
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = tx; translationY = ty },
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun LiveTextPanel(book: BookMeta, page: Int, playback: TtsController.PlaybackState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { BookStore(context) }
    val rawText by produceState("", book.id, page) { value = withContext(Dispatchers.IO) { store.readPageText(book.id, page) } }
    val text = remember(rawText) { TextUtil.speechText(rawText) }
    val start = if (playback.bookId == book.id && playback.page == page) playback.currentStart else -1
    val end = if (playback.bookId == book.id && playback.page == page) playback.currentEnd else -1
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val annotated = buildAnnotatedString {
        append(text)
        if (start >= 0 && end > start && start < text.length) {
            val safeEnd = min(end, text.length)
            addStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.SemiBold), start, safeEnd)
        }
    }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    LaunchedEffect(start, text) {
        val currentLayout = layout ?: return@LaunchedEffect
        if (start >= 0 && start < text.length) {
            val line = currentLayout.getLineForOffset(start.coerceAtMost(text.length - 1))
            val top = currentLayout.getLineTop(line).toInt()
            scroll.animateScrollTo((top - 70).coerceAtLeast(0))
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("लाइव वाचन", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (playback.bookId == book.id && playback.page == page) playback.message else "पृष्ठ का पाठ", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = annotated,
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight),
            onTextLayout = { layout = it }
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
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = 6.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        Slider(value = page.toFloat(), onValueChange = { onPageSlide(it.toInt()) }, onValueChangeFinished = onPageSlideFinished, valueRange = 0f..max(0, pageCount - 1).toFloat(), steps = 0, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = onPrevious, enabled = page > 0) { Icon(Icons.Default.ArrowBack, contentDescription = "पिछला पृष्ठ") }
            OutlinedButton(onClick = onSpeed) { Text("${"%.2f".format(playback.speed)}x") }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playback.playing) "रोकें" else "चलाएँ")
            }
            OutlinedButton(onClick = onJump) { Text("पृष्ठ") }
            IconButton(onClick = onNext, enabled = page < pageCount - 1) { Icon(Icons.Default.ArrowForward, contentDescription = "अगला पृष्ठ") }
        }
    }
}

@Composable
private fun MiniPlayer(playback: TtsController.PlaybackState, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = remember(playback.bookId) { playback.bookId?.let { BookStore(context).loadMeta(it)?.title } ?: "पुस्तक" }
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), tonalElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: "पुस्तक", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("पृष्ठ ${playback.page + 1} / ${playback.pageCount} • ${playback.message}", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { if (playback.playing) TtsController.pause() else TtsController.resume() }) {
                Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
            }
            IconButton(onClick = { TtsController.stop() }) { Icon(Icons.Default.Stop, contentDescription = "बंद") }
        }
    }
}

@Composable
private fun VoiceDialog(onDismiss: () -> Unit) {
    val options = remember { TtsController.voices() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("आवाज़ चुनें") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(options, key = { it.name }) { voice ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { TtsController.useVoice(voice.name); onDismiss() }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(voice.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${voice.localeTag} • ${if (voice.offline) "ऑफलाइन" else "ऑनलाइन आवश्यक हो सकता है"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("बंद") } }
    )
}

private suspend fun queryDisplayName(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun renderPage(context: Context, book: BookMeta, pageIndex: Int, maxWidth: Int): android.graphics.Bitmap? = runCatching {
    val file = BookStore(context).pdfFile(book.id)
    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val width = maxWidth.coerceAtLeast(1)
                val height = (width * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also {
                    page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
}.getOrNull()
