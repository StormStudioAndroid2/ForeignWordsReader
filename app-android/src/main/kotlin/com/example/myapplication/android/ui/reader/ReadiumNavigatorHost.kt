package com.example.myapplication.android.ui.reader

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import com.example.myapplication.android.reader.AndroidReaderModel
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression

private const val NavigatorTag = "readium_epub_navigator"

@OptIn(ExperimentalReadiumApi::class)
@Composable
fun ReadiumNavigatorHost(
    state: AndroidReaderModel.Ready,
    fragmentManager: FragmentManager,
    onLocatorChanged: (String, Double, Int) -> Unit,
    onCenterTap: () -> Unit,
    seekRequest: Pair<Int, Float>?,
    onSeekRequestHandled: () -> Unit,
    onNavigatorAttached: (EpubNavigatorFragment) -> Unit,
    onNavigatorDetached: (EpubNavigatorFragment) -> Unit,
) {
    val context = LocalContext.current
    val currentOnLocatorChanged by rememberUpdatedState(onLocatorChanged)
    val currentOnCenterTap by rememberUpdatedState(onCenterTap)
    val currentOnSeekRequestHandled by rememberUpdatedState(onSeekRequestHandled)
    val currentOnNavigatorAttached by rememberUpdatedState(onNavigatorAttached)
    val currentOnNavigatorDetached by rememberUpdatedState(onNavigatorDetached)

    DisposableEffect(state.readerKey) {
        onDispose {
            if (!fragmentManager.isStateSaved) {
                fragmentManager.removeCurrentNavigator()
            }
        }
    }

    LaunchedEffect(state.readerKey) {
        val navigator = fragmentManager.awaitCurrentNavigator()
        currentOnNavigatorAttached(navigator)
        val directionalNavigation = DirectionalNavigationAdapter(
            navigator = navigator,
            animatedTransition = true,
        )
        val overlayTapListener = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = navigator.publicationView.width.toFloat()
                if (width <= 0f) {
                    return false
                }

                val centerStart = width * 0.25f
                val centerEnd = width * 0.75f
                return if (event.point.x in centerStart..centerEnd) {
                    currentOnCenterTap()
                    true
                } else {
                    false
                }
            }
        }

        navigator.addInputListener(directionalNavigation)
        navigator.addInputListener(overlayTapListener)
        try {
            navigator.currentLocator.collect { locator ->
                currentOnLocatorChanged(
                    locator.toJSON().toString(),
                    locator.readingProgress,
                    locator.locations.position ?: 0,
                )
            }
        } finally {
            navigator.removeInputListener(overlayTapListener)
            navigator.removeInputListener(directionalNavigation)
            currentOnNavigatorDetached(navigator)
        }
    }

    LaunchedEffect(state.readerKey, seekRequest) {
        val progress = seekRequest?.second ?: return@LaunchedEffect
        try {
            val navigator = fragmentManager.awaitCurrentNavigator()
            val locator = state.publication.locateProgression(progress.toDouble().coerceIn(0.0, 1.0))
                ?: return@LaunchedEffect
            navigator.go(locator, animated = true)
        } finally {
            currentOnSeekRequestHandled()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            FragmentContainerView(context).apply {
                id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { container ->
            if (container.tag != state.readerKey) {
                container.tag = state.readerKey
                fragmentManager.fragmentFactory = state.fragmentFactory
                fragmentManager.commit {
                    setReorderingAllowed(true)
                    replace(container.id, EpubNavigatorFragment::class.java, null, NavigatorTag)
                }
            }
        },
    )
}

private fun FragmentManager.currentNavigator(): EpubNavigatorFragment? =
    findFragmentByTag(NavigatorTag) as? EpubNavigatorFragment

private suspend fun FragmentManager.awaitCurrentNavigator(): EpubNavigatorFragment {
    while (true) {
        currentNavigator()?.let { return it }
        delay(100)
    }
}

fun FragmentManager.removeCurrentNavigator() {
    findFragmentByTag(NavigatorTag)?.let { fragment ->
        commitNow {
            remove(fragment)
        }
    }
}

private val Locator.readingProgress: Double
    get() = (locations.totalProgression ?: locations.progression ?: 0.0).coerceIn(0.0, 1.0)
