package com.example.myapplication.android.reader

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Html
import android.util.Size
import androidx.core.net.toUri
import com.example.myapplication.shared.data.AndroidBookLibraryStoreFactory
import com.example.myapplication.shared.data.BookLibraryStore
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.main.BookLibraryGateway
import com.example.myapplication.shared.processing.AndroidModelRepository
import com.example.myapplication.shared.processing.AndroidGlobalFrequencyRepositoryFactory
import com.example.myapplication.shared.processing.AndroidTextAnalysisProvider
import com.example.myapplication.shared.processing.BookAnalysisProcessor
import com.example.myapplication.shared.processing.BookLemmaCount
import com.example.myapplication.shared.processing.BookProcessingState
import com.example.myapplication.shared.processing.BookProcessingStatus
import com.example.myapplication.shared.processing.DefaultAnalysisLanguage
import com.example.myapplication.shared.processing.TextSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalReadiumApi::class)
internal class AndroidBookLibraryGateway(
    private val application: Application,
) : BookLibraryGateway, AndroidDebugBookBatchProcessor {

    private val store: BookLibraryStore = AndroidBookLibraryStoreFactory(application).create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
    private val publicationParser = DefaultPublicationParser(
        context = application,
        httpClient = httpClient,
        assetRetriever = assetRetriever,
        pdfFactory = null,
    )
    private val publicationOpener = PublicationOpener(
        publicationParser = publicationParser,
        contentProtections = emptyList(),
    )

    override fun loadBooks(
        onResult: (List<BookItem>) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.getRecentBooks()
                }
            }.onSuccess(onResult)
                .onFailure { error -> onError(error.message ?: "Could not load the book library.") }
        }
    }

    override fun importBook(
        uriString: String,
        onResult: (BookItem) -> Unit,
        onProcessingChanged: (BookItem) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importBook(uriString)
                }
            }.onSuccess { book ->
                onResult(book)
                processBook(
                    uriString = uriString,
                    book = book,
                    onProcessingChanged = onProcessingChanged,
                )
            }
                .onFailure { error -> onError(error.message ?: "Could not import this EPUB.") }
        }
    }

    override fun markBookOpened(
        uriString: String,
        onResult: (BookItem) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val book = store.markBookOpened(
                        uriString = uriString,
                        lastOpenedAtMillis = System.currentTimeMillis(),
                    ) ?: error("This book is no longer in the library.")
                    val shouldProcess = !store.hasCurrentBookIndex(
                        bookId = book.id,
                        language = DefaultAnalysisLanguage,
                    )
                    book to shouldProcess
                }
            }.onSuccess { (book, shouldProcess) ->
                onResult(book)
                if (shouldProcess) {
                    processBook(
                        uriString = uriString,
                        book = book,
                        onProcessingChanged = {},
                    )
                }
            }
                .onFailure { error -> onError(error.message ?: "Could not update the recent books list.") }
        }
    }

    override fun processDebugBookFolder(
        folderUriString: String,
        onProgress: (AndroidDebugBookBatchProgress) -> Unit,
        onComplete: (AndroidDebugBookBatchSummary) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!application.isDebuggable()) {
            onError("Debug batch processing is available only in debug builds.")
            return
        }

        scope.launch {
            val documents = runCatching {
                withContext(Dispatchers.IO) {
                    listEpubDocuments(folderUriString.toUri())
                }
            }.getOrElse { error ->
                onError(error.message ?: "Could not process EPUB folder.")
                return@launch
            }

            if (documents.isEmpty()) {
                onComplete(
                    AndroidDebugBookBatchSummary(
                        total = 0,
                        succeeded = 0,
                        failed = 0,
                        outputDirectory = debugLogDirectory().absolutePath,
                    ),
                )
                return@launch
            }

            var succeeded = 0
            var failed = 0
            documents.forEachIndexed { index, document ->
                onProgress(
                    AndroidDebugBookBatchProgress(
                        processed = index,
                        total = documents.size,
                        succeeded = succeeded,
                        failed = failed,
                        currentFileName = document.displayName,
                    ),
                )

                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val book = importBook(document.uri.toString())
                        processBookNow(
                            uriString = document.uri.toString(),
                            book = book,
                            force = true,
                            debugSourceName = document.displayName,
                        )
                    }.onFailure { error ->
                        exportDebugFailureLog(
                            title = document.displayName.substringBeforeLast('.').normalizedOrNull()
                                ?: document.displayName,
                            sourceUriString = document.uri.toString(),
                            error = error,
                        )
                    }
                }

                if (result.getOrNull()?.status?.state == BookProcessingState.Completed) {
                    succeeded += 1
                } else {
                    failed += 1
                }
            }

            onComplete(
                AndroidDebugBookBatchSummary(
                    total = documents.size,
                    succeeded = succeeded,
                    failed = failed,
                    outputDirectory = debugLogDirectory().absolutePath,
                ),
            )
        }
    }

    private suspend fun importBook(uriString: String): BookItem {
        val uri = uriString.toUri()
        takePersistableReadPermission(uri)

        val absoluteUrl = uri.toAbsoluteUrl()
            ?: error("Could not resolve this EPUB URI.")
        val asset = assetRetriever.retrieve(absoluteUrl).getOrElse { error ->
            throw IllegalStateException("Could not read this EPUB: $error")
        }
        val publication = publicationOpener.open(
            asset = asset,
            allowUserInteraction = true,
        ).getOrElse { error ->
            throw IllegalStateException("Could not parse this EPUB: $error")
        }

        return try {
            val existingBook = store.getBook(uriString)
            val id = stableId(uriString)
            val coverUriString = publication.coverFitting(Size(240, 320))
                ?.let { cover -> saveCover(id, cover) }
                ?: existingBook?.coverUriString
            val book = BookItem(
                id = id,
                uriString = uriString,
                title = publication.metadata.title?.normalizedOrNull()
                    ?: fallbackTitle(uri),
                author = publication.metadata.authors.firstOrNull()?.name?.normalizedOrNull()
                    ?: "Unknown author",
                coverUriString = coverUriString,
                lastOpenedAtMillis = System.currentTimeMillis(),
            )

            store.upsertBook(book)
            val processingStatus = BookProcessingStatus(
                bookId = book.id,
                state = BookProcessingState.Processing,
            )
            store.upsertProcessingStatus(processingStatus)
            store.getBook(uriString) ?: book.copy(processingState = BookProcessingState.Processing)
        } finally {
            publication.close()
        }
    }

    private fun processBook(
        uriString: String,
        book: BookItem,
        onProcessingChanged: (BookItem) -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    processBookNow(uriString = uriString, book = book).book
                }
            }.onSuccess(onProcessingChanged)
                .onFailure { error ->
                    store.upsertProcessingStatus(
                        BookProcessingStatus(
                            bookId = book.id,
                            state = BookProcessingState.Failed,
                            errorMessage = error.message ?: "Could not process this book with UDPipe.",
                        ),
                    )
                    onProcessingChanged(store.getBook(uriString) ?: book.copy(processingState = BookProcessingState.Failed))
                }
        }
    }

    private suspend fun processBookNow(
        uriString: String,
        book: BookItem,
        force: Boolean = false,
        debugSourceName: String? = null,
    ): ProcessedBookResult {
        val startedAtMillis = System.currentTimeMillis()
        val sections = openPublication(uriString).usePublication { publication ->
            publication.content()
                ?.text()
                ?.toTextSections()
                .orEmpty()
        }
        val modelRepository = AndroidModelRepository(application)
        val analysisProvider = AndroidTextAnalysisProvider(modelRepository)
        val globalFrequencyRepository = AndroidGlobalFrequencyRepositoryFactory(application).create()
        val status = try {
            BookAnalysisProcessor(
                store = store,
                modelRepository = modelRepository,
                analysisProvider = analysisProvider,
                clockMillis = System::currentTimeMillis,
                globalFrequencyRepository = globalFrequencyRepository,
            ).processBook(
                book = book,
                sections = sections,
                force = force,
            )
        } finally {
            try {
                analysisProvider.close()
            } finally {
                globalFrequencyRepository.close()
            }
        }
        exportDebugBookLogs(
            book = book,
            status = status,
            startedAtMillis = startedAtMillis,
            sectionCount = sections.size,
            sourceUriString = uriString,
            sourceName = debugSourceName,
        )
        return ProcessedBookResult(
            book = store.getBook(uriString) ?: book,
            status = status,
        )
    }

    private suspend fun openPublication(uriString: String): org.readium.r2.shared.publication.Publication {
        val uri = uriString.toUri()
        takePersistableReadPermission(uri)

        val absoluteUrl = uri.toAbsoluteUrl()
            ?: error("Could not resolve this EPUB URI.")
        val asset = assetRetriever.retrieve(absoluteUrl).getOrElse { error ->
            throw IllegalStateException("Could not read this EPUB: $error")
        }
        return publicationOpener.open(
            asset = asset,
            allowUserInteraction = true,
        ).getOrElse { error ->
            throw IllegalStateException("Could not parse this EPUB: $error")
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        try {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers do not offer persistable permissions. The file can still be opened now.
        }
    }

    private fun takePersistableTreeReadPermission(uri: Uri) {
        try {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Tree access can still be available for the current session.
        }
    }

    private fun listEpubDocuments(folderUri: Uri): List<DebugBookDocument> {
        takePersistableTreeReadPermission(folderUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
        return listEpubDocumentsInTree(treeUri = folderUri, documentId = treeDocumentId)
            .sortedBy { it.displayName.lowercase(Locale.US) }
    }

    private fun listEpubDocumentsInTree(
        treeUri: Uri,
        documentId: String,
    ): List<DebugBookDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        return application.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(displayNameIndex).orEmpty()
                        val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                        if (mimeType == EpubMimeType || displayName.endsWith(".epub", ignoreCase = true)) {
                            add(
                                DebugBookDocument(
                                    displayName = displayName.ifBlank { "Untitled EPUB" },
                                    uri = DocumentsContract.buildDocumentUriUsingTree(
                                        treeUri,
                                        cursor.getString(documentIdIndex),
                                    ),
                                ),
                            )
                        } else if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            addAll(
                                listEpubDocumentsInTree(
                                    treeUri = treeUri,
                                    documentId = cursor.getString(documentIdIndex),
                                ),
                            )
                        }
                    }
                }
            }
            .orEmpty()
    }

    private fun saveCover(bookId: String, bitmap: Bitmap): String {
        val directory = File(application.filesDir, "book-covers").apply { mkdirs() }
        val file = File(directory, "$bookId.png")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return file.absolutePath
    }

    private fun exportDebugBookLogs(
        book: BookItem,
        status: BookProcessingStatus,
        startedAtMillis: Long,
        sectionCount: Int,
        sourceUriString: String,
        sourceName: String?,
    ) {
        if (!application.isDebuggable()) {
            return
        }

        val exportedAtMillis = System.currentTimeMillis()
        val directory = debugLogDirectory().apply { mkdirs() }
        val prefix = debugFilePrefix(
            title = book.title,
            stableId = book.id,
            exportedAtMillis = exportedAtMillis,
        )
        File(directory, "$prefix-processing-log.txt").writeText(
            buildString {
                appendLine("bookId=${book.id}")
                appendLine("title=${book.title}")
                appendLine("sourceName=${sourceName.orEmpty()}")
                appendLine("sourceUri=$sourceUriString")
                appendLine("exportedAt=${debugTimestamp(exportedAtMillis)}")
                appendLine("elapsedMs=${exportedAtMillis - startedAtMillis}")
                appendLine("sections=$sectionCount")
                appendLine("state=${status.state.name}")
                appendLine("language=${status.language}")
                appendLine("nlpProvider=${status.nlpProvider}")
                appendLine("modelId=${status.modelId}")
                appendLine("modelVersion=${status.modelVersion}")
                appendLine("indexVersion=${status.indexVersion}")
                appendLine("pipelineFingerprint=${status.pipelineFingerprint}")
                appendLine("tokenCount=${status.tokenCount}")
                appendLine("uniqueLemmaCount=${status.uniqueLemmaCount}")
                appendLine("savedIndexSizeBytes=${status.savedIndexSizeBytes}")
                appendLine("errorMessage=${status.errorMessage.orEmpty()}")
            },
        )

        val lemmas = store.getLemmaCounts(bookId = book.id)
        if (lemmas.isEmpty()) {
            return
        }

        val tfFile = File(directory, "$prefix-top-lemmas.txt")
        tfFile.writeText(
            buildString {
                appendLine("bookId=${book.id}")
                appendLine("title=${book.title}")
                appendLine("exportedAt=${debugTimestamp(exportedAtMillis)}")
                appendLine("metric=tf")
                appendLine("limit=$DebugLemmaExportLimit")
                appendLine("columns=rank lemma total_count")
                appendLine()
                lemmas
                    .sortedWith(compareByDescending<BookLemmaCount> { it.totalCount }.thenBy { it.lemma })
                    .take(DebugLemmaExportLimit)
                    .forEachIndexed { index, lemma ->
                        appendLine("${index + 1}\t${lemma.lemma}\t${lemma.totalCount}")
                    }
            },
        )

        val tfIdfFile = File(directory, "$prefix-top-lemmas-tfidf.txt")
        tfIdfFile.writeText(
            buildString {
                appendLine("bookId=${book.id}")
                appendLine("title=${book.title}")
                appendLine("exportedAt=${debugTimestamp(exportedAtMillis)}")
                appendLine("metric=tf_idf")
                appendLine("limit=$DebugLemmaExportLimit")
                appendLine("columns=rank lemma tf_idf_score total_count global_frequency_zipf")
                appendLine()
                lemmas
                    .sortedWith(
                        compareByDescending<BookLemmaCount> { it.tfIdfScore }
                            .thenByDescending { it.totalCount }
                            .thenBy { it.lemma },
                    )
                    .take(DebugLemmaExportLimit)
                    .forEachIndexed { index, lemma ->
                        appendLine(
                            "${index + 1}\t${lemma.lemma}\t${lemma.tfIdfScore.toDebugDecimal()}\t" +
                                "${lemma.totalCount}\t${lemma.globalFrequencyZipf.toDebugDecimalOrBlank()}",
                        )
                    }
            },
        )
    }

    private fun exportDebugFailureLog(
        title: String,
        sourceUriString: String,
        error: Throwable,
    ) {
        if (!application.isDebuggable()) {
            return
        }

        val exportedAtMillis = System.currentTimeMillis()
        val prefix = debugFilePrefix(
            title = title,
            stableId = stableId(sourceUriString),
            exportedAtMillis = exportedAtMillis,
        )
        File(debugLogDirectory().apply { mkdirs() }, "$prefix-processing-log.txt").writeText(
            buildString {
                appendLine("bookId=${stableId(sourceUriString)}")
                appendLine("title=$title")
                appendLine("sourceUri=$sourceUriString")
                appendLine("exportedAt=${debugTimestamp(exportedAtMillis)}")
                appendLine("state=${BookProcessingState.Failed.name}")
                appendLine("errorMessage=${error.message ?: "Could not process this EPUB."}")
            },
        )
    }

    private fun debugLogDirectory(): File =
        File(application.filesDir, DebugLemmaIndexDirectory)
}

