package com.example.myapplication.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosBookLibraryStoreFactory {
    fun create(): BookLibraryStore =
        BookLibraryStore(
            driver = NativeSqliteDriver(
                schema = BookDatabase.Schema,
                name = "book.db",
            ),
        )
}
