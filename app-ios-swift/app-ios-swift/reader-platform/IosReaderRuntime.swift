import Foundation
import ReadiumShared
import UIKit
import shared

private let locatorProgressPersistenceDelta = 0.005
private let locatorPersistenceInterval: TimeInterval = 5

final class IosReaderRuntime {
    private let uriString: String
    private let model: MutableValue<ReaderComponentModel>
    private let state: IosReaderState
    private let readium = IosReadium()
    let searchGateway = IosReaderSearchGateway()
    private var task: Task<Void, Never>?
    private var securityAccess: SecurityScopedURLAccess?
    private var pendingLocator: PendingReaderLocator?
    private var lastPersistedProgress: Double?
    private var lastPersistedAt = Date.distantPast
    private var backgroundObserver: NSObjectProtocol?
    private var isClosed = false

    init(
        uriString: String,
        model: MutableValue<ReaderComponentModel>,
        state: IosReaderState
    ) {
        self.uriString = uriString
        self.model = model
        self.state = state
        self.backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.persistPendingLocator(force: true)
        }
    }

    deinit {
        close()
    }

    func open() {
        isClosed = false
        task?.cancel()
        task = Task { [weak self] in
            await self?.openPublication()
        }
    }

    func close() {
        guard !isClosed else { return }

        isClosed = true
        task?.cancel()
        task = nil
        searchGateway.invalidate()
        persistPendingLocator(force: true)
        securityAccess?.stop()
        securityAccess = nil

        if let backgroundObserver {
            NotificationCenter.default.removeObserver(backgroundObserver)
            self.backgroundObserver = nil
        }

        Task { @MainActor [weak self] in
            self?.state.phase = .loading
        }
    }

    func goForward() {
        Task { @MainActor [weak self] in
            guard let self, !self.isClosed, case let .ready(reader) = self.state.phase else { return }
            await reader.goForward()
        }
    }

    func goBackward() {
        Task { @MainActor [weak self] in
            guard let self, !self.isClosed, case let .ready(reader) = self.state.phase else { return }
            await reader.goBackward()
        }
    }

    private func openPublication() async {
        await publish(status: .loading)

        do {
            let sourceURL = try IosReaderPersistence.resolveURL(uriString: uriString)
            let access = SecurityScopedURLAccess(url: sourceURL)
            securityAccess = access

            guard let fileURL = FileURL(url: access.url) else {
                throw IosReaderError.unsupportedURL
            }

            let asset = try await readium.assetRetriever
                .retrieve(url: fileURL, mediaType: .epub)
                .get()
            guard !Task.isCancelled, !isClosed else { return }

            let publication = try await readium.publicationOpener
                .open(asset: asset, allowUserInteraction: true)
                .get()
            guard !Task.isCancelled, !isClosed else { return }

            let restoredLocator = IosReaderPersistence.restoreLocator(uriString: uriString)
            let reader = try await MainActor.run {
                try IosEpubReaderViewController(
                    publication: publication,
                    initialLocation: restoredLocator,
                    onLocatorChanged: { [weak self] locator in
                        self?.saveLocator(locator)
                    }
                )
            }
            guard !Task.isCancelled, !isClosed else { return }

            IosReaderPersistence.saveLastEpub(uriString: uriString)
            searchGateway.update(publication: publication, reader: reader)
            await publish(
                status: .ready,
                reader: reader,
                readingProgress: restoredLocator?.readingProgress ?? 0,
                currentPage: Int32(restoredLocator?.locations.position ?? 0),
                title: publication.metadata.title
            )
        } catch is CancellationError {
            return
        } catch {
            searchGateway.invalidate()
            securityAccess?.stop()
            securityAccess = nil
            await publish(status: .error, message: IosReaderError.message(for: error))
        }
    }

    private func saveLocator(_ locator: Locator) {
        guard !isClosed else { return }

        let readingProgress = locator.readingProgress
        let currentPage = Int32(locator.locations.position ?? 0)

        if let locatorJson = locator.jsonString {
            pendingLocator = PendingReaderLocator(
                json: locatorJson,
                readingProgress: readingProgress
            )
            persistPendingLocator(force: false)
        }

        model.value = ReaderComponentModel(
            uriString: model.value.uriString,
            status: model.value.status,
            errorMessage: model.value.errorMessage,
            readingProgress: readingProgress,
            currentPage: currentPage,
            title: model.value.title
        )
    }

    private func persistPendingLocator(force: Bool) {
        guard let pendingLocator else { return }

        let progressDelta = abs(pendingLocator.readingProgress - (lastPersistedProgress ?? -1))
        let elapsed = Date().timeIntervalSince(lastPersistedAt)
        guard force || lastPersistedProgress == nil || progressDelta >= locatorProgressPersistenceDelta || elapsed >= locatorPersistenceInterval else {
            return
        }

        IosReaderPersistence.saveLocator(pendingLocator.json, uriString: uriString)
        lastPersistedProgress = pendingLocator.readingProgress
        lastPersistedAt = Date()
    }

    @MainActor
    private func publish(
        status: ReaderComponentStatus,
        message: String? = nil,
        reader: IosEpubReaderViewController? = nil,
        readingProgress: Double = 0,
        currentPage: Int32 = 0,
        title: String? = nil
    ) {
        guard !isClosed else { return }

        model.value = ReaderComponentModel(
            uriString: uriString,
            status: status,
            errorMessage: message,
            readingProgress: readingProgress,
            currentPage: currentPage,
            title: title ?? ""
        )

        if let reader {
            state.phase = .ready(reader)
        } else if status == ReaderComponentStatus.error {
            state.phase = .error(message ?? "Could not open this EPUB.")
        } else {
            state.phase = .loading
        }
    }
}

private struct PendingReaderLocator {
    let json: String
    let readingProgress: Double
}
