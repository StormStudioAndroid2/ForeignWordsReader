package com.example.myapplication.shared.processing

import com.example.myapplication.shared.main.BookItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class BookPreprocessingPipelineTest {
    @Test
    fun fingerprintUsesOrderedStageIdsAndVersions() {
        val pipeline = BookPreprocessingPipeline(
            stages = listOf(
                NoopStage(stageId = "first-stage", version = 1),
                NoopStage(stageId = "second-stage", version = 2),
            ),
        )

        assertEquals("first-stage@1|second-stage@2", pipeline.fingerprint)
    }

    @Test
    fun runsStagesInOrder() {
        val calls = mutableListOf<String>()
        val pipeline = BookPreprocessingPipeline(
            stages = listOf(
                RecordingStage(stageId = "first-stage", calls = calls),
                RecordingStage(stageId = "second-stage", calls = calls),
                RecordingStage(stageId = "third-stage", calls = calls),
            ),
        )

        pipeline.run(testContext(pipelineFingerprint = pipeline.fingerprint))

        assertEquals(listOf("first-stage", "second-stage", "third-stage"), calls)
    }

    @Test
    fun rejectsDuplicateStageIds() {
        assertFailsWith<IllegalArgumentException> {
            BookPreprocessingPipeline(
                stages = listOf(
                    NoopStage(stageId = "same-stage", version = 1),
                    NoopStage(stageId = "same-stage", version = 2),
                ),
            )
        }
    }

    @Test
    fun defaultFingerprintMatchesVersionedConstant() {
        val pipeline = BookPreprocessingPipeline.default(
            store = FakeProcessingStore(),
            modelRepository = FakeModelRepository(),
            analysisProvider = FakeTextAnalysisProvider(),
            clockMillis = { 1_000L },
            indexBuilder = BookIndexBuilder(),
        )

        assertEquals(DefaultBookPreprocessingPipelineFingerprint, pipeline.fingerprint)
    }
}

class BookPreprocessingStageTest {
    @Test
    fun udpipeAnalysisStageInstallsModelAndStoresAnalysis() {
        val modelRepository = FakeModelRepository(isAvailable = false)
        val analysisProvider = FakeTextAnalysisProvider()
        val stage = UdpipeAnalysisStage(
            modelRepository = modelRepository,
            analysisProvider = analysisProvider,
        )

        val result = stage.process(testContext())

        val analysis = assertNotNull(result.analysis)
        assertEquals("installed-model-path", analysisProvider.lastRequest?.model?.path)
        assertEquals(1, analysis.tokens.size)
    }

    @Test
    fun buildLemmaIndexStageBuildsIndexAndTimestamp() {
        val stage = BuildLemmaIndexStage(
            indexBuilder = BookIndexBuilder(),
            clockMillis = { 2_000L },
        )

        val result = stage.process(
            testContext(
                analysis = TextAnalysisResult.Success(
                    metadata = testMetadata(),
                    tokens = listOf(
                        testToken(lemma = "read"),
                        testToken(lemma = "read"),
                        testToken(lemma = ".", upos = "PUNCT", tokenType = TokenType.Punctuation),
                    ),
                ),
            ),
        )

        assertEquals(2_000L, result.processedAtMillis)
        assertEquals(2L, result.index?.metadata?.tokenCount)
        assertEquals(1L, result.index?.metadata?.uniqueLemmaCount)
    }

    @Test
    fun persistBookIndexStageStoresCompletedStatusWithPipelineFingerprint() {
        val store = FakeProcessingStore()
        val index = BookIndexBuilder().build(
            bookId = TestBook.id,
            metadata = testMetadata(),
            tokens = listOf(testToken(lemma = "book")),
            processedAtMillis = 3_000L,
        )
        val stage = PersistBookIndexStage(store = store)

        val result = stage.process(
            testContext(
                pipelineFingerprint = "custom-stage@1",
                processedAtMillis = 3_000L,
                index = index,
            ),
        )

        assertEquals(BookProcessingState.Completed, result.status?.state)
        assertEquals("custom-stage@1", store.replacedStatus?.pipelineFingerprint)
        assertEquals(index, store.replacedIndex)
    }
}

