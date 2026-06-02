package com.example.myapplication.shared.processing

const val DefaultAnalysisLanguage: String = "en"
const val DefaultAnalysisProvider: String = "udpipe"
const val DefaultAnalysisModelId: String = "english-ewt"
const val DefaultAnalysisModelVersion: String = "ud-2.5-191206"
const val DefaultAnalysisIndexVersion: Long = 3L
const val DefaultBookIndexChunkSize: Int = 800
const val DefaultBookPreprocessingPipelineFingerprint: String =
    "udpipe-analysis@1|build-lemma-candidates@1|filter-lemma-candidates@1|score-lemma-index@1|persist-book-index@1"

enum class BookProcessingState {
    NotStarted,
    Processing,
    Completed,
    Failed,
}

data class BookProcessingStatus(
    val bookId: String,
    val language: String = DefaultAnalysisLanguage,
    val nlpProvider: String = DefaultAnalysisProvider,
    val udpipeVersion: String = "",
    val modelId: String = DefaultAnalysisModelId,
    val modelVersion: String = DefaultAnalysisModelVersion,
    val indexVersion: Long = DefaultAnalysisIndexVersion,
    val pipelineFingerprint: String = DefaultBookPreprocessingPipelineFingerprint,
    val state: BookProcessingState = BookProcessingState.NotStarted,
    val tokenCount: Long = 0L,
    val uniqueLemmaCount: Long = 0L,
    val savedIndexSizeBytes: Long = 0L,
    val processedAtMillis: Long? = null,
    val errorMessage: String? = null,
)

data class TextSection(
    val sectionId: String,
    val text: String,
)

data class AnalysisModelInfo(
    val language: String,
    val modelId: String,
    val modelVersion: String,
    val path: String,
)

data class TextAnalysisRequest(
    val language: String,
    val model: AnalysisModelInfo,
    val sections: List<TextSection>,
)

data class TextAnalysisMetadata(
    val language: String,
    val nlpProvider: String,
    val providerVersion: String,
    val modelId: String,
    val modelVersion: String,
    val indexVersion: Long,
)

sealed interface TextAnalysisResult {
    data class Success(
        val metadata: TextAnalysisMetadata,
        val tokens: List<AnalyzedToken>,
    ) : TextAnalysisResult

    data class Failure(
        val message: String,
    ) : TextAnalysisResult
}

interface TextAnalysisProvider {
    fun analyze(request: TextAnalysisRequest): TextAnalysisResult
}

interface ModelRepository {
    fun isModelAvailable(language: String): Boolean
    fun getModelPath(language: String): String?
    fun installModel(language: String): AnalysisModelInfo
    fun deleteModel(language: String)
}

enum class TokenType {
    Word,
    Punctuation,
    Symbol,
    Other,
}

data class AnalyzedToken(
    val sectionId: String,
    val tokenOrder: Long,
    val surface: String,
    val lemma: String,
    val upos: String,
    val tokenType: TokenType,
)

data class BookAnalysisResult(
    val bookId: String,
    val metadata: TextAnalysisMetadata,
    val tokens: List<AnalyzedToken>,
)

data class BookIndexMetadata(
    val bookId: String,
    val language: String,
    val nlpProvider: String,
    val udpipeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val indexVersion: Long,
    val tokenCount: Long,
    val uniqueLemmaCount: Long,
    val savedIndexSizeBytes: Long = 0L,
    val processedAtMillis: Long? = null,
)

data class BookLemmaCandidateMetadata(
    val bookId: String,
    val language: String,
    val nlpProvider: String,
    val udpipeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val indexVersion: Long,
    val tokenCount: Long,
    val processedAtMillis: Long? = null,
)

data class BookLemmaCandidate(
    val bookId: String,
    val lemma: String,
    val totalCount: Long,
    val globalFrequencyZipf: Double?,
    val uposCounts: Map<String, Long>,
    val dominantUpos: String,
    val propnRatio: Double,
)

data class BookLemmaCandidateIndex(
    val metadata: BookLemmaCandidateMetadata,
    val lemmaCandidates: List<BookLemmaCandidate>,
    val chunkLemmaCounts: List<BookChunkLemmaCount>,
)

data class FilteredBookLemmaCandidates(
    val metadata: BookLemmaCandidateMetadata,
    val lemmaCandidates: List<BookLemmaCandidate>,
    val chunkLemmaCounts: List<BookChunkLemmaCount>,
)

data class BookLemmaCount(
    val bookId: String,
    val lemma: String,
    val totalCount: Long,
    val globalFrequencyZipf: Double? = null,
    val tfIdfScore: Double = totalCount.toDouble(),
)

data class BookChunkLemmaCount(
    val bookId: String,
    val chunkId: Long,
    val lemma: String,
    val localCount: Long,
)

data class BookIndex(
    val metadata: BookIndexMetadata,
    val lemmaCounts: List<BookLemmaCount>,
    val chunkLemmaCounts: List<BookChunkLemmaCount>,
)

interface BookProcessingStore {
    fun getProcessingStatus(bookId: String, language: String): BookProcessingStatus?

    fun hasCurrentBookIndex(
        bookId: String,
        language: String,
        nlpProvider: String = DefaultAnalysisProvider,
        modelVersion: String = DefaultAnalysisModelVersion,
        indexVersion: Long = DefaultAnalysisIndexVersion,
        pipelineFingerprint: String = DefaultBookPreprocessingPipelineFingerprint,
    ): Boolean

    fun upsertProcessingStatus(status: BookProcessingStatus)

    fun replaceBookIndex(
        status: BookProcessingStatus,
        index: BookIndex,
    )
}
