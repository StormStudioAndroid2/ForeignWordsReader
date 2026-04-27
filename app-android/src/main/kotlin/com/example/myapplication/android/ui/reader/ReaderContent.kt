package com.example.myapplication.android.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentManager
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.example.myapplication.android.reader.AndroidReaderModel
import com.example.myapplication.android.reader.DefaultAndroidReaderComponent
import com.example.myapplication.shared.reader.ReaderComponent

@Composable
fun AndroidReaderContent(
    component: ReaderComponent,
    fragmentManager: FragmentManager,
    modifier: Modifier = Modifier,
) {
    val model by component.model.subscribeAsState()
    val searchModel by component.search.model.subscribeAsState()
    val androidComponent = component as? DefaultAndroidReaderComponent
    if (androidComponent == null) {
        UnsupportedReaderContent(
            model = model,
            modifier = modifier,
        )
        return
    }

    val androidModel by androidComponent.androidModel.subscribeAsState()
    var overlayVisible by remember(androidModel) { mutableStateOf(false) }
    var searchDialogVisible by remember(androidModel) { mutableStateOf(false) }
    var seekRequestId by remember(androidModel) { mutableStateOf(0) }
    var seekRequest by remember(androidModel) { mutableStateOf<Pair<Int, Float>?>(null) }
    val readerTitle = model.title.trim().ifEmpty { "EPUB Reader" }
    val effectiveSearchVisible = searchDialogVisible || searchModel.isVisible

    LaunchedEffect(searchModel.isVisible) {
        if (!searchModel.isVisible && searchDialogVisible) {
            searchDialogVisible = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(readerTitle) },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val current = androidModel) {
                is AndroidReaderModel.Error -> ErrorReaderState(
                    message = current.message,
                    onBackClicked = component::onBackClicked,
                )
                is AndroidReaderModel.Loading -> LoadingReaderState()
                is AndroidReaderModel.Ready -> ReadiumNavigatorHost(
                    state = current,
                    fragmentManager = fragmentManager,
                    onLocatorChanged = component::onLocatorChanged,
                    onCenterTap = { overlayVisible = !overlayVisible },
                    seekRequest = seekRequest,
                    onSeekRequestHandled = { seekRequest = null },
                    onNavigatorAttached = androidComponent::attachNavigator,
                    onNavigatorDetached = androidComponent::detachNavigator,
                )
            }

            if (androidModel is AndroidReaderModel.Ready) {
                ReaderChromeOverlay(
                    visible = overlayVisible && !effectiveSearchVisible,
                    progress = model.readingProgress.toFloat(),
                    onProgressSeeked = { progress ->
                        seekRequestId += 1
                        seekRequest = seekRequestId to progress
                    },
                    onSearchClicked = {
                        searchDialogVisible = true
                        component.search.onOpenRequested()
                    },
                )
            }

            ReaderSearchOverlay(
                component = component.search,
                model = searchModel.copy(isVisible = effectiveSearchVisible),
                onDismissRequested = {
                    searchDialogVisible = false
                    component.search.onDismissRequested()
                },
                onResultClicked = { locatorJson ->
                    searchDialogVisible = false
                    component.search.onResultClicked(locatorJson)
                },
            )
        }
    }
}