private class NoopStage(
    override val stageId: String,
    override val version: Long,
) : BookPreprocessingStage {
    override fun process(context: BookPreprocessingContext): BookPreprocessingContext = context
}

private class RecordingStage(
    override val stageId: String,
    private val calls: MutableList<String>,
) : BookPreprocessingStage {
    override val version: Long = 1L

    override fun process(context: BookPreprocessingContext): BookPreprocessingContext {
        calls += stageId
        return context
    }
}

internal val TestBook = BookItem(
    id = "book-1",
    uriString = "file:///book.epub",
    title = "Test Book",
    author = "Test Author",
    coverUriString = null,
    lastOpenedAtMillis = 1L,
)

internal fun testContext(
    pipelineFingerprint: String = DefaultBookPreprocessingPipelineFingerprint,
    analysis: TextAnalysisResult.Success? = null,
    processedAtMillis: Long? = null,
    index: BookIndex? = null,
): BookPreprocessingContext =
    BookPreprocessingContext(
        book = TestBook,
        language = DefaultAnalysisLanguage,
        sections = listOf(TextSection(sectionId = "section-1", text = "Readers read books.")),
        pipelineFingerprint = pipelineFingerprint,
        startedAtMillis = 1_000L,
        processedAtMillis = processedAtMillis,
        analysis = analysis,
        index = index,
    )

internal fun testMetadata(): TextAnalysisMetadata =
    TextAnalysisMetadata(
        language = DefaultAnalysisLanguage,
        nlpProvider = DefaultAnalysisProvider,
        providerVersion = "1.3.1",
        modelId = DefaultAnalysisModelId,
        modelVersion = DefaultAnalysisModelVersion,
        indexVersion = DefaultAnalysisIndexVersion,
    )

internal fun testToken(
    lemma: String,
    upos: String = "NOUN",
    tokenType: TokenType = TokenType.Word,
): AnalyzedToken =
    AnalyzedToken(
        sectionId = "section-1",
        tokenOrder = 0,
        surface = lemma,
        lemma = lemma,
        upos = upos,
        tokenType = tokenType,
    )

internal class FakeModelRepository(
    private val isAvailable: Boolean = true,
) : ModelRepository {
    override fun isModelAvailable(language: String): Boolean = isAvailable

    override fun getModelPath(language: String): String? =
        if (isAvailable) "existing-model-path" else null

    override fun installModel(language: String): AnalysisModelInfo =
        AnalysisModelInfo(
            language = language,
            modelId = DefaultAnalysisModelId,
            modelVersion = DefaultAnalysisModelVersion,
            path = "installed-model-path",
        )

    override fun deleteModel(language: String) = Unit
}

internal class FakeTextAnalysisProvider(
    private val result: TextAnalysisResult = TextAnalysisResult.Success(
        metadata = testMetadata(),
        tokens = listOf(testToken(lemma = "read")),
    ),
) : TextAnalysisProvider {
    var lastRequest: TextAnalysisRequest? = null
        private set

    override fun analyze(request: TextAnalysisRequest): TextAnalysisResult {
        lastRequest = request
        return result
    }
}

internal class FakeProcessingStore : BookProcessingStore {
    var currentPipelineFingerprint: String? = null
    var processingStatus: BookProcessingStatus? = null
    val upsertedStatuses = mutableListOf<BookProcessingStatus>()
    var replacedStatus: BookProcessingStatus? = null
    var replacedIndex: BookIndex? = null

    override fun getProcessingStatus(bookId: String, language: String): BookProcessingStatus? =
        processingStatus

    override fun hasCurrentBookIndex(
        bookId: String,
        language: String,
        nlpProvider: String,
        modelVersion: String,
        indexVersion: Long,
        pipelineFingerprint: String,
    ): Boolean =
        currentPipelineFingerprint == pipelineFingerprint

    override fun upsertProcessingStatus(status: BookProcessingStatus) {
        upsertedStatuses += status
        processingStatus = status
    }

    override fun replaceBookIndex(
        status: BookProcessingStatus,
        index: BookIndex,
    ) {
        replacedStatus = status
        replacedIndex = index
        processingStatus = status
    }
}
