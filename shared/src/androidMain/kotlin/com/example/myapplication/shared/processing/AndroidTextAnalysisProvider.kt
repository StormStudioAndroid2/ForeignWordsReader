package com.example.myapplication.shared.processing

import android.content.Context
import java.io.File

class AndroidModelRepository(
    context: Context,
) : ModelRepository {
    private val appContext = context.applicationContext

    override fun isModelAvailable(language: String): Boolean =
        modelFile(language).exists()

    override fun getModelPath(language: String): String? =
        modelFile(language).takeIf { it.exists() && it.length() > 0L }?.absolutePath

    override fun installModel(language: String): AnalysisModelInfo {
        check(language == DefaultAnalysisLanguage) {
            "Only the English UDPipe model is bundled in this version."
        }

        val target = modelFile(language)
        if (!target.exists() || target.length() == 0L) {
            target.parentFile?.mkdirs()
            appContext.assets.open("udpipe/$DefaultAnalysisModelId.udpipe").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return AnalysisModelInfo(
            language = language,
            modelId = DefaultAnalysisModelId,
            modelVersion = DefaultAnalysisModelVersion,
            path = target.absolutePath,
        )
    }

    override fun deleteModel(language: String) {
        modelFile(language).delete()
    }

    private fun modelFile(language: String): File =
        File(appContext.filesDir, "udpipe-models/${modelId(language)}.udpipe")

    private fun modelId(language: String): String =
        when (language) {
            DefaultAnalysisLanguage -> DefaultAnalysisModelId
            else -> "unsupported-$language"
        }
}

class AndroidTextAnalysisProvider(
    private val modelRepository: ModelRepository,
    private val parser: ConlluParser = ConlluParser(),
) : TextAnalysisProvider {
    private val engines = mutableMapOf<String, Long>()

    override fun analyze(request: TextAnalysisRequest): TextAnalysisResult =
        synchronized(this) {
            runCatching {
                val modelPath = request.model.path.ifBlank {
                    modelRepository.getModelPath(request.language)
                        ?: modelRepository.installModel(request.language).path
                }
                val engine = engines.getOrPut(modelPath) {
                    AndroidUdpipeNative.createEngine().also { handle ->
                        AndroidUdpipeNative.loadModel(handle, modelPath)
                    }
                }

                val tokens = request.sections.flatMap { section ->
                    val conllu = AndroidUdpipeNative.analyzeUtf8(engine, section.text)
                    parser.parse(sectionId = section.sectionId, conllu = conllu)
                }

                TextAnalysisResult.Success(
                    metadata = TextAnalysisMetadata(
                        language = request.language,
                        nlpProvider = DefaultAnalysisProvider,
                        providerVersion = AndroidUdpipeNative.version(),
                        modelId = request.model.modelId,
                        modelVersion = request.model.modelVersion,
                        indexVersion = DefaultAnalysisIndexVersion,
                    ),
                    tokens = tokens,
                )
            }.getOrElse { error ->
                TextAnalysisResult.Failure(error.message ?: "UDPipe processing failed.")
            }
        }

    fun close() {
        synchronized(this) {
            engines.values.forEach(AndroidUdpipeNative::destroyEngine)
            engines.clear()
        }
    }
}

private object AndroidUdpipeNative {
    init {
        System.loadLibrary("foreignwords_udpipe")
    }

    external fun createEngine(): Long
    external fun loadModel(handle: Long, modelPath: String)
    external fun analyzeUtf8(handle: Long, text: String): String
    external fun version(): String
    external fun destroyEngine(handle: Long)
}
