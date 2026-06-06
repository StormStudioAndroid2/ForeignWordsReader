package com.example.myapplication.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.processing.BookChunkLemmaCount
import com.example.myapplication.shared.processing.BookIndex
import com.example.myapplication.shared.processing.BookLemmaCount
import com.example.myapplication.shared.processing.BookProcessingStore
import com.example.myapplication.shared.processing.BookProcessingStatus
import com.example.myapplication.shared.processing.BookProcessingState
import com.example.myapplication.shared.processing.DefaultAnalysisIndexVersion
import com.example.myapplication.shared.processing.DefaultAnalysisLanguage
import com.example.myapplication.shared.processing.DefaultAnalysisModelVersion
import com.example.myapplication.shared.processing.DefaultAnalysisProvider
import com.example.myapplication.shared.processing.DefaultBookPreprocessingPipelineFingerprint

class BookLibraryStore(
    driver: SqlDriver,
) : BookProcessingStore {
    private val database = BookDatabase(driver)
    private val queries = database.bookDatabaseQueries

    fun getRecentBooks(language: String = DefaultAnalysisLanguage): List<BookItem> =
        queries.selectRecentBooks(
            language = language,
            mapper = ::mapBook,
        ).executeAsList()

    fun getBook(uriString: String, language: String = DefaultAnalysisLanguage): BookItem? =
        queries.selectByUri(
            language = language,
            uri_string = uriString,
            mapper = ::mapBook,
        ).executeAsOneOrNull()

    fun upsertBook(book: BookItem) {
        queries.upsertBook(
            id = book.id,
            uri_string = book.uriString,
            title = book.title,
            author = book.author,
            cover_uri_string = book.coverUriString,
            last_opened_at_millis = book.lastOpenedAtMillis,
        )
    }

    fun markBookOpened(uriString: String, lastOpenedAtMillis: Long): BookItem? {
        queries.updateLastOpened(
            last_opened_at_millis = lastOpenedAtMillis,
            uri_string = uriString,
        )
        return getBook(uriString)
    }

    override fun getProcessingStatus(bookId: String, language: String): BookProcessingStatus? =
        queries.selectProcessingStatus(
            book_id = bookId,
            language = language,
            mapper = ::mapProcessingStatus,
        ).executeAsOneOrNull()

    override fun hasCurrentBookIndex(
        bookId: String,
        language: String,
        nlpProvider: String,
        modelVersion: String,
        indexVersion: Long,
        pipelineFingerprint: String,
    ): Boolean =
        queries.selectCurrentCompletedIndex(
            book_id = bookId,
            language = language,
            nlp_provider = nlpProvider,
            model_version = modelVersion,
            index_version = indexVersion,
            pipeline_fingerprint = pipelineFingerprint,
        ).executeAsOneOrNull() != null

    fun hasCurrentBookIndex(
        bookId: String,
        language: String,
    ): Boolean =
        hasCurrentBookIndex(
            bookId = bookId,
            language = language,
            nlpProvider = DefaultAnalysisProvider,
            modelVersion = DefaultAnalysisModelVersion,
            indexVersion = DefaultAnalysisIndexVersion,
            pipelineFingerprint = DefaultBookPreprocessingPipelineFingerprint,
        )

    override fun upsertProcessingStatus(status: BookProcessingStatus) {
        queries.upsertProcessingStatus(
            book_id = status.bookId,
            language = status.language,
            nlp_provider = status.nlpProvider,
            udpipe_version = status.udpipeVersion,
            model_id = status.modelId,
            model_version = status.modelVersion,
            index_version = status.indexVersion,
            pipeline_fingerprint = status.pipelineFingerprint,
            state = status.state.name,
            token_count = status.tokenCount,
            unique_lemma_count = status.uniqueLemmaCount,
            saved_index_size_bytes = status.savedIndexSizeBytes,
            processed_at_millis = status.processedAtMillis,
            error_message = status.errorMessage,
        )
    }

    override fun replaceBookIndex(
        status: BookProcessingStatus,
        index: BookIndex,
    ) {
        queries.transaction {
            queries.deleteLemmaTotals(
                book_id = status.bookId,
                language = status.language,
            )
            queries.deleteChunkLemmaCounts(
                book_id = status.bookId,
                language = status.language,
            )
            upsertProcessingStatus(status)
            index.lemmaCounts.forEach { count ->
                queries.insertLemmaTotal(
                    book_id = count.bookId,
                    language = status.language,
                    lemma = count.lemma,
                    total_count = count.totalCount,
                    global_frequency_zipf = count.globalFrequencyZipf,
                    tf_idf_score = count.tfIdfScore,
                )
            }
            index.chunkLemmaCounts.forEach { count ->
                queries.insertChunkLemmaCount(
                    book_id = count.bookId,
                    language = status.language,
                    chunk_id = count.chunkId,
                    lemma = count.lemma,
                    local_count = count.localCount,
                )
            }
        }
    }

    fun getLemmaCounts(bookId: String, language: String = DefaultAnalysisLanguage): List<BookLemmaCount> =
        queries.selectLemmaTotals(
            book_id = bookId,
            language = language,
            mapper = { rowBookId, _, lemma, totalCount, globalFrequencyZipf, tfIdfScore ->
                BookLemmaCount(
                    bookId = rowBookId,
                    lemma = lemma,
                    totalCount = totalCount,
                    globalFrequencyZipf = globalFrequencyZipf,
                    tfIdfScore = tfIdfScore,
                )
            },
        ).executeAsList()

    fun getChunkLemmaCounts(
        bookId: String,
        chunkId: Long,
        language: String = DefaultAnalysisLanguage,
    ): List<BookChunkLemmaCount> =
        queries.selectChunkLemmaCounts(
            book_id = bookId,
            language = language,
            chunk_id = chunkId,
            mapper = { rowBookId, _, rowChunkId, lemma, localCount ->
                BookChunkLemmaCount(
                    bookId = rowBookId,
                    chunkId = rowChunkId,
                    lemma = lemma,
                    localCount = localCount,
                )
            },
        ).executeAsList()

    private fun mapBook(
        id: String,
        uriString: String,
        title: String,
        author: String,
        coverUriString: String?,
        lastOpenedAtMillis: Long,
        language: String?,
        nlpProvider: String?,
        udpipeVersion: String?,
        modelId: String?,
        modelVersion: String?,
        indexVersion: Long?,
        pipelineFingerprint: String?,
        state: String?,
        tokenCount: Long?,
        uniqueLemmaCount: Long?,
        savedIndexSizeBytes: Long?,
        processedAtMillis: Long?,
        errorMessage: String?,
    ): BookItem =
        BookItem(
            id = id,
            uriString = uriString,
            title = title,
            author = author,
            coverUriString = coverUriString,
            lastOpenedAtMillis = lastOpenedAtMillis,
            processingState = state.toProcessingState(),
            processingTokenCount = tokenCount ?: 0L,
            processingErrorMessage = errorMessage,
        )

    private fun mapProcessingStatus(
        bookId: String,
        language: String,
        nlpProvider: String,
        udpipeVersion: String,
        modelId: String,
        modelVersion: String,
        indexVersion: Long,
        pipelineFingerprint: String,
        state: String,
        tokenCount: Long,
        uniqueLemmaCount: Long,
        savedIndexSizeBytes: Long,
        processedAtMillis: Long?,
        errorMessage: String?,
    ): BookProcessingStatus =
        BookProcessingStatus(
            bookId = bookId,
            language = language,
            nlpProvider = nlpProvider,
            udpipeVersion = udpipeVersion,
            modelId = modelId,
            modelVersion = modelVersion,
            indexVersion = indexVersion,
            pipelineFingerprint = pipelineFingerprint,
            state = state.toProcessingState(),
            tokenCount = tokenCount,
            uniqueLemmaCount = uniqueLemmaCount,
            savedIndexSizeBytes = savedIndexSizeBytes,
            processedAtMillis = processedAtMillis,
            errorMessage = errorMessage,
        )
}

private fun String?.toProcessingState(): BookProcessingState =
    this
        ?.let { value -> BookProcessingState.entries.firstOrNull { it.name == value } }
        ?: BookProcessingState.NotStarted
