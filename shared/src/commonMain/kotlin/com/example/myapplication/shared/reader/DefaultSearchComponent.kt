package com.example.myapplication.shared.reader

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value

class DefaultSearchComponent(
    componentContext: ComponentContext,
    private val gateway: ReaderSearchGateway,
) : SearchComponent, ComponentContext by componentContext {

    private val mutableModel = MutableValue(SearchComponent.Model())
    private var requestId: Long = 0

    override val model: Value<SearchComponent.Model> = mutableModel

    override fun onOpenRequested() {
        mutableModel.value = mutableModel.value.copy(isVisible = true)
    }

    override fun onDismissRequested() {
        mutableModel.value = mutableModel.value.copy(isVisible = false)
    }

    override fun onQueryChanged(query: String) {
        mutableModel.value = mutableModel.value.copy(
            query = query,
            errorMessage = null,
        )

        if (query.isBlank()) {
            resetSearch(clearQuery = false)
        }
    }

    override fun onSearchSubmitted() {
        val query = mutableModel.value.query.trim()
        if (query.isEmpty()) {
            resetSearch(clearQuery = false)
            return
        }

        requestId += 1
        val currentRequestId = requestId
        gateway.cancelSearch()
        mutableModel.value = mutableModel.value.copy(
            isVisible = true,
            query = query,
            status = SearchComponent.Status.Loading,
            results = emptyList(),
            selectedLocatorJson = null,
            errorMessage = null,
            isLoadingMore = false,
            canLoadMore = false,
        )

        gateway.startSearch(
            query = query,
            onPage = { page ->
                if (currentRequestId != requestId) return@startSearch
                mutableModel.value = mutableModel.value.copy(
                    status = SearchComponent.Status.Results,
                    results = page.results,
                    errorMessage = null,
                    isLoadingMore = false,
                    canLoadMore = page.results.isNotEmpty(),
                )
            },
            onComplete = {
                if (currentRequestId != requestId) return@startSearch
                mutableModel.value = mutableModel.value.copy(
                    status = if (mutableModel.value.results.isEmpty()) {
                        SearchComponent.Status.Empty
                    } else {
                        SearchComponent.Status.Results
                    },
                    isLoadingMore = false,
                    canLoadMore = false,
                )
            },
            onError = { message ->
                if (currentRequestId != requestId) return@startSearch
                mutableModel.value = mutableModel.value.copy(
                    status = SearchComponent.Status.Error,
                    errorMessage = message,
                    isLoadingMore = false,
                    canLoadMore = false,
                )
            },
        )
    }

    override fun onClearQueryClicked() {
        mutableModel.value = mutableModel.value.copy(query = "")
        resetSearch(clearQuery = false)
    }

    override fun onLoadNextPage() {
        val currentModel = mutableModel.value
        if (currentModel.status != SearchComponent.Status.Results || currentModel.isLoadingMore || !currentModel.canLoadMore) {
            return
        }

        val currentRequestId = requestId
        mutableModel.value = currentModel.copy(
            isLoadingMore = true,
            errorMessage = null,
        )
        gateway.loadMore(
            onPage = { page ->
                if (currentRequestId != requestId) return@loadMore
                mutableModel.value = mutableModel.value.copy(
                    status = SearchComponent.Status.Results,
                    results = mutableModel.value.results + page.results,
                    errorMessage = null,
                    isLoadingMore = false,
                    canLoadMore = page.results.isNotEmpty(),
                )
            },
            onComplete = {
                if (currentRequestId != requestId) return@loadMore
                mutableModel.value = mutableModel.value.copy(
                    status = if (mutableModel.value.results.isEmpty()) {
                        SearchComponent.Status.Empty
                    } else {
                        SearchComponent.Status.Results
                    },
                    isLoadingMore = false,
                    canLoadMore = false,
                )
            },
            onError = { message ->
                if (currentRequestId != requestId) return@loadMore
                mutableModel.value = mutableModel.value.copy(
                    errorMessage = message,
                    isLoadingMore = false,
                    canLoadMore = false,
                    status = if (mutableModel.value.results.isEmpty()) {
                        SearchComponent.Status.Error
                    } else {
                        SearchComponent.Status.Results
                    },
                )
            },
        )
    }

    override fun onResultClicked(locatorJson: String) {
        mutableModel.value = mutableModel.value.copy(
            selectedLocatorJson = locatorJson,
            errorMessage = null,
        )
        gateway.navigateToResult(
            locatorJson = locatorJson,
            onSuccess = {
                mutableModel.value = mutableModel.value.copy(
                    isVisible = false,
                    selectedLocatorJson = locatorJson,
                    errorMessage = null,
                )
            },
            onError = { message ->
                mutableModel.value = mutableModel.value.copy(errorMessage = message)
            },
        )
    }

    private fun resetSearch(clearQuery: Boolean) {
        requestId += 1
        gateway.cancelSearch()
        mutableModel.value = SearchComponent.Model(
            isVisible = mutableModel.value.isVisible,
            query = if (clearQuery) "" else mutableModel.value.query,
        )
    }
}
