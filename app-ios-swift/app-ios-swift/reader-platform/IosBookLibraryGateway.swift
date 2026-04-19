import CryptoKit
import Foundation
import ReadiumShared
import UIKit
import shared

final class IosBookLibraryGateway: BookLibraryGateway {
    private let readium = IosReadium()
    private let store = IosBookLibraryStoreFactory().create()

    func loadBooks(
        onResult: @escaping ([BookItem]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        onResult(store.getRecentBooks())
    }

    func importBook(
        uriString: String,
        onResult: @escaping (BookItem) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task {
            do {
                let book = try await importBook(uriString: uriString)
                await MainActor.run {
                    onResult(book)
                }
            } catch {
                await MainActor.run {
                    onError(IosReaderError.message(for: error))
                }
            }
        }
    }

    func markBookOpened(
        uriString: String,
        onResult: @escaping (BookItem) -> Void,
        onError: @escaping (String) -> Void
    ) {
        guard let book = store.markBookOpened(
            uriString: uriString,
            lastOpenedAtMillis: currentTimeMillis()
        ) else {
            onError("This book is no longer in the library.")
            return
        }
        onResult(book)
    }

    private func importBook(uriString: String) async throws -> BookItem {
        let sourceURL = try IosReaderPersistence.resolveURL(uriString: uriString)
        let access = SecurityScopedURLAccess(url: sourceURL)
        defer { access.stop() }

        guard let fileURL = FileURL(url: access.url) else {
            throw IosReaderError.unsupportedURL
        }

        let asset = try await readium.assetRetriever
            .retrieve(url: fileURL, mediaType: .epub)
            .get()
        let publication = try await readium.publicationOpener
            .open(asset: asset, allowUserInteraction: true)
            .get()

        let existingBook = store.getBook(uriString: uriString)
        let id = stableId(uriString)
        let coverUriString = try await publication.coverFitting(maxSize: CGSize(width: 240, height: 320))
            .get()
            .flatMap { saveCover(bookId: id, image: $0) }
            ?? existingBook?.coverUriString
        let book = BookItem(
            id: id,
            uriString: uriString,
            title: publication.metadata.title.nonEmpty ?? fallbackTitle(sourceURL),
            author: (publication.metadata.authors.first?.name).nonEmpty ?? "Unknown author",
            coverUriString: coverUriString,
            lastOpenedAtMillis: currentTimeMillis()
        )
        store.upsertBook(book: book)
        return book
    }

    private func saveCover(bookId: String, image: UIImage) -> String? {
        guard let data = image.pngData() else {
            return nil
        }

        do {
            let directory = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("BookCovers", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

            let fileURL = directory.appendingPathComponent("\(bookId).png")
            try data.write(to: fileURL, options: .atomic)
            return "BookCovers/\(bookId).png"
        } catch {
            NSLog("Could not save EPUB cover: \(error)")
            return nil
        }
    }
}

private func fallbackTitle(_ url: URL?) -> String {
    guard let url else {
        return "Untitled book"
    }

    let title = url.deletingPathExtension().lastPathComponent
    return title.isEmpty ? "Untitled book" : title
}

private func stableId(_ value: String) -> String {
    SHA256.hash(data: Data(value.utf8))
        .map { String(format: "%02x", $0) }
        .joined()
}

private func currentTimeMillis() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}

private extension String? {
    var nonEmpty: String? {
        guard let value = self?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        return value
    }
}
