package com.aditya.readaloud.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.aditya.readaloud.pdf.PdfExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BookStore(private val context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }

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

    fun readPageText(id: String, pageIndex: Int): String = runCatching {
        pageTextFile(id, pageIndex).takeIf { it.exists() }?.readText() ?: ""
    }.getOrDefault("")

    suspend fun importFromUri(
        uri: Uri,
        displayName: String,
        onProgress: suspend (done: Int, total: Int, ocr: Boolean) -> Unit
    ): BookMeta = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val pdf = File(dir, "book.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(pdf).use { output -> input.copyTo(output, 1024 * 128) }
            } ?: error("PDF खुल नहीं सका")

            val pageCount = PdfExtractor.pageCount(pdf)
            require(pageCount > 0) { "PDF में कोई पृष्ठ नहीं है" }
            val title = PdfExtractor.documentTitle(pdf).takeUnless { it.isBlank() } ?: displayName.removeSuffix(".pdf")
            val thumb = renderThumbnail(pdf)
            if (thumb != null) {
                FileOutputStream(thumbnailFile(id)).use { out ->
                    thumb.compress(Bitmap.CompressFormat.JPEG, 78, out)
                }
                thumb.recycle()
            }

            for (page in 0 until pageCount) {
                var text = PdfExtractor.extractPageText(pdf, page)
                var usedOcr = false
                if (text.cleanForSpeech().length < 24) {
                    val ocrText = PdfExtractor.ocrPage(context, pdf, page)
                    if (ocrText.cleanForSpeech().length > text.cleanForSpeech().length) {
                        text = ocrText
                        usedOcr = true
                    }
                }
                pageTextFile(id, page).writeText(text)
                onProgress(page + 1, pageCount, usedOcr)
            }

            val now = System.currentTimeMillis()
            val meta = BookMeta(
                id = id,
                title = title,
                fileName = pdf.name,
                pageCount = pageCount,
                sizeBytes = pdf.length(),
                importedAt = now,
                lastOpenedAt = now
            )
            saveMeta(meta)
            meta
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    fun saveMeta(meta: BookMeta) {
        File(File(root, meta.id), "meta.json").writeText(meta.toJson().toString())
    }

    fun updateProgress(meta: BookMeta, page: Int, speed: Float, languageMode: String, voiceName: String?) {
        saveMeta(meta.copy(
            lastPage = page.coerceIn(0, meta.pageCount - 1),
            lastSpeed = speed,
            languageMode = languageMode,
            voiceName = voiceName,
            lastOpenedAt = System.currentTimeMillis()
        ))
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
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

    private fun String.cleanForSpeech(): String = replace(Regex("\\s+"), " ").trim()
}