internal interface AndroidDebugBookBatchProcessor {
    fun processDebugBookFolder(
        folderUriString: String,
        onProgress: (AndroidDebugBookBatchProgress) -> Unit,
        onComplete: (AndroidDebugBookBatchSummary) -> Unit,
        onError: (String) -> Unit,
    )
}

internal data class AndroidDebugBookBatchProgress(
    val processed: Int,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val currentFileName: String,
)

internal data class AndroidDebugBookBatchSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val outputDirectory: String,
)

private data class DebugBookDocument(
    val displayName: String,
    val uri: Uri,
)

private data class ProcessedBookResult(
    val book: BookItem,
    val status: BookProcessingStatus,
)

private inline fun <T> org.readium.r2.shared.publication.Publication.usePublication(
    block: (org.readium.r2.shared.publication.Publication) -> T,
): T =
    try {
        block(this)
    } finally {
        close()
    }

private fun String.toPlainText(): String =
    Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.toTextSections(): List<TextSection> =
    split(Regex("\\n\\s*\\n"))
        .mapIndexedNotNull { index, rawSection ->
            rawSection.toPlainText()
                .takeIf(String::isNotBlank)
                ?.let { TextSection(sectionId = "section-$index", text = it) }
        }
        .ifEmpty {
            toPlainText()
                .takeIf(String::isNotBlank)
                ?.let { listOf(TextSection(sectionId = "section-0", text = it)) }
                .orEmpty()
        }

