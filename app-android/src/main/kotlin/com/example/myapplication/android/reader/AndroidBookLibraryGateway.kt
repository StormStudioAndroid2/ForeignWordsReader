package com.example.myapplication.android.reader

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.text.Html
import android.net.Uri
import android.util.Size
import com.example.myapplication.shared.data.AndroidBookLibraryStoreFactory
import com.example.myapplication.shared.data.BookLibraryStore
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.main.BookLibraryGateway
import com.example.myapplication.shared.processing.AndroidModelRepository
import com.example.myapplication.shared.processing.AndroidTextAnalysisProvider
import com.example.myapplication.shared.processing.AndroidGlobalFrequencyRepositoryFactory
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
import java.util.Locale
import androidx.core.net.toUri

@OptIn(ExperimentalReadiumApi::class)
internal class AndroidBookLibraryGateway(
    private val application: Application,
) : BookLibraryGateway {

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
                    val sections = openPublication(uriString).usePublication { publication ->
                        publication.content()
                            ?.text()
                            ?.toTextSections()
                            .orEmpty()
                    }
                    val modelRepository = AndroidModelRepository(application)
                    BookAnalysisProcessor(
                        store = store,
                        modelRepository = modelRepository,
                        analysisProvider = AndroidTextAnalysisProvider(modelRepository),
                        clockMillis = System::currentTimeMillis,
                        globalFrequencyRepository = AndroidGlobalFrequencyRepositoryFactory(application).create(),
                    ).processBook(
                        book = book,
                        sections = sections,
                    )
                    exportTopLemmasForDebug(book)
                    store.getBook(uriString) ?: book
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

    private fun saveCover(bookId: String, bitmap: Bitmap): String {
        val directory = File(application.filesDir, "book-covers").apply { mkdirs() }
        val file = File(directory, "$bookId.png")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return file.absolutePath
    }

    private fun exportTopLemmasForDebug(book: BookItem) {
        if (!application.isDebuggable()) {
            return
        }

        val lemmas = store.getLemmaCounts(bookId = book.id)
        if (lemmas.isEmpty()) {
            return
        }

        val directory = File(application.filesDir, "debug-lemma-index").apply { mkdirs() }
        val tfFile = File(directory, "${book.id}-top-lemmas.txt")
        tfFile.writeText(
            buildString {
                appendLine("bookId=${book.id}")
                appendLine("title=${book.title}")
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

        val tfIdfFile = File(directory, "${book.id}-top-lemmas-tfidf.txt")
        tfIdfFile.writeText(
            buildString {
                appendLine("bookId=${book.id}")
                appendLine("title=${book.title}")
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
}

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

private fun Double.toDebugDecimal(): String =
    String.format(Locale.US, "%.6f", this)

private fun Double?.toDebugDecimalOrBlank(): String =
    this?.toDebugDecimal().orEmpty()

private const val DebugLemmaExportLimit = 1_000
