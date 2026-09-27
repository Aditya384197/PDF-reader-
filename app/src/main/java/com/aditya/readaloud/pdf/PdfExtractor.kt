package com.aditya.readaloud.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Tasks
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfExtractor {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    fun pageCount(file: File): Int {
        initFileMode()
        PDDocument.load(file).use { return it.numberOfPages }
    }

    fun documentTitle(file: File): String {
        initFileMode()
        return runCatching { PDDocument.load(file).use { it.documentInformation?.title.orEmpty() } }.getOrDefault("")
    }

    fun extractPageText(file: File, pageIndex: Int): String {
        initFileMode()
        return runCatching {
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper().apply {
                    setSortByPosition(true)
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                    lineSeparator = "\n"
                    wordSeparator = " "
                }
                stripper.getText(doc).normalizeExtracted()
            }
        }.getOrDefault("")
    }

    fun ocrPage(context: Context, file: File, pageIndex: Int): String {
        init(context)
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return ""
                    renderer.openPage(pageIndex).use { page ->
                        val maxWidth = 1600
                        val width = maxWidth
                        val height = (maxWidth * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try {
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val recognizer = TextRecognition.getClient(
                                DevanagariTextRecognizerOptions.Builder().build()
                            )
                            try {
                                Tasks.await(recognizer.process(image)).text.normalizeOcr()
                            } finally {
                                recognizer.close()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }.getOrDefault("")
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

    private fun initFileMode() {
        check(initialized) { "PdfExtractor.init(context) पहले कॉल करें" }
    }
}
