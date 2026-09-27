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
        if (initialized) return
        val app = context.applicationContext
        PDFBoxResourceLoader.init(app)
        recognizer = TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build()
        )
        initialized = true
    }

    fun pageCount(file: File): Int {
        check(initialized) { "PdfExtractor.init(context) पहले कॉल करें" }
        PDDocument.load(file).use { document ->
            return document.numberOfPages
        }
    }

    fun documentTitle(file: File): String = runCatching {
        check(initialized) { "PdfExtractor.init(context) पहले कॉल करें" }
        PDDocument.load(file).use { document ->
            document.documentInformation?.title.orEmpty()
        }
    }.getOrDefault("")

    fun extractPageText(file: File, pageIndex: Int): String = runCatching {
        check(initialized) { "PdfExtractor.init(context) पहले कॉल करें" }
        PDDocument.load(file).use { document ->
            extractPageText(document, pageIndex)
        }
    }.getOrDefault("")

    fun extractPagesText(file: File, pageIndexes: List<Int>): Map<Int, String> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return runCatching {
            check(initialized) { "PdfExtractor.init(context) पहले कॉल करें" }
            PDDocument.load(file).use { document ->
                pageIndexes.distinct()
                    .filter { it in 0 until document.numberOfPages }
                    .associateWith { index -> extractPageText(document, index) }
            }
        }.getOrDefault(emptyMap())
    }

    fun ocrPage(context: Context, file: File, pageIndex: Int): String {
        return ocrPages(context, file, listOf(pageIndex))[pageIndex].orEmpty()
    }

    fun ocrPages(context: Context, file: File, pageIndexes: List<Int>): Map<Int, String> {
        init(context)
        if (pageIndexes.isEmpty()) return emptyMap()
        val activeRecognizer = recognizer ?: return emptyMap()
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageIndexes.distinct()
                        .filter { it in 0 until renderer.pageCount }
                        .associateWith { index ->
                            renderer.openPage(index).use { page ->
                                val width = 1100
                                val height = (width * page.height / page.width.toFloat())
                                    .toInt()
                                    .coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(
                                    width,
                                    height,
                                    Bitmap.Config.ARGB_8888
                                )
                                try {
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                    )
                                    val image = InputImage.fromBitmap(bitmap, 0)
                                    Tasks.await(activeRecognizer.process(image)).text.normalizeOcr()
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun extractPageText(document: PDDocument, pageIndex: Int): String {
        if (pageIndex !in 0 until document.numberOfPages) return ""
        val stripper = PDFTextStripper().apply {
            setSortByPosition(true)
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            lineSeparator = "\n"
            wordSeparator = " "
        }
        return stripper.getText(document).normalizeExtracted()
    }

    private fun String.normalizeExtracted(): String =
        replace("\u00AD", "")
            .replace(Regex("-\\s*\\n\\s*"), "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun String.normalizeOcr(): String =
        replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
