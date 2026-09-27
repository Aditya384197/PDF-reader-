package com.aditya.readaloud.pdf

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aditya.readaloud.data.BookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

class PdfIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_BOOK_ID = "book_id"
        private const val BATCH_SIZE = 4
        private const val MAX_PAGES_PER_RUN = 48
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val store = BookStore(applicationContext)
        val book = store.loadMeta(id) ?: return@withContext Result.success()
        val pdf = store.pdfFile(id)
        if (!pdf.exists()) return@withContext Result.failure()

        PdfExtractor.init(applicationContext)
        var processed = 0

        while (processed < MAX_PAGES_PER_RUN) {
            val priority = store.readPriorityPages(id)
                .filter { !store.pageTextFile(id, it).exists() }
            val fallback = store.nextUnindexedPage(id)
            val batch = (priority.take(BATCH_SIZE) + listOfNotNull(fallback))
                .distinct()
                .take(BATCH_SIZE)
            if (batch.isEmpty()) {
                store.markIndexingComplete(id)
                return@withContext Result.success()
            }

            val extracted = PdfExtractor.extractPagesText(pdf, batch)
            val needsOcr = batch.filter { extracted[it].orEmpty().clean().length < 24 }
            val ocr = PdfExtractor.ocrPages(applicationContext, pdf, needsOcr)

            for (page in batch) {
                val text = extracted[page].orEmpty().let { base ->
                    val scanned = ocr[page].orEmpty()
                    if (scanned.clean().length > base.clean().length) scanned else base
                }
                if (!store.pageTextFile(id, page).exists()) {
                    store.writePageText(id, page, text)
                    store.markPageReady(id)
                }
                store.removePagePriority(id, page)
            }
            processed += batch.size
        }

        store.scheduleIndexing(id)
        Result.success()
    }

    private fun String.clean(): String = replace(Regex("\s+"), " ").trim()
}
