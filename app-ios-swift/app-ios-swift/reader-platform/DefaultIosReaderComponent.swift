import shared

final class DefaultIosReaderComponent: ReaderComponent {
    private let uriString: String
    private let onFinished: () -> Void
    private let mutableModel: MutableValue<ReaderComponentModel>
    private let runtime: IosReaderRuntime

    let readerState: IosReaderState
    let search: SearchComponent

    var model: Value<ReaderComponentModel> {
        mutableModel
    }

    init(
        componentContext: ComponentContext,
        uriString: String,
        onFinished: @escaping () -> Void
    ) {
        self.uriString = uriString
        self.onFinished = onFinished

        let initialModel = ReaderComponentModel(
            uriString: uriString,
            status: ReaderComponentStatus.loading,
            errorMessage: nil,
            readingProgress: 0,
            currentPage: 0,
            title: ""
        )
        self.mutableModel = mutableValue(initialModel)

        let state = IosReaderState()
        self.readerState = state
        self.runtime = IosReaderRuntime(
            uriString: uriString,
            model: mutableModel,
            state: state
        )
        self.search = DefaultSearchComponent(
            componentContext: componentContext,
            gateway: runtime.searchGateway
        )
        runtime.open()
    }

    deinit {
        runtime.close()
    }

    func onBackClicked() {
        runtime.close()
        onFinished()
    }

    func onPreviousClicked() {
        runtime.goBackward()
    }

    func onNextClicked() {
        runtime.goForward()
    }

    func onLocatorChanged(locatorJson: String, readingProgress: Double, currentPage: Int32) {
        IosReaderPersistence.saveLocator(locatorJson, uriString: uriString)
        mutableModel.value = ReaderComponentModel(
            uriString: mutableModel.value.uriString,
            status: mutableModel.value.status,
            errorMessage: mutableModel.value.errorMessage,
            readingProgress: readingProgress,
            currentPage: currentPage,
            title: mutableModel.value.title
        )
    }
}

final class IosReaderComponentFactory: DefaultRootComponentReaderComponentFactory {
    func create(
        componentContext: ComponentContext,
        uriString: String,
        onFinished: @escaping () -> Void
    ) -> ReaderComponent {
        DefaultIosReaderComponent(
            componentContext: componentContext,
            uriString: uriString,
            onFinished: onFinished
        )
    }
}
