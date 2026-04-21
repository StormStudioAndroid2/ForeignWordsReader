package com.example.myapplication.android.reader

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import com.example.myapplication.shared.data.AndroidBookLibraryStoreFactory
import com.example.myapplication.shared.data.BookLibraryStore
import com.example.myapplication.shared.main.BookItem
import com.example.myapplication.shared.main.BookLibraryGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.security.MessageDigest
import androidx.core.net.toUri

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
        onError: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importBook(uriString)
                }
            }.onSuccess(onResult)
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
                    store.markBookOpened(
                        uriString = uriString,
                        lastOpenedAtMillis = System.currentTimeMillis(),
                    ) ?: error("This book is no longer in the library.")
                }
            }.onSuccess(onResult)
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
            book
        } finally {
            publication.close()
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
