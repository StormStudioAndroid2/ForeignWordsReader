package com.example.myapplication.shared.processing

import com.example.myapplication.shared.data.BookLibraryStore
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_analyze_utf8
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_create_engine
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_destroy_engine
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_free_string
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_load_model
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_result_free
import com.example.myapplication.shared.udpipe.cinterop.udpipe_adapter_version
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
class IosModelRepository : ModelRepository {
    override fun isModelAvailable(language: String): Boolean =
        getModelPath(language)?.let { path -> NSFileManager.defaultManager.fileExistsAtPath(path) } == true

    override fun getModelPath(language: String): String? {
        if (language != DefaultAnalysisLanguage) {
            return null
        }

        return installedModelPath()
            .takeIf { path -> NSFileManager.defaultManager.fileExistsAtPath(path) }
    }

    override fun installModel(language: String): AnalysisModelInfo {
        check(language == DefaultAnalysisLanguage) {
            "Only the English UDPipe model is bundled in this version."
        }

        val targetPath = installedModelPath()
        if (!NSFileManager.defaultManager.fileExistsAtPath(targetPath)) {
            val sourcePath = bundledModelPath() ?: error("Bundled English UDPipe model was not found.")
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = modelDirectory(),
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            val copied = NSFileManager.defaultManager.copyItemAtPath(
                srcPath = sourcePath,
                toPath = targetPath,
                error = null,
            )
            check(copied) { "Could not install bundled English UDPipe model." }
        }

        return AnalysisModelInfo(
            language = language,
            modelId = DefaultAnalysisModelId,
            modelVersion = DefaultAnalysisModelVersion,
            path = targetPath,
        )
    }

    override fun deleteModel(language: String) {
        if (language == DefaultAnalysisLanguage) {
            getModelPath(language)?.let { path ->
                NSFileManager.defaultManager.removeItemAtPath(path, null)
            }
        }
    }

    private fun bundledModelPath(): String? =
        NSBundle.mainBundle.pathForResource(
            name = DefaultAnalysisModelId,
            ofType = "udpipe",
            inDirectory = "udpipe",
        ) ?: NSBundle.mainBundle.pathForResource(
            name = DefaultAnalysisModelId,
            ofType = "udpipe",
        )

    private fun installedModelPath(): String =
        "${modelDirectory()}/$DefaultAnalysisModelId.udpipe"

    private fun modelDirectory(): String {
        val baseDirectory = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("Application Support directory is unavailable.")
        return "$baseDirectory/udpipe-models"
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosTextAnalysisProvider(
    private val modelRepository: ModelRepository,
    private val parser: ConlluParser = ConlluParser(),
) : TextAnalysisProvider {
    private val engines = mutableMapOf<String, CPointer<cnames.structs.udpipe_adapter_engine>>()

    override fun analyze(request: TextAnalysisRequest): TextAnalysisResult =
        runCatching {
            val modelPath = request.model.path.ifBlank {
                modelRepository.getModelPath(request.language)
                    ?: modelRepository.installModel(request.language).path
            }
            val engine = engines.getOrPut(modelPath) {
                val created = udpipe_adapter_create_engine()
                    ?: error("Could not create UDPipe engine.")
                val loadError = udpipe_adapter_load_model(created, modelPath)
                if (loadError != null) {
                    try {
                        error(loadError.toKString())
                    } finally {
                        udpipe_adapter_free_string(loadError)
                    }
                }
                created
            }

            val tokens = request.sections.flatMap { section ->
                val result = udpipe_adapter_analyze_utf8(engine, section.text)
                    ?: error("UDPipe processing failed.")
                try {
                    result.pointed.error?.toKString()?.let(::error)
                    parser.parse(
                        sectionId = section.sectionId,
                        conllu = result.pointed.output?.toKString().orEmpty(),
                    )
                } finally {
                    udpipe_adapter_result_free(result)
                }
            }

            TextAnalysisResult.Success(
                metadata = TextAnalysisMetadata(
                    language = request.language,
                    nlpProvider = DefaultAnalysisProvider,
                    providerVersion = udpipe_adapter_version()?.toKString().orEmpty(),
                    modelId = request.model.modelId,
                    modelVersion = request.model.modelVersion,
                    indexVersion = DefaultAnalysisIndexVersion,
                ),
                tokens = tokens,
            )
        }.getOrElse { error ->
            TextAnalysisResult.Failure(error.message ?: "UDPipe processing failed.")
        }

    fun close() {
        engines.values.forEach { engine -> udpipe_adapter_destroy_engine(engine) }
        engines.clear()
    }
}

class IosBookProcessingRunner(
    private val store: BookLibraryStore,
) {
    fun process(book: BookItem, sections: List<TextSection>): BookProcessingStatus =
        runCatching {
            val modelRepository = IosModelRepository()
            BookAnalysisProcessor(
                store = store,
                modelRepository = modelRepository,
                analysisProvider = IosTextAnalysisProvider(modelRepository = modelRepository),
                clockMillis = ::currentTimeMillis,
                globalFrequencyRepository = IosGlobalFrequencyRepositoryFactory().create(),
            ).processBook(
                book = book,
                sections = sections,
            )
        }.getOrElse { error ->
            BookProcessingStatus(
                bookId = book.id,
                state = BookProcessingState.Failed,
                errorMessage = error.message ?: "Could not process this book.",
            ).also { status ->
                runCatching { store.upsertProcessingStatus(status) }
            }
        }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long =
    memScoped {
        val value = alloc<timeval>()
        gettimeofday(value.ptr, null)
        value.tv_sec * 1000L + value.tv_usec / 1000L
    }
