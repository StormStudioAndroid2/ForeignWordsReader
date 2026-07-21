package com.example.myapplication.shared.reader

import com.arkivanov.decompose.value.Value

interface WordsComponent {

    val model: Value<Model>

    fun onOpenRequested()
    fun onDismissRequested()

    data class Model(
        val isVisible: Boolean = false,
        val status: Status = Status.Idle,
        val items: List<ReaderWordItem> = emptyList(),
        val errorMessage: String? = null,
    )

    enum class Status {
        Idle,
        Loading,
        Loaded,
        Empty,
        Error,
    }
}
