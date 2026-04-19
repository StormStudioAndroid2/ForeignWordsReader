import Combine
import Foundation
import UIKit
import shared

@MainActor
final class BookCoverImageLoader: ObservableObject {
    @Published private(set) var image: UIImage?

    private static let cache = NSCache<NSString, UIImage>()
    private let source: BookCoverSource
    private var task: Task<Void, Never>?

    init(book: BookItem) {
        self.source = BookCoverSource(book: book)
    }

    deinit {
        task?.cancel()
    }

    func load() {
        guard image == nil else { return }

        let cacheKey = source.cacheKey as NSString
        if let cachedImage = Self.cache.object(forKey: cacheKey) {
            image = cachedImage
            return
        }

        task?.cancel()
        task = Task { [weak self, source] in
            let loadedImage = await source.loadImage()
            guard let self, !Task.isCancelled else { return }

            if let loadedImage {
                Self.cache.setObject(loadedImage, forKey: cacheKey)
            }
            self.image = loadedImage
        }
    }
}

private struct BookCoverSource {
    let id: String
    let coverUriString: String?

    init(book: BookItem) {
        self.id = book.id
        self.coverUriString = book.coverUriString
    }

    var cacheKey: String {
        coverUriString ?? "BookCovers/\(id).png"
    }

    func loadImage() async -> UIImage? {
        await Task.detached(priority: .utility) {
            for url in coverURLCandidates {
                if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                    return image
                }
            }

            return nil
        }.value
    }

    private var coverURLCandidates: [URL] {
        var urls = [URL]()

        if
            let coverUriString,
            let url = URL(string: coverUriString),
            url.isFileURL
        {
            urls.append(url)
        }

        if let applicationSupportURL = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: false
        ) {
            if let coverUriString, !coverUriString.contains("://") {
                urls.append(applicationSupportURL.appendingPathComponent(coverUriString))
            }
            urls.append(applicationSupportURL.appendingPathComponent("BookCovers/\(id).png"))
        }

        return urls
    }
}
