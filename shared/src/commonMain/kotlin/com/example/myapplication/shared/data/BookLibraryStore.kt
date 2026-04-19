package com.example.myapplication.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.example.myapplication.shared.main.BookItem

class BookLibraryStore(
    driver: SqlDriver,
) {
    private val database = BookDatabase(driver)
    private val queries = database.bookDatabaseQueries

    fun getRecentBooks(): List<BookItem> =
        queries.selectRecentBooks(::mapBook).executeAsList()

    fun getBook(uriString: String): BookItem? =
        queries.selectByUri(uri_string = uriString, ::mapBook).executeAsOneOrNull()

    fun upsertBook(book: BookItem) {
        queries.upsertBook(
            id = book.id,
            uri_string = book.uriString,
            title = book.title,
            author = book.author,
            cover_uri_string = book.coverUriString,
            last_opened_at_millis = book.lastOpenedAtMillis,
        )
    }

    fun markBookOpened(uriString: String, lastOpenedAtMillis: Long): BookItem? {
        queries.updateLastOpened(
            last_opened_at_millis = lastOpenedAtMillis,
            uri_string = uriString,
        )
        return getBook(uriString)
    }

    private fun mapBook(
        id: String,
        uriString: String,
        title: String,
        author: String,
        coverUriString: String?,
        lastOpenedAtMillis: Long,
    ): BookItem =
        BookItem(
            id = id,
            uriString = uriString,
            title = title,
            author = author,
            coverUriString = coverUriString,
            lastOpenedAtMillis = lastOpenedAtMillis,
        )
}