private fun fallbackTitle(uri: Uri): String =
    uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.normalizedOrNull()
        ?: "Untitled book"

private fun String.normalizedOrNull(): String? =
    trim().takeIf(String::isNotEmpty)

private fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun Application.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private fun debugFilePrefix(
    title: String,
    stableId: String,
    exportedAtMillis: Long,
): String =
    "${title.toDebugFileNamePart()}-${debugTimestamp(exportedAtMillis)}-${stableId.take(12)}"

private fun String.toDebugFileNamePart(): String =
    trim()
        .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
        .replace(Regex("\\s+"), "-")
        .trim('-', '_', '.')
        .take(MaxDebugFileTitleLength)
        .ifBlank { "Untitled-book" }

private fun debugTimestamp(millis: Long): String =
    SimpleDateFormat(DebugTimestampPattern, Locale.US).format(Date(millis))

private fun Double.toDebugDecimal(): String =
    String.format(Locale.US, "%.6f", this)

private fun Double?.toDebugDecimalOrBlank(): String =
    this?.toDebugDecimal().orEmpty()

private const val DebugLemmaExportLimit = 1_000
private const val DebugLemmaIndexDirectory = "debug-lemma-index"
private const val DebugTimestampPattern = "yyyyMMdd-HHmmss"
private const val EpubMimeType = "application/epub+zip"
private const val MaxDebugFileTitleLength = 80
