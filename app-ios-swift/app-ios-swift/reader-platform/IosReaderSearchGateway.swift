import Foundation
import ReadiumShared
import shared

final class IosReaderSearchGateway: ReaderSearchGateway {
    private var publication: Publication?
    private weak var reader: IosEpubReaderViewController?
    private var iterator: SearchIterator?
    private var task: Task<Void, Never>?

    func update(publication: Publication, reader: IosEpubReaderViewController) {
        self.publication = publication
        self.reader = reader
    }

    func invalidate() {
        cancelSearch()
        publication = nil
        reader = nil
    }

    func startSearch(
        query: String,
        onPage: @escaping (ReaderSearchPage) -> Void,
        onComplete: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        cancelSearch()

        guard let publication else {
            onError("Reader is not ready.")
            return
        }

        task = Task { [weak self] in
            guard let self else { return }

            switch await publication.search(query: query) {
            case let .success(iterator):
                self.iterator = iterator
                self.loadNextPage(
                    onPage: onPage,
                    onComplete: onComplete,
                    onError: onError
                )

            case let .failure(error):
                await MainActor.run {
                    onError(self.message(for: error))
                }
            }
        }
    }

    func loadMore(
        onPage: @escaping (ReaderSearchPage) -> Void,
        onComplete: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        loadNextPage(
            onPage: onPage,
            onComplete: onComplete,
            onError: onError
        )
    }

    func cancelSearch() {
        task?.cancel()
        task = nil
        iterator?.close()
        iterator = nil

        Task { @MainActor [weak self] in
            self?.reader?.clearSearchHighlight()
        }
    }

    func navigateToResult(
        locatorJson: String,
        onSuccess: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        guard let reader else {
            onError("Reader is not ready.")
            return
        }

        let locator: Locator?
        do {
            locator = try Locator(jsonString: locatorJson)
        } catch {
            onError("Could not open this search result.")
            return
        }

        guard let locator else {
            onError("Could not open this search result.")
            return
        }

        Task { @MainActor in
            let didNavigate = await reader.goToSearchResult(locator)
            if didNavigate {
                onSuccess()
            } else {
                onError("Could not open this search result.")
            }
        }
    }

    private func loadNextPage(
        onPage: @escaping (ReaderSearchPage) -> Void,
        onComplete: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) {
        guard let iterator else {
            onComplete()
            return
        }

        task?.cancel()
        task = Task { [weak self] in
            guard let self else { return }

            switch await iterator.next() {
            case let .success(collection):
                if let collection {
                    let page = ReaderSearchPage(results: collection.locators.map(self.makeResultItem))
                    await MainActor.run {
                        onPage(page)
                    }
                } else {
                    await MainActor.run {
                        onComplete()
                    }
                }

            case let .failure(error):
                await MainActor.run {
                    onError(self.message(for: error))
                }
            }
        }
    }

    private func makeResultItem(locator: Locator) -> ReaderSearchResultItem {
        ReaderSearchResultItem(
            id: locator.jsonString ?? UUID().uuidString,
            locatorJson: locator.jsonString ?? "",
            title: normalized(locator.title, fallback: "Search result"),
            before: locator.text.before ?? "",
            highlight: locator.text.highlight ?? "",
            after: locator.text.after ?? "",
            progression: locator.readingProgress,
            position: Int32(locator.locations.position ?? 0)
        )
    }

    private func message(for error: SearchError) -> String {
        switch error {
        case .publicationNotSearchable:
            return "This publication is not searchable."
        case .badQuery:
            return "This search query is not supported."
        case .reading:
            return "Could not search this publication."
        }
    }
}

private func normalized(_ value: String?, fallback: String) -> String {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return fallback
    }
    return trimmed
}
