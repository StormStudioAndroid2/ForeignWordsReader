package com.example.myapplication.shared.reader

import com.arkivanov.decompose.value.Value

interface ReaderComponent {

    val model: Value<Model>

    fun onBackClicked()
    fun onPreviousClicked()
    fun onNextClicked()
    fun onLocatorChanged(locatorJson: String, readingProgress: Double, currentPage: Int)

    data class Model(
        val uriString: String,
        val status: Status = Status.Loading,
        val errorMessage: String? = null,
        val readingProgress: Double = 0.0,
        val currentPage: Int = 0,
    )

    enum class Status {
        Loading,
        Ready,
        Error,
    }
}
