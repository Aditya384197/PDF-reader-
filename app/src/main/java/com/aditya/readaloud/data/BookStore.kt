package com.aditya.readaloud.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aditya.readaloud.pdf.PdfExtractor
import com.aditya.readaloud.pdf.PdfIndexWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class BookStore(private val context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val priorityLocks = HashMap<String, Any>()

    fun listBooks(): List<BookMeta> = root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { loadMeta(it.name) }
        ?.sortedByDescending { it.lastOpenedAt }
        ?: emptyList()

    fun loadMeta(id: String): BookMeta? = runCatching {
        val file = File(File(root, id), "meta.json")
        if (!file.exists()) return null
        BookMeta.fromJson(org.json.JSONObject(file.readText()))
    }.getOrNull()

    fun pdfFile(id: String): File = File(File(root, id), "book.pdf")
    fun pageTextFile(id: String, pageIndex: Int): File = File(File(root, id), "page_$pageIndex.txt")
    fun thumbnailFile(id: String): File = File(File(root, id), "thumb.jpg")

    fun audioCacheFile(id: String, pageIndex: Int, start: Int, voice: String, text: String): File {
        val dir = File(File(root, id), "audio").apply { mkdirs() }
        val key = sha1("gemini-3.8-flash-tts|$voice|$pageIndex|$start|$text")
        return File(dir, "$key.wav")
    }

    private fun priorityFile(id: String): File = File(File(root, id), "priority.pages")

    fun readPageText(id: String, pageIndex: Int): String = runCatching {
        pageTextFile(id, pageIndex).takeIf { it.exists() }?.readText() ?: ""
    }.getOrDefault("")

    suspend fun importFromUri(uri: Uri, displayName: String): BookMeta = withContext(Dispatchers.IO) {
        PdfExtractor.init(context)
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val pdf = File(dir, "book.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(pdf).use { output -> input.copyTo(output, 1024 * 512) }
            } ?: error("PDF खुल नहीं सका")

            val pageCount = PdfExtractor.pageCount(pdf)
            require(pageCount > 0) { "PDF में कोई पृष्ठ नहीं है" }
            val title = PdfExtractor.documentTitle(pdf)
                .takeUnless { it.isBlank() }
                ?: displayName.removeSuffix(".pdf").removeSuffix(".PDF")

            renderThumbnail(pdf)?.let { thumb ->
                FileOutputStream(thumbnailFile(id)).use { out ->
                    thumb.compress(Bitmap.CompressFormat.JPEG, 78, out)
                }
                thumb.recycle()
            }

            val now = System.currentTimeMillis()
            val meta = BookMeta(
                id = id,
                title = title,
                fileName = pdf.name,
                pageCount = pageCount,
                sizeBytes = pdf.length(),
                importedAt = now,
                lastOpenedAt = now,
                indexedPages = 0,
                indexing = true
            )
            saveMeta(meta)
            requestPagePriority(id, 0, 5, schedule = false)
            scheduleIndexing(id)
            meta
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    suspend fun ensurePageText(id: String, pageIndex: Int): String = withContext(Dispatchers.IO) {
        val book = loadMeta(id) ?: return@withContext ""
        if (book.pageCount <= 0) return@withContext ""
        val safePage = pageIndex.coerceIn(0, book.pageCount - 1)
        val target = pageTextFile(id, safePage)
        if (target.exists()) return@withContext target.readText()

        val pdf = pdfFile(id)
        if (!pdf.exists()) return@withContext ""

        var text = PdfExtractor.extractPageText(pdf, safePage)
        if (clean(text).length < 24) {
            val ocr = PdfExtractor.ocrPage(context, pdf, safePage)
            if (clean(ocr).length > clean(text).length) text = ocr
        }
        writePageText(id, safePage, text)
        markPageReady(id)
        removePagePriority(id, safePage)
        text
    }

    fun requestPagePriority(id: String, page: Int, lookAhead: Int = 5, schedule: Boolean = true) {
        val book = loadMeta(id) ?: return
        if (book.pageCount <= 0) return
        val start = page.coerceIn(0, book.pageCount - 1)
        val end = (start + lookAhead - 1).coerceAtMost(book.pageCount - 1)
        synchronized(lockFor(id)) {
            val current = readPrioritySet(id)
            for (p in start..end) {
                if (!pageTextFile(id, p).exists()) current += p
            }
            writePrioritySet(id, current)
        }
        if (schedule) scheduleIndexing(id)
    }

    fun readPriorityPages(id: String): List<Int> = synchronized(lockFor(id)) {
        readPrioritySet(id).sorted()
    }

    fun removePagePriority(id: String, page: Int) = synchronized(lockFor(id)) {
        val current = readPrioritySet(id)
        if (current.remove(page)) writePrioritySet(id, current)
    }

    fun nextUnindexedPage(id: String): Int? {
        val book = loadMeta(id) ?: return null
        return (0 until book.pageCount).firstOrNull { !pageTextFile(id, it).exists() }
    }

    fun writePageText(id: String, page: Int, text: String) {
        val target = pageTextFile(id, page)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "पृष्ठ पाठ सहेजा नहीं जा सका" }
    }

    fun markPageReady(id: String) {
        val current = loadMeta(id) ?: return
        val indexed = (0 until current.pageCount).count { pageTextFile(id, it).exists() }
        saveMeta(current.copy(indexedPages = indexed, indexing = indexed < current.pageCount))
    }

    fun markIndexingComplete(id: String) {
        val current = loadMeta(id) ?: return
        val indexed = (0 until current.pageCount).count { pageTextFile(id, it).exists() }
        saveMeta(current.copy(indexedPages = indexed, indexing = indexed < current.pageCount))
    }

    fun saveMeta(meta: BookMeta) {
        val file = File(File(root, meta.id), "meta.json")
        val temp = File(file.parentFile, "meta.json.tmp")
        temp.writeText(meta.toJson().toString())
        if (file.exists()) file.delete()
        check(temp.renameTo(file))
    }

    fun updateProgress(meta: BookMeta, page: Int, speed: Float, languageMode: String, voiceName: String?) {
        val latest = loadMeta(meta.id) ?: meta
        saveMeta(latest.copy(
            lastPage = page.coerceIn(0, (latest.pageCount - 1).coerceAtLeast(0)),
            lastSpeed = speed,
            languageMode = languageMode,
            voiceName = voiceName,
            lastOpenedAt = System.currentTimeMillis()
        ))
    }

    fun delete(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        File(root, id).deleteRecursively()
        synchronized(priorityLocks) { priorityLocks.remove(id) }
    }

    fun scheduleIndexing(id: String) {
        val request = OneTimeWorkRequestBuilder<PdfIndexWorker>()
            .setInputData(workDataOf(PdfIndexWorker.KEY_BOOK_ID to id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun workName(id: String) = "pdf_index_$id"

    private fun lockFor(id: String): Any = synchronized(priorityLocks) {
        priorityLocks.getOrPut(id) { Any() }
    }

    private fun readPrioritySet(id: String): MutableSet<Int> =
        priorityFile(id).takeIf { it.exists() }?.readLines()
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toMutableSet() ?: mutableSetOf()

    private fun writePrioritySet(id: String, pages: Set<Int>) {
        val file = priorityFile(id)
        if (pages.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        file.writeText(pages.sorted().joinToString("\n"))
    }

    private fun renderThumbnail(pdf: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val width = 240
                    val height = (width * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
