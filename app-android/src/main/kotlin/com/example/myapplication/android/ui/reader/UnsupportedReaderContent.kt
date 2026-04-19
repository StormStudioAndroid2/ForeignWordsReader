package com.example.myapplication.android.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.myapplication.shared.reader.ReaderComponent

@Composable
fun UnsupportedReaderContent(
    model: ReaderComponent.Model,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(model.errorMessage ?: "EPUB reading is available on Android only.")
    }
}