import Combine

final class IosReaderState: ObservableObject {
    @Published var phase: IosReaderPhase = .loading
}

enum IosReaderPhase {
    case loading
    case ready(IosEpubReaderViewController)
    case error(String)
}
