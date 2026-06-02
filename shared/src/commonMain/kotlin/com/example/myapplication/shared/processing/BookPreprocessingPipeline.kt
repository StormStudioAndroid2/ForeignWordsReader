package com.example.myapplication.shared.processing

import com.example.myapplication.shared.main.BookItem

interface BookPreprocessingStage {
    val stageId: String
    val version: Long

    fun process(context: BookPreprocessingContext): BookPreprocessingContext
}

data class BookPreprocessingContext(
    val book: BookItem,
    val language: String,
    val sections: List<TextSection>,
    val pipelineFingerprint: String,
    val startedAtMillis: Long,
    val processedAtMillis: Long? = null,
    val analysis: TextAnalysisResult.Success? = null,
    val lemmaCandidates: BookLemmaCandidateIndex? = null,
    val filteredLemmaCandidates: FilteredBookLemmaCandidates? = null,
    val index: BookIndex? = null,
    val status: BookProcessingStatus? = null,
)

private val StageIdRegex = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

class BookPreprocessingPipeline(
    val stages: List<BookPreprocessingStage>,
) {
    val fingerprint: String = stages.joinToString(separator = "|") { stage ->
        "${stage.stageId}@${stage.version}"
    }

    init {
        require(stages.isNotEmpty()) { "Preprocessing pipeline must contain at least one stage." }
        stages.forEach { stage ->
            require(stage.stageId.isNotBlank()) { "Preprocessing stage id must not be blank." }
            require(StageIdRegex.matches(stage.stageId)) {
                "Preprocessing stage id '${stage.stageId}' must use lowercase letters, digits, and hyphens."
            }
            require(stage.version > 0L) { "Preprocessing stage '${stage.stageId}' version must be positive." }
        }
        require(stages.map { it.stageId }.distinct().size == stages.size) {
            "Preprocessing stage ids must be unique."
        }
    }

    fun run(context: BookPreprocessingContext): BookPreprocessingContext =
        stages.fold(context) { current, stage -> stage.process(current) }

    companion object {
        fun default(
            store: BookProcessingStore,
            modelRepository: ModelRepository,
            analysisProvider: TextAnalysisProvider,
            clockMillis: () -> Long,
            indexBuilder: BookIndexBuilder,
        ): BookPreprocessingPipeline =
            BookPreprocessingPipeline(
                stages = listOf(
                    UdpipeAnalysisStage(
                        modelRepository = modelRepository,
                        analysisProvider = analysisProvider,
                    ),
                    BuildLemmaCandidatesStage(
                        indexBuilder = indexBuilder,
                        clockMillis = clockMillis,
                    ),
                    FilterLemmaCandidatesStage(indexBuilder = indexBuilder),
                    ScoreLemmaIndexStage(indexBuilder = indexBuilder),
                    PersistBookIndexStage(store = store),
                ),
            )
    }
}

class UdpipeAnalysisStage(
    private val modelRepository: ModelRepository,
    private val analysisProvider: TextAnalysisProvider,
) : BookPreprocessingStage {
    override val stageId: String = "udpipe-analysis"
    override val version: Long = 1L

    override fun process(context: BookPreprocessingContext): BookPreprocessingContext {
        val model = if (modelRepository.isModelAvailable(context.language)) {
            AnalysisModelInfo(
                language = context.language,
                modelId = DefaultAnalysisModelId,
                modelVersion = DefaultAnalysisModelVersion,
                path = modelRepository.getModelPath(context.language)
                    ?: error("Installed model path is not available for language '${context.language}'."),
            )
        } else {
            modelRepository.installModel(context.language)
        }

        val analysis = analysisProvider.analyze(
            TextAnalysisRequest(
                language = context.language,
                model = model,
                sections = context.sections,
            ),
        )

        return when (analysis) {
            is TextAnalysisResult.Failure -> error(analysis.message)
            is TextAnalysisResult.Success -> context.copy(analysis = analysis)
        }
    }
}

class BuildLemmaCandidatesStage(
    private val indexBuilder: BookIndexBuilder,
    private val clockMillis: () -> Long,
) : BookPreprocessingStage {
    override val stageId: String = "build-lemma-candidates"
    override val version: Long = 1L

    override fun process(context: BookPreprocessingContext): BookPreprocessingContext {
        val analysis = context.analysis ?: error("Text analysis must complete before building lemma candidates.")
        val processedAtMillis = clockMillis()
        return context.copy(
            processedAtMillis = processedAtMillis,
            lemmaCandidates = indexBuilder.buildCandidates(
                bookId = context.book.id,
                metadata = analysis.metadata,
                tokens = analysis.tokens,
                processedAtMillis = processedAtMillis,
            ),
        )
    }
}

class FilterLemmaCandidatesStage(
    private val indexBuilder: BookIndexBuilder,
) : BookPreprocessingStage {
    override val stageId: String = "filter-lemma-candidates"
    override val version: Long = 1L

    override fun process(context: BookPreprocessingContext): BookPreprocessingContext {
        val candidates = context.lemmaCandidates ?: error("Lemma candidates must be built before filtering.")
        return context.copy(filteredLemmaCandidates = indexBuilder.filterCandidates(candidates))
    }
}

class ScoreLemmaIndexStage(
    private val indexBuilder: BookIndexBuilder,
) : BookPreprocessingStage {
    override val stageId: String = "score-lemma-index"
    override val version: Long = 1L

    override fun process(context: BookPreprocessingContext): BookPreprocessingContext {
        val candidates = context.filteredLemmaCandidates ?: error("Lemma candidates must be filtered before scoring.")
        return context.copy(index = indexBuilder.score(candidates))
    }
}

class PersistBookIndexStage(
    private val store: BookProcessingStore,
) : BookPreprocessingStage {
    override val stageId: String = "persist-book-index"
    override val version: Long = 1L

    override fun process(context: BookPreprocessingContext): BookPreprocessingContext {
        val index = context.index ?: error("Lemma index must be built before persistence.")
        val processedAtMillis = context.processedAtMillis
            ?: error("Processed timestamp must be set before persistence.")
        val status = BookProcessingStatus(
            bookId = context.book.id,
            language = index.metadata.language,
            nlpProvider = index.metadata.nlpProvider,
            udpipeVersion = index.metadata.udpipeVersion,
            modelId = index.metadata.modelId,
            modelVersion = index.metadata.modelVersion,
            indexVersion = index.metadata.indexVersion,
            pipelineFingerprint = context.pipelineFingerprint,
            state = BookProcessingState.Completed,
            tokenCount = index.metadata.tokenCount,
            uniqueLemmaCount = index.metadata.uniqueLemmaCount,
            savedIndexSizeBytes = index.metadata.savedIndexSizeBytes,
            processedAtMillis = processedAtMillis,
        )

        store.replaceBookIndex(status = status, index = index)
        return context.copy(status = status)
    }
}
