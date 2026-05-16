package com.example.myapplication.shared.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value

class DefaultMainComponent(
    componentContext: ComponentContext,
    private val bookLibraryGateway: BookLibraryGateway,
    private val onShowWelcome: () -> Unit,
    private val onOpenReader: (String) -> Unit,
) : MainComponent, ComponentContext by componentContext {

    private val mutableModel = MutableValue(MainComponent.Model(books = emptyList(), isLoading = true))

    override val model: Value<MainComponent.Model> = mutableModel

    init {
        loadBooks()
    }

    override fun onShowWelcomeClicked() {
        onShowWelcome()
    }

    override fun onBookClicked(uriString: String) {
        bookLibraryGateway.markBookOpened(
            uriString = uriString,
            onResult = { book ->
                mutableModel.value = mutableModel.value.copy(
                    books = mutableModel.value.books.withBookFirst(book),
                    errorMessage = null,
                )
                onOpenReader(uriString)
            },
            onError = { message ->
                mutableModel.value = mutableModel.value.copy(errorMessage = message)
            },
        )
    }

    override fun onEpubSelected(uriString: String) {
        mutableModel.value = mutableModel.value.copy(isLoading = true, errorMessage = null)
        bookLibraryGateway.importBook(
            uriString = uriString,
            onResult = { book ->
                mutableModel.value = mutableModel.value.copy(
                    books = mutableModel.value.books.withBookFirst(book),
                    isLoading = false,
                    errorMessage = null,
                )
                onOpenReader(book.uriString)
            },
            onProcessingChanged = { book ->
                mutableModel.value = mutableModel.value.copy(
                    books = mutableModel.value.books.withBookFirst(book),
                    isLoading = false,
                    errorMessage = null,
                )
            },
            onError = { message ->
                mutableModel.value = mutableModel.value.copy(
                    isLoading = false,
                    errorMessage = message,
                )
            },
        )
    }

    private fun loadBooks() {
        mutableModel.value = mutableModel.value.copy(isLoading = true, errorMessage = null)
        bookLibraryGateway.loadBooks(
            onResult = { books ->
                mutableModel.value = mutableModel.value.copy(
                    books = books,
                    isLoading = false,
                    errorMessage = null,
                )
            },
            onError = { message ->
                mutableModel.value = mutableModel.value.copy(
                    isLoading = false,
                    errorMessage = message,
                )
            },
        )
    }
}

private fun List<BookItem>.withBookFirst(book: BookItem): List<BookItem> =
    listOf(book) + filterNot { it.uriString == book.uriString }
