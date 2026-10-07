package com.example.myapplication.android.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.shared.reader.ReaderWordItem
import com.example.myapplication.shared.reader.WordsComponent

@Composable
fun ReaderWordsOverlay(
    component: WordsComponent,
    model: WordsComponent.Model,
    onDismissRequested: () -> Unit = component::onDismissRequested,
    modifier: Modifier = Modifier,
) {
    if (!model.isVisible) {
        return
    }

    val context = LocalContext.current
    val activity = context.findActivity()

    Dialog(
        onDismissRequest = onDismissRequested,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .then(modifier),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .align(Alignment.TopCenter),
                elevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Frequent words",
                            style = MaterialTheme.typography.h6,
                        )
                        IconButton(onClick = onDismissRequested) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close words",
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = wordsSummary(model),
                        style = MaterialTheme.typography.body2,
                        color = if (model.status == WordsComponent.Status.Error) {
                            MaterialTheme.colors.error
                        } else {
                            MaterialTheme.colors.onSurface.copy(alpha = 0.72f)
                        },
                    )

                    if (model.status == WordsComponent.Status.Loaded) {
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Grouped forms from this book. Numbers show how many times each word appears.",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.62f),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when (model.status) {
                        WordsComponent.Status.Idle,
                        WordsComponent.Status.Loading -> WordsLoadingState()
                        WordsComponent.Status.Empty -> WordsEmptyState("No frequent words are ready for this book yet.")
                        WordsComponent.Status.Error -> WordsEmptyState(model.errorMessage ?: "Could not load frequent words.")
                        WordsComponent.Status.Loaded -> ReaderWordsList(
                            items = model.items,
                            onDictionaryClicked = { item ->
                                if (activity != null) {
                                    ExternalTextActionLauncher.launch(
                                        activity = activity,
                                        item = item,
                                        action = ExternalTextAction.Dictionary,
                                    )
                                } else {
                                    ExternalTextActionLauncher.showUnavailableMessage(context)
                                }
                            },
                            onTranslateClicked = { item ->
                                if (activity != null) {
                                    ExternalTextActionLauncher.launch(
                                        activity = activity,
                                        item = item,
                                        action = ExternalTextAction.Translate,
                                    )
                                } else {
                                    ExternalTextActionLauncher.showUnavailableMessage(context)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderWordsList(
    items: List<ReaderWordItem>,
    onDictionaryClicked: (ReaderWordItem) -> Unit,
    onTranslateClicked: (ReaderWordItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = items,
            key = { it.lemma },
        ) { item ->
            WordRow(
                item = item,
                onDictionaryClicked = { onDictionaryClicked(item) },
                onTranslateClicked = { onTranslateClicked(item) },
            )
        }
    }
}

@Composable
private fun WordRow(
    item: ReaderWordItem,
    onDictionaryClicked: () -> Unit,
    onTranslateClicked: () -> Unit,
) {
    val displayWord = item.resolvedDisplayWord()
    val lemma = item.lemma.trim()
    val showBaseForm = lemma.isNotEmpty() && !lemma.equals(displayWord, ignoreCase = true)
    val extraSurfaceWords = item.surfaceWords
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .drop(1)
    val actionEnabled = item.actionText().isNotEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayWord,
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showBaseForm) {
                        Text(
                            text = "Base form: $lemma",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (extraSurfaceWords.isNotEmpty()) {
                        Text(
                            text = "Forms: ${extraSurfaceWords.joinToString(separator = ", ")}",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = item.totalCount.frequencyLabel(),
                    modifier = Modifier.semantics {
                        contentDescription = "${item.totalCount} ${item.totalCount.timesWord()} in this book"
                    },
                    style = MaterialTheme.typography.subtitle2,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.84f),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDictionaryClicked,
                    enabled = actionEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Dictionary")
                }
                TextButton(
                    onClick = onTranslateClicked,
                    enabled = actionEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Translate")
                }
            }
        }
    }
}

@Composable
private fun WordsEmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun WordsLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

private fun wordsSummary(model: WordsComponent.Model): String =
    when (model.status) {
        WordsComponent.Status.Idle -> "Loading frequent words..."
        WordsComponent.Status.Loading -> "Loading frequent words..."
        WordsComponent.Status.Loaded -> "${model.items.size} frequent words found"
        WordsComponent.Status.Empty -> "No frequent words are ready for this book yet."
        WordsComponent.Status.Error -> model.errorMessage ?: "Could not load frequent words."
    }

private fun ReaderWordItem.resolvedDisplayWord(): String =
    displayWord.trim().ifEmpty { lemma.trim() }

private fun Long.frequencyLabel(): String =
    "$this ${timesWord()}"

private fun Long.timesWord(): String =
    if (this == 1L) "time" else "times"
