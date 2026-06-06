import ReadiumNavigator
import ReadiumShared
import UIKit

private let searchDecorationGroup = "search"
private let searchDecorationId = "selected-search-result"

final class IosEpubReaderViewController: UIViewController, EPUBNavigatorDelegate {
    private let publication: Publication
    private let navigator: EPUBNavigatorViewController
    private let onLocatorChanged: (Locator) -> Void
    private var currentReadingProgress: Double
    private var directionalNavigationAdapter: DirectionalNavigationAdapter?

    var onCenterTap: () -> Void = {}
    var readingProgress: Double {
        currentReadingProgress
    }

    init(
        publication: Publication,
        initialLocation: Locator?,
        onLocatorChanged: @escaping (Locator) -> Void
    ) throws {
        self.publication = publication
        self.navigator = try EPUBNavigatorViewController(
            publication: publication,
            initialLocation: initialLocation,
            config: EPUBNavigatorViewController.Configuration(
                preferences: EPUBPreferences(scroll: false)
            )
        )
        self.onLocatorChanged = onLocatorChanged
        self.currentReadingProgress = initialLocation?.readingProgress ?? 0

        super.init(nibName: nil, bundle: nil)

        navigator.delegate = self

        let adapter = DirectionalNavigationAdapter(
            pointerPolicy: .init(types: [.touch, .mouse]),
            animatedTransition: true
        )
        adapter.bind(to: navigator)
        directionalNavigationAdapter = adapter
    }

    deinit {
        navigator.delegate = nil
        onCenterTap = {}
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        addChild(navigator)
        navigator.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(navigator.view)
        NSLayoutConstraint.activate([
            navigator.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            navigator.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            navigator.view.topAnchor.constraint(equalTo: view.topAnchor),
            navigator.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        navigator.didMove(toParent: self)
    }

    func goForward() async {
        await navigator.goForward(options: .animated)
    }

    func goBackward() async {
        await navigator.goBackward(options: .animated)
    }

    func goToProgress(_ progress: Double) async {
        guard let locator = await publication.locate(progression: progress.clampedProgress) else {
            return
        }
        _ = await navigator.go(to: locator, options: .animated)
    }

    @MainActor
    func goToSearchResult(_ locator: Locator) async -> Bool {
        let didNavigate = await navigator.go(to: locator, options: .animated)
        if didNavigate {
            showSearchHighlight(locator)
        }
        return didNavigate
    }

    @MainActor
    func clearSearchHighlight() {
        navigator.apply(decorations: [], in: searchDecorationGroup)
    }

    @MainActor
    private func showSearchHighlight(_ locator: Locator) {
        guard navigator.supports(decorationStyle: .highlight) else {
            return
        }

        navigator.apply(
            decorations: [
                Decoration(
                    id: searchDecorationId,
                    locator: locator,
                    style: .highlight(tint: UIColor.systemYellow, isActive: true)
                ),
            ],
            in: searchDecorationGroup
        )
    }

    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        currentReadingProgress = locator.readingProgress
        onLocatorChanged(locator)
    }

    func navigatorContentInset(_ navigator: VisualNavigator) -> UIEdgeInsets? {
        .zero
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        let width = navigator.view.bounds.width
        guard width > 0 else { return }

        let centerStart = width * 0.25
        let centerEnd = width * 0.75
        if (centerStart...centerEnd).contains(point.x) {
            onCenterTap()
        }
    }

    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
        NSLog("Readium navigator error: \(error)")
    }
}

extension Locator {
    var readingProgress: Double {
        (locations.totalProgression ?? locations.progression ?? 0).clampedProgress
    }
}
