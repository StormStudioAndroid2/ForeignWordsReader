package com.example.myapplication.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.scale
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.example.myapplication.android.reader.AndroidBookLibraryGateway
import com.example.myapplication.android.reader.AndroidDebugBookBatchProcessor
import com.example.myapplication.android.reader.DefaultAndroidReaderComponent
import com.example.myapplication.android.reader.lastReadableEpubUriString
import com.example.myapplication.android.ui.AndroidWelcomeContent
import com.example.myapplication.android.ui.main.AndroidMainContent
import com.example.myapplication.android.ui.reader.AndroidReaderContent
import com.example.myapplication.android.ui.reader.removeCurrentNavigator
import com.example.myapplication.shared.root.DefaultRootComponent
import com.example.myapplication.shared.root.RootComponent
import com.example.myapplication.shared.root.RootComponent.Child

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bookLibraryGateway = AndroidBookLibraryGateway(application)
        val root = DefaultRootComponent(
            componentContext = defaultComponentContext(),
            readerComponentFactory = { childComponentContext, uriString, onFinished ->
                DefaultAndroidReaderComponent(
                    componentContext = childComponentContext,
                    application = application,
                    uriString = uriString,
                    onFinished = onFinished,
                )
            },
            bookLibraryGateway = bookLibraryGateway,
            initialReaderUriString = lastReadableEpubUriString(this),
        )

        setContent {
            AndroidRootContent(
                component = root,
                fragmentManager = supportFragmentManager,
                debugBatchProcessor = bookLibraryGateway.takeIf { application.isDebuggable() },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        supportFragmentManager.removeCurrentNavigator()
        super.onSaveInstanceState(outState)
    }
}

private fun android.app.Application.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

@Composable
private fun AndroidRootContent(
    component: RootComponent,
    fragmentManager: FragmentManager,
    debugBatchProcessor: AndroidDebugBookBatchProcessor?,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Children(
                stack = component.stack,
                modifier = Modifier.fillMaxSize(),
                animation = stackAnimation(fade() + scale()),
            ) {
                when (val child = it.instance) {
                    is Child.Main -> AndroidMainContent(
                        component = child.component,
                        debugBatchProcessor = debugBatchProcessor,
                    )
                    is Child.Reader -> AndroidReaderContent(
                        component = child.component,
                        fragmentManager = fragmentManager,
                    )

                    is Child.Welcome -> AndroidWelcomeContent(component = child.component)
                }
            }
        }
    }
}
