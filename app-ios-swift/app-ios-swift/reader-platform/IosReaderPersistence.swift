import Foundation
import ReadiumShared

private let preferencesName = "readium_reader"
private let lastEpubUriKey = "last_epub_uri"

enum IosReaderPersistence {
    static func saveSelectedEpub(url: URL) {
        let uriString = url.absoluteString
        storeBookmark(for: url, uriString: uriString)
    }

    static func lastReadableEpubUriString() -> String? {
        guard let uriString = defaults.string(forKey: lastEpubUriKey) else {
            return nil
        }

        return (try? resolveURL(uriString: uriString)).map { _ in uriString }
    }

    static func saveLastEpub(uriString: String) {
        defaults.set(uriString, forKey: lastEpubUriKey)
    }

    static func saveLocator(_ locatorJson: String, uriString: String) {
        defaults.set(locatorJson, forKey: locatorKey(uriString))
    }

    static func restoreLocator(uriString: String) -> Locator? {
        guard let locatorJson = defaults.string(forKey: locatorKey(uriString)) else {
            return nil
        }

        return try? Locator(jsonString: locatorJson)
    }

    static func resolveURL(uriString: String) throws -> URL {
        if let bookmark = defaults.data(forKey: bookmarkKey(uriString)) {
            var isStale = false
            let url = try URL(
                resolvingBookmarkData: bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
            if isStale {
                storeBookmark(for: url, uriString: uriString)
            }
            return url
        }

        guard let url = URL(string: uriString) else {
            throw IosReaderError.invalidURL
        }

        return url
    }

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: preferencesName) ?? .standard
    }

    private static func storeBookmark(for url: URL, uriString: String) {
        guard url.isFileURL else { return }

        do {
            let bookmark = try url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            defaults.set(bookmark, forKey: bookmarkKey(uriString))
        } catch {
            NSLog("Could not store EPUB security bookmark: \(error)")
        }
    }

    private static func bookmarkKey(_ uriString: String) -> String {
        "bookmark:\(uriString)"
    }

    private static func locatorKey(_ uriString: String) -> String {
        "locator:\(uriString)"
    }
}

enum IosReaderError: Error {
    case invalidURL
    case unsupportedURL

    static func message(for error: Error) -> String {
        switch error {
        case invalidURL:
            return "The EPUB file URL is invalid."
        case unsupportedURL:
            return "This EPUB file location is not supported."
        default:
            return error.localizedDescription.isEmpty
                ? "Could not open this EPUB."
                : error.localizedDescription
        }
    }
}

final class SecurityScopedURLAccess {
    let url: URL
    private var isAccessing: Bool

    init(url: URL) {
        self.url = url
        self.isAccessing = url.startAccessingSecurityScopedResource()
    }

    deinit {
        stop()
    }

    func stop() {
        guard isAccessing else { return }
        url.stopAccessingSecurityScopedResource()
        isAccessing = false
    }
}
