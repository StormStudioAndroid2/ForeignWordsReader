package com.example.myapplication.shared.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class AndroidBookLibraryStoreFactory(
    private val context: Context,
) {
    fun create(): BookLibraryStore =
        BookLibraryStore(
            driver = AndroidSqliteDriver(
                schema = BookDatabase.Schema,
                context = context,
                name = "book.db",
            ),
        )
}
