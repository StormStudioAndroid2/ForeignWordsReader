package com.example.myapplication.shared.reader

interface ReaderSearchGateway {
    fun startSearch(
        query: String,
        onPage: (ReaderSearchPage) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    )

    fun loadMore(
        onPage: (ReaderSearchPage) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    )

    fun cancelSearch()

    fun navigateToResult(
        locatorJson: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    )
}

object EmptyReaderSearchGateway : ReaderSearchGateway {
    override fun startSearch(
        query: String,
        onPage: (ReaderSearchPage) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("Search is not available on this platform.")
    }

    override fun loadMore(
        onPage: (ReaderSearchPage) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        onComplete()
    }

    override fun cancelSearch() = Unit

    override fun navigateToResult(
        locatorJson: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("Search is not available on this platform.")
    }
}
