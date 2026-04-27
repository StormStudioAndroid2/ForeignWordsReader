package com.example.myapplication.shared.reader

import com.arkivanov.decompose.value.Value

interface SearchComponent {

    val model: Value<Model>

    fun onOpenRequested()
    fun onDismissRequested()
    fun onQueryChanged(query: String)
    fun onSearchSubmitted()
    fun onClearQueryClicked()
    fun onLoadNextPage()
    fun onResultClicked(locatorJson: String)

    data class Model(
        val isVisible: Boolean = false,
        val query: String = "",
        val status: Status = Status.Idle,
        val results: List<ReaderSearchResultItem> = emptyList(),
        val selectedLocatorJson: String? = null,
        val errorMessage: String? = null,
        val isLoadingMore: Boolean = false,
        val canLoadMore: Boolean = false,
    )

    enum class Status {
        Idle,
        Loading,
        Results,
        Empty,
        Error,
    }
}
