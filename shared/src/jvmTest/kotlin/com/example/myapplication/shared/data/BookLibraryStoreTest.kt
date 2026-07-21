package com.example.myapplication.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.processing.BookChunkLemmaCount
import com.example.myapplication.shared.processing.BookIndex
import com.example.myapplication.shared.processing.BookIndexMetadata
import com.example.myapplication.shared.processing.BookLemmaCount
import com.example.myapplication.shared.processing.BookLemmaSurfaceForm
import com.example.myapplication.shared.processing.BookProcessingState
import com.example.myapplication.shared.processing.BookProcessingStatus
import com.example.myapplication.shared.processing.DefaultAnalysisIndexVersion
import com.example.myapplication.shared.processing.DefaultAnalysisLanguage
import com.example.myapplication.shared.processing.DefaultAnalysisModelId
import com.example.myapplication.shared.processing.DefaultAnalysisModelVersion
import com.example.myapplication.shared.processing.DefaultAnalysisProvider
import com.example.myapplication.shared.processing.DefaultBookPreprocessingPipelineFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals

class BookLibraryStoreTest {
    @Test
    fun replaceBookIndexPersistsSurfaceWordsAndUiOrdering() {
        val store = createStore()
        store.upsertBook(testBook)

        store.replaceBookIndex(
            status = completedStatus(uniqueLemmaCount = 3L),
            index = BookIndex(
                metadata = indexMetadata(uniqueLemmaCount = 3L),
                lemmaCounts = listOf(
                    lemmaCount(lemma = "alpha", totalCount = 30L, tfIdfScore = 4.0),
                    lemmaCount(lemma = "gamma", totalCount = 30L, tfIdfScore = 5.0),
                    lemmaCount(lemma = "beta", totalCount = 20L, tfIdfScore = 9.0),
                ),
                lemmaSurfaceForms = listOf(
                    surfaceForm(lemma = "alpha", surfaceWord = "alpha", count = 10L),
                    surfaceForm(lemma = "alpha", surfaceWord = "Alpha", count = 2L),
                    surfaceForm(lemma = "gamma", surfaceWord = "gamma", count = 7L),
                    surfaceForm(lemma = "beta", surfaceWord = "betas", count = 6L),
                    surfaceForm(lemma = "beta", surfaceWord = "beta", count = 14L),
                ),
                chunkLemmaCounts = listOf(
                    BookChunkLemmaCount(bookId = testBook.id, chunkId = 0L, lemma = "alpha", localCount = 3L),
                    BookChunkLemmaCount(bookId = testBook.id, chunkId = 0L, lemma = "gamma", localCount = 6L),
                    BookChunkLemmaCount(bookId = testBook.id, chunkId = 0L, lemma = "beta", localCount = 6L),
                ),
            ),
        )

        val lemmas = store.getLemmaCounts(bookId = testBook.id)
        assertEquals(listOf("gamma", "alpha", "beta"), lemmas.map { it.lemma })
        assertEquals(listOf("gamma"), lemmas.first { it.lemma == "gamma" }.surfaceWords)
        assertEquals(listOf("beta", "betas"), lemmas.first { it.lemma == "beta" }.surfaceWords)
        assertEquals(listOf("alpha", "Alpha"), lemmas.first { it.lemma == "alpha" }.surfaceWords)

        val chunkCounts = store.getChunkLemmaCounts(bookId = testBook.id, chunkId = 0L)
        assertEquals(listOf("beta", "gamma", "alpha"), chunkCounts.map { it.lemma })
    }

