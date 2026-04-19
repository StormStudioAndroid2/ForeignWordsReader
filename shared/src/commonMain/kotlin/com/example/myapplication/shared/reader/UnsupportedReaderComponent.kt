package com.example.myapplication.shared.reader

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value

class UnsupportedReaderComponent(
    componentContext: ComponentContext,
    uriString: String,
    private val onFinished: () -> Unit,
) : ReaderComponent, ComponentContext by componentContext {

    override val model: Value<ReaderComponent.Model> =
        MutableValue(
            ReaderComponent.Model(
                uriString = uriString,
                status = ReaderComponent.Status.Error,
                errorMessage = "EPUB reading is available on Android only.",
            ),
        )

    override fun onBackClicked() {
        onFinished()
    }

    override fun onPreviousClicked() = Unit

    override fun onNextClicked() = Unit

    override fun onLocatorChanged(
        locatorJson: String,
        readingProgress: Double,
        currentPage: Int,
    ) = Unit
}
