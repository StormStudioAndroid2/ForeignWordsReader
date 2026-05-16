package com.example.myapplication.shared.processing

import kotlin.test.Test
import kotlin.test.assertEquals

class BookAnalysisProcessorTest {
    @Test
    fun skipsProcessingWhenCurrentPipelineFingerprintExists() {
        val store = FakeProcessingStore()
        val pipeline = BookPreprocessingPipeline(
            stages = listOf(FailingStage(message = "Pipeline should not run.")),
        )
        val completedStatus = BookProcessingStatus(
            bookId = TestBook.id,
            language = DefaultAnalysisLanguage,
            pipelineFingerprint = pipeline.fingerprint,
            state = BookProcessingState.Completed,
            tokenCount = 12L,
        )
        store.currentPipelineFingerprint = pipeline.fingerprint
        store.processingStatus = completedStatus

        val status = processor(
            store = store,
            pipeline = pipeline,
        ).processBook(
            book = TestBook,
            sections = listOf(TextSection(sectionId = "section-1", text = "Already processed.")),
        )

        assertEquals(completedStatus, status)
        assertEquals(emptyList(), store.upsertedStatuses)
    }

    @Test
    fun runsDefaultPipelineAndPersistsCompletedStatus() {
        val store = FakeProcessingStore()

        val status = processor(store = store).processBook(
            book = TestBook,
            sections = listOf(TextSection(sectionId = "section-1", text = "Readers read books.")),
        )

        assertEquals(BookProcessingState.Completed, status.state)
        assertEquals(DefaultBookPreprocessingPipelineFingerprint, status.pipelineFingerprint)
        assertEquals(1L, status.uniqueLemmaCount)
        assertEquals(status, store.replacedStatus)
        assertEquals(1L, store.replacedIndex?.lemmaCounts?.firstOrNull()?.totalCount)
    }

    @Test
    fun stageFailureWritesFailedStatus() {
        val store = FakeProcessingStore()
        val pipeline = BookPreprocessingPipeline(
            stages = listOf(FailingStage(message = "Synthetic stage failure.")),
        )

        val status = processor(
            store = store,
            pipeline = pipeline,
        ).processBook(
            book = TestBook,
            sections = listOf(TextSection(sectionId = "section-1", text = "This will fail.")),
        )

        assertEquals(BookProcessingState.Failed, status.state)
        assertEquals("Synthetic stage failure.", status.errorMessage)
        assertEquals(pipeline.fingerprint, status.pipelineFingerprint)
        assertEquals(BookProcessingState.Processing, store.upsertedStatuses.first().state)
        assertEquals(BookProcessingState.Failed, store.upsertedStatuses.last().state)
    }

    private fun processor(
        store: FakeProcessingStore,
        pipeline: BookPreprocessingPipeline = BookPreprocessingPipeline.default(
            store = store,
            modelRepository = FakeModelRepository(),
            analysisProvider = FakeTextAnalysisProvider(),
            clockMillis = { 2_000L },
            indexBuilder = BookIndexBuilder(),
        ),
    ): BookAnalysisProcessor =
        BookAnalysisProcessor(
            store = store,
            modelRepository = FakeModelRepository(),
            analysisProvider = FakeTextAnalysisProvider(),
            clockMillis = { 2_000L },
            pipeline = pipeline,
        )

    private class FailingStage(
        private val message: String,
    ) : BookPreprocessingStage {
        override val stageId: String = "failing-stage"
        override val version: Long = 1L

        override fun process(context: BookPreprocessingContext): BookPreprocessingContext =
            error(message)
    }
}
