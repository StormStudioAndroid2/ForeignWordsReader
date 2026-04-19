package com.example.myapplication.android.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun BoxScope.ReaderChromeOverlay(
    visible: Boolean,
    progress: Float,
    onProgressSeeked: (Float) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
    ) {
        ReaderTopStripe()
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        ReaderBottomStripe(
            progress = progress,
            onProgressSeeked = onProgressSeeked,
        )
    }
}

@Composable
private fun ReaderTopStripe() {
    Surface(
        color = MaterialTheme.colors.surface.copy(alpha = 0.94f),
        elevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = {}) {
                Text("Search")
            }
            TextButton(onClick = {}) {
                Text("Bookmark")
            }
            TextButton(onClick = {}) {
                Text("Settings")
            }
            TextButton(onClick = {}) {
                Text("Contents")
            }
        }
    }
}

@Composable
private fun ReaderBottomStripe(
    progress: Float,
    onProgressSeeked: (Float) -> Unit,
) {
    var sliderProgress by remember { mutableStateOf(progress.coerceIn(0f, 1f)) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(progress) {
        if (!isDragging) {
            sliderProgress = progress.coerceIn(0f, 1f)
        }
    }

    Surface(
        color = MaterialTheme.colors.surface.copy(alpha = 0.94f),
        elevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "${(sliderProgress * 100).roundToInt()}%",
                style = MaterialTheme.typography.body2,
            )
            Slider(
                value = sliderProgress,
                onValueChange = { value ->
                    isDragging = true
                    sliderProgress = value.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    isDragging = false
                    onProgressSeeked(sliderProgress.coerceIn(0f, 1f))
                },
            )
        }
    }
}
