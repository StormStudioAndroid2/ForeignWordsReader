package com.example.myapplication.shared.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import com.example.myapplication.shared.main.BookLibraryGateway
import com.example.myapplication.shared.main.DefaultMainComponent
import com.example.myapplication.shared.main.EmptyBookLibraryGateway
import com.example.myapplication.shared.main.MainComponent
import com.example.myapplication.shared.reader.ReaderComponent
import com.example.myapplication.shared.reader.UnsupportedReaderComponent
import com.example.myapplication.shared.root.RootComponent.Child
import com.example.myapplication.shared.welcome.DefaultWelcomeComponent
import com.example.myapplication.shared.welcome.WelcomeComponent
import kotlinx.serialization.Serializable

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val readerComponentFactory: ReaderComponentFactory,
    private val bookLibraryGateway: BookLibraryGateway,
    private val initialReaderUriString: String?,
) : RootComponent, ComponentContext by componentContext {

    constructor(componentContext: ComponentContext) : this(
        componentContext = componentContext,
        readerComponentFactory = ReaderComponentFactory { childComponentContext, uriString, onFinished ->
            UnsupportedReaderComponent(
                componentContext = childComponentContext,
                uriString = uriString,
                onFinished = onFinished,
            )
        },
        bookLibraryGateway = EmptyBookLibraryGateway,
        initialReaderUriString = null,
    )

    constructor(
        componentContext: ComponentContext,
        readerComponentFactory: ReaderComponentFactory,
    ) : this(
        componentContext = componentContext,
        readerComponentFactory = readerComponentFactory,
        bookLibraryGateway = EmptyBookLibraryGateway,
        initialReaderUriString = null,
    )

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialStack = ::initialStack,
            handleBackButton = true,
            childFactory = ::child,
        )

    private fun initialStack(): List<Config> =
        initialReaderUriString
            ?.let { uriString -> listOf(Config.Main, Config.Reader(uriString = uriString)) }
            ?: listOf(Config.Main)

    private fun child(config: Config, childComponentContext: ComponentContext): Child =
        when (config) {
            is Config.Main -> Child.Main(mainComponent(childComponentContext))
            is Config.Reader -> Child.Reader(readerComponent(config, childComponentContext))
            is Config.Welcome -> Child.Welcome(welcomeComponent(childComponentContext))
        }

    private fun mainComponent(componentContext: ComponentContext): MainComponent =
        DefaultMainComponent(
            componentContext = componentContext,
            bookLibraryGateway = bookLibraryGateway,
            onShowWelcome = { navigation.pushNew(Config.Welcome) },
            onOpenReader = { uriString -> navigation.pushNew(Config.Reader(uriString = uriString)) },
        )

    private fun readerComponent(config: Config.Reader, componentContext: ComponentContext): ReaderComponent =
        readerComponentFactory.create(
            componentContext = componentContext,
            uriString = config.uriString,
            onFinished = navigation::pop,
        )

    private fun welcomeComponent(componentContext: ComponentContext): WelcomeComponent =
        DefaultWelcomeComponent(
            componentContext = componentContext,
            onFinished = navigation::pop,
        )

    override fun onBackClicked(toIndex: Int) {
        navigation.popTo(index = toIndex)
    }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Main : Config

        @Serializable
        data class Reader(val uriString: String) : Config

        @Serializable
        data object Welcome : Config
    }

    fun interface ReaderComponentFactory {
        fun create(
            componentContext: ComponentContext,
            uriString: String,
            onFinished: () -> Unit,
        ): ReaderComponent
    }
}
