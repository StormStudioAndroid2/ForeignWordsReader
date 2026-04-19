package com.example.myapplication.shared.main

interface BookLibraryGateway {
    fun loadBooks(
        onResult: (List<BookItem>) -> Unit,
        onError: (String) -> Unit,
    )

    fun importBook(
        uriString: String,
        onResult: (BookItem) -> Unit,
        onError: (String) -> Unit,
    )

    fun markBookOpened(
        uriString: String,
        onResult: (BookItem) -> Unit,
        onError: (String) -> Unit,
    )
}

object EmptyBookLibraryGateway : BookLibraryGateway {
    override fun loadBooks(
        onResult: (List<BookItem>) -> Unit,
        onError: (String) -> Unit,
    ) {
        onResult(emptyList())
    }

    override fun importBook(
        uriString: String,
        onResult: (BookItem) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("EPUB library is not available on this platform.")
    }

    override fun markBookOpened(
        uriString: String,
        onResult: (BookItem) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("EPUB library is not available on this platform.")
    }
}
