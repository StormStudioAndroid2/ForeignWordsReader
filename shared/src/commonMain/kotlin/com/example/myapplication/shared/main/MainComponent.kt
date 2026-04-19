package com.example.myapplication.shared.main

import com.arkivanov.decompose.value.Value

interface MainComponent {

    val model: Value<Model>

    fun onShowWelcomeClicked()
    fun onBookClicked(uriString: String)
    fun onEpubSelected(uriString: String)

    data class Model(
        val books: List<BookItem>,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )
}