    @Test
    fun replaceBookIndexDeletesOldSurfaceWords() {
        val store = createStore()
        store.upsertBook(testBook)

        store.replaceBookIndex(
            status = completedStatus(uniqueLemmaCount = 1L),
            index = BookIndex(
                metadata = indexMetadata(uniqueLemmaCount = 1L),
                lemmaCounts = listOf(lemmaCount(lemma = "old", totalCount = 20L, tfIdfScore = 8.0)),
                lemmaSurfaceForms = listOf(surfaceForm(lemma = "old", surfaceWord = "older", count = 3L)),
                chunkLemmaCounts = listOf(
                    BookChunkLemmaCount(bookId = testBook.id, chunkId = 0L, lemma = "old", localCount = 3L),
                ),
            ),
        )
        store.replaceBookIndex(
            status = completedStatus(uniqueLemmaCount = 1L),
            index = BookIndex(
                metadata = indexMetadata(uniqueLemmaCount = 1L),
                lemmaCounts = listOf(lemmaCount(lemma = "new", totalCount = 25L, tfIdfScore = 9.0)),
                lemmaSurfaceForms = listOf(surfaceForm(lemma = "new", surfaceWord = "newer", count = 4L)),
                chunkLemmaCounts = listOf(
                    BookChunkLemmaCount(bookId = testBook.id, chunkId = 0L, lemma = "new", localCount = 4L),
                ),
            ),
        )

        assertEquals(listOf("new"), store.getLemmaCounts(bookId = testBook.id).map { it.lemma })
        assertEquals(listOf("newer"), store.getLemmaSurfaceForms(bookId = testBook.id).map { it.surfaceWord })
        assertEquals(listOf("new"), store.getChunkLemmaCounts(bookId = testBook.id, chunkId = 0L).map { it.lemma })
    }

    private fun createStore(): BookLibraryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BookDatabase.Schema.create(driver)
        return BookLibraryStore(driver)
    }

    private fun completedStatus(uniqueLemmaCount: Long): BookProcessingStatus =
        BookProcessingStatus(
            bookId = testBook.id,
            language = DefaultAnalysisLanguage,
            nlpProvider = DefaultAnalysisProvider,
            udpipeVersion = "1.3.1",
            modelId = DefaultAnalysisModelId,
            modelVersion = DefaultAnalysisModelVersion,
            indexVersion = DefaultAnalysisIndexVersion,
            pipelineFingerprint = DefaultBookPreprocessingPipelineFingerprint,
            state = BookProcessingState.Completed,
            tokenCount = 200L,
            uniqueLemmaCount = uniqueLemmaCount,
            savedIndexSizeBytes = 128L,
            processedAtMillis = 3_000L,
        )

    private fun indexMetadata(uniqueLemmaCount: Long): BookIndexMetadata =
        BookIndexMetadata(
            bookId = testBook.id,
            language = DefaultAnalysisLanguage,
            nlpProvider = DefaultAnalysisProvider,
            udpipeVersion = "1.3.1",
            modelId = DefaultAnalysisModelId,
            modelVersion = DefaultAnalysisModelVersion,
            indexVersion = DefaultAnalysisIndexVersion,
            tokenCount = 200L,
            uniqueLemmaCount = uniqueLemmaCount,
            savedIndexSizeBytes = 128L,
            processedAtMillis = 3_000L,
        )

    private fun lemmaCount(
        lemma: String,
        totalCount: Long,
        tfIdfScore: Double,
    ): BookLemmaCount =
        BookLemmaCount(
            bookId = testBook.id,
            lemma = lemma,
            totalCount = totalCount,
            globalFrequencyZipf = null,
            tfIdfScore = tfIdfScore,
        )

    private fun surfaceForm(
        lemma: String,
        surfaceWord: String,
        count: Long,
    ): BookLemmaSurfaceForm =
        BookLemmaSurfaceForm(
            bookId = testBook.id,
            lemma = lemma,
            surfaceWord = surfaceWord,
            count = count,
        )

    private companion object {
        val testBook = BookItem(
            id = "book-1",
            uriString = "file:///book.epub",
            title = "Test Book",
            author = "Test Author",
            coverUriString = null,
            lastOpenedAtMillis = 1L,
        )
    }
}
