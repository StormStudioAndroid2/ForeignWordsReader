package com.example.myapplication.shared.processing

import com.example.myapplication.shared.main.BookItem

class BookAnalysisProcessor(
    private val store: BookProcessingStore,
    modelRepository: ModelRepository,
    analysisProvider: TextAnalysisProvider,
    private val clockMillis: () -> Long,
    globalFrequencyRepository: GlobalFrequencyRepository = EmptyGlobalFrequencyRepository,
    indexBuilder: BookIndexBuilder = BookIndexBuilder(
        globalFrequencyRepository = globalFrequencyRepository,
    ),
    private val pipeline: BookPreprocessingPipeline = BookPreprocessingPipeline.default(
        store = store,
        modelRepository = modelRepository,
        analysisProvider = analysisProvider,
        clockMillis = clockMillis,
        indexBuilder = indexBuilder,
    ),
) {
    fun processBook(
        book: BookItem,
        sections: List<TextSection>,
        force: Boolean = false,
    ): BookProcessingStatus {
        val pipelineFingerprint = pipeline.fingerprint
        val readableSections = sections
            .mapNotNull { section ->
                val text = section.text.trim()
                if (text.isBlank()) null else section.copy(text = text)
            }

        if (readableSections.isEmpty()) {
            return fail(
                bookId = book.id,
                pipelineFingerprint = pipelineFingerprint,
                message = "No readable text was extracted from this EPUB.",
            )
        }

        val language = SimpleLanguageDetector.detect(readableSections)
        if (language != DefaultAnalysisLanguage) {
            return fail(
                bookId = book.id,
                language = language,
                pipelineFingerprint = pipelineFingerprint,
                message = "No installed analysis model is available for language '$language'.",
            )
        }

        if (
            !force &&
            store.hasCurrentBookIndex(
                bookId = book.id,
                language = language,
                pipelineFingerprint = pipelineFingerprint,
            )
        ) {
            return store.getProcessingStatus(bookId = book.id, language = language)
                ?: BookProcessingStatus(
                    bookId = book.id,
                    language = language,
                    pipelineFingerprint = pipelineFingerprint,
                    state = BookProcessingState.Completed,
                )
        }

        val startedStatus = BookProcessingStatus(
            bookId = book.id,
            language = language,
            pipelineFingerprint = pipelineFingerprint,
            state = BookProcessingState.Processing,
        )
        store.upsertProcessingStatus(startedStatus)

        return try {
            val startedAt = clockMillis()
            val result = pipeline.run(
                BookPreprocessingContext(
                    book = book,
                    language = language,
                    sections = readableSections,
                    pipelineFingerprint = pipelineFingerprint,
                    startedAtMillis = startedAt,
                ),
            )
            val status = result.status ?: error("Preprocessing pipeline completed without a final status.")
            val elapsedMillis = clockMillis() - startedAt

            println(
                "Book analysis completed bookId=${book.id} elapsedMs=$elapsedMillis " +
                    "tokens=${status.tokenCount} uniqueLemmas=${status.uniqueLemmaCount} " +
                    "indexBytes=${status.savedIndexSizeBytes}",
            )
            status
        } catch (error: Throwable) {
            fail(
                bookId = book.id,
                language = language,
                pipelineFingerprint = pipelineFingerprint,
                message = error.message ?: "Could not process this book.",
            )
        }
    }

    private fun fail(
        bookId: String,
        language: String = DefaultAnalysisLanguage,
        pipelineFingerprint: String,
        message: String,
    ): BookProcessingStatus {
        val failedStatus = BookProcessingStatus(
            bookId = bookId,
            language = language,
            pipelineFingerprint = pipelineFingerprint,
            state = BookProcessingState.Failed,
            errorMessage = message,
        )
        store.upsertProcessingStatus(failedStatus)
        return failedStatus
    }
}
