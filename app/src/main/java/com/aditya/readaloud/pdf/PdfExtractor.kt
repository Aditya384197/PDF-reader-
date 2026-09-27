package com.aditya.readaloud.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfExtractor {
    private var initialized = false
    private var recognizer: TextRecognizer? = null

    @Synchronized
    fun init(context: Context) {
        if (!initialized) {
            val app = context.applicationContext
            PDFBoxResourceLoader.init(app)
            recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            initialized = true
        }
    }

    fun pageCount(file: File): Int {
        check(initialized)
        PDDocument.load(file).use { return it.numberOfPages }
    }

    fun documentTitle(file: File): String = runCatching {
        check(initialized)
        PDDocument.load(file).use { it.documentInformation?.title.orEmpty() }
    }.getOrDefault("")

    fun extractPageText(file: File, pageIndex: Int): String = runCatching {
        check(initialized)
        PDDocument.load(file).use { doc ->
            extractPageText(doc, pageIndex)
        }
    }.getOrDefault("")

    fun extractPagesText(file: File, pageIndexes: List<Int>): Map<Int, String> = runCatching {
        check(initialized)
        if (pageIndexes.isEmpty()) return@runCatching emptyMap()
        PDDocument.load(file).use { doc ->
            pageIndexes.distinct().associateWith { extractPageText(doc, it) }
        }
    }.getOrDefault(emptyMap())

    fun extractAllPageText(file: File): List<String> = runCatching {
        check(initialized)
        PDDocument.load(file).use { doc ->
            (0 until doc.numberOfPages).map { extractPageText(doc, it) }
        }
    }.getOrDefault(emptyList())

    fun ocrPage(context: Context, file: File, pageIndex: Int): String =
        ocrPages(context, file, listOf(pageIndex))[pageIndex].orEmpty()

    fun ocrPages(context: Context, file: File, pageIndexes: List<Int>): Map<Int, String> {
        init(context)
        if (pageIndexes.isEmpty()) return emptyMap()
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageIndexes.distinct().filter { it in 0 until renderer.pageCount }.associateWith { index ->
                        renderer.openPage(index).use { page ->
                            val maxWidth = 1100
                            val width = maxWidth
                            val height = (maxWidth * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                Tasks.await(recognizer!!.process(InputImage.fromBitmap(bitmap, 0))).text.normalizeOcr()
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun extractPageText(doc: PDDocument, pageIndex: Int): String {
        if (pageIndex !in 0 until doc.numberOfPages) return ""
        return PDFTextStripper().apply {
            setSortByPosition(true)
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            lineSeparator = "
"
            wordSeparator = " "
        }.getText(doc).normalizeExtracted()
    }

    private fun String.normalizeExtracted(): String =
        replace("­", "")
            .replace(Regex("-\s*\n\s*"), "")
            .replace(Regex("[ \t]+\n"), "
")
            .replace(Regex("\n{3,}"), "

")
            .trim()

    private fun String.normalizeOcr(): String =
        replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "

")
            .trim()
}
