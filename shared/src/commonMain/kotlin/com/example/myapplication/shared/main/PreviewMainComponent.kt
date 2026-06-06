package com.example.myapplication.shared.main

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.example.myapplication.shared.processing.BookProcessingState

object PreviewMainComponent : MainComponent {
    override val model: Value<MainComponent.Model> =
        MutableValue(
            MainComponent.Model(
                books = listOf(
                    BookItem(
                        id = "preview",
                        uriString = "preview://book",
                        title = "Sample EPUB",
                        author = "Unknown author",
                        coverUriString = null,
                        lastOpenedAtMillis = 0L,
                        processingState = BookProcessingState.Processing,
                    ),
                ),
            ),
        )

    override fun onShowWelcomeClicked() {}
    override fun onBookClicked(uriString: String) {}
    override fun onEpubSelected(uriString: String) {}
}
