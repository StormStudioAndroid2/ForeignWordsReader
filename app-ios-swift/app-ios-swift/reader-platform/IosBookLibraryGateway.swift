import CryptoKit
import Foundation
import ReadiumShared
import UIKit
import shared

private let analysisLanguage = "en"
private let analysisProvider = "udpipe"
private let analysisModelId = "english-ewt"
private let analysisModelVersion = "ud-2.5-191206"
private let analysisIndexVersion: Int64 = 3
private let preprocessingPipelineFingerprint = "udpipe-analysis@1|build-lemma-index@1|persist-book-index@1"
private let debugLemmaExportLimit = 1_000

final class IosBookLibraryGateway: BookLibraryGateway {
    private let readium = IosReadium()
    private let store = IosBookLibraryStoreFactory().create()

    func loadBooks(
        onResult: @escaping ([BookItem]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        onResult(store.getRecentBooks(language: analysisLanguage))
    }

    func importBook(
        uriString: String,
        onResult: @escaping (BookItem) -> Void,
        onProcessingChanged: @escaping (BookItem) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task {
            do {
                let importedBook = try await importBook(uriString: uriString)
                let book = importedBook.book
                await MainActor.run {
                    onResult(book)
                }

                _ = IosBookProcessingRunner(store: store).process(
                    book: book,
                    sections: importedBook.sections
                )
                #if DEBUG
                exportTopLemmasForDebug(book: book)
                #endif
                let updatedBook = store.getBook(uriString: uriString, language: analysisLanguage) ?? book
                await MainActor.run {
                    onProcessingChanged(updatedBook)
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
        let shouldProcess = !store.hasCurrentBookIndex(
            bookId: book.id,
            language: analysisLanguage,
            nlpProvider: analysisProvider,
            modelVersion: analysisModelVersion,
            indexVersion: analysisIndexVersion,
            pipelineFingerprint: preprocessingPipelineFingerprint
        )
        onResult(book)
        guard shouldProcess else {
            return
        }

        Task {
            do {
                let importedBook = try await importBook(uriString: uriString)
                _ = IosBookProcessingRunner(store: store).process(
                    book: importedBook.book,
                    sections: importedBook.sections
                )
                #if DEBUG
                exportTopLemmasForDebug(book: importedBook.book)
                #endif
            } catch {
                NSLog("Could not refresh stale book analysis: \(error)")
            }
        }
    }

    private func importBook(uriString: String) async throws -> ImportedBook {
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
        let text = await publication.content()?.text(separator: "\n\n") ?? ""

        let existingBook = store.getBook(uriString: uriString, language: analysisLanguage)
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
            lastOpenedAtMillis: currentTimeMillis(),
            processingState: BookProcessingState.notstarted,
            processingTokenCount: 0,
            processingErrorMessage: nil
        )
        store.upsertBook(book: book)
        store.upsertProcessingStatus(
            status: BookProcessingStatus(
                bookId: id,
                language: analysisLanguage,
                nlpProvider: analysisProvider,
                udpipeVersion: "",
                modelId: analysisModelId,
                modelVersion: analysisModelVersion,
                indexVersion: analysisIndexVersion,
                pipelineFingerprint: preprocessingPipelineFingerprint,
                state: BookProcessingState.processing,
                tokenCount: 0,
                uniqueLemmaCount: 0,
                savedIndexSizeBytes: 0,
                processedAtMillis: nil,
                errorMessage: nil
            )
        )
        return ImportedBook(
            book: store.getBook(uriString: uriString, language: analysisLanguage) ?? book,
            sections: text.toTextSections()
        )
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

    #if DEBUG
    private func exportTopLemmasForDebug(book: BookItem) {
        let lemmas = store.getLemmaCounts(bookId: book.id, language: analysisLanguage)
        guard !lemmas.isEmpty else {
            return
        }

        do {
            let directory = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("DebugLemmaIndex", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

            let tfFileURL = directory.appendingPathComponent("\(book.id)-top-lemmas.txt")
            var tfOutput = """
            bookId=\(book.id)
            title=\(book.title)
            metric=tf
            limit=\(debugLemmaExportLimit)
            columns=rank lemma total_count

            """
            let tfLemmas = lemmas
                .sorted {
                    if $0.totalCount != $1.totalCount {
                        return $0.totalCount > $1.totalCount
                    }
                    return $0.lemma < $1.lemma
                }
                .prefix(debugLemmaExportLimit)
            for (index, lemma) in tfLemmas.enumerated() {
                tfOutput += "\(index + 1)\t\(lemma.lemma)\t\(lemma.totalCount)\n"
            }
            try tfOutput.write(to: tfFileURL, atomically: true, encoding: .utf8)

            let tfIdfFileURL = directory.appendingPathComponent("\(book.id)-top-lemmas-tfidf.txt")
            var tfIdfOutput = """
            bookId=\(book.id)
            title=\(book.title)
            metric=tf_idf
            limit=\(debugLemmaExportLimit)
            columns=rank lemma tf_idf_score total_count global_frequency_zipf

            """
            let tfIdfLemmas = lemmas
                .sorted {
                    if $0.tfIdfScore != $1.tfIdfScore {
                        return $0.tfIdfScore > $1.tfIdfScore
                    }
                    if $0.totalCount != $1.totalCount {
                        return $0.totalCount > $1.totalCount
                    }
                    return $0.lemma < $1.lemma
                }
                .prefix(debugLemmaExportLimit)
            for (index, lemma) in tfIdfLemmas.enumerated() {
                let zipf = lemma.globalFrequencyZipf
                    .map { String(format: "%.6f", $0.doubleValue) }
                    ?? ""
                tfIdfOutput += "\(index + 1)\t\(lemma.lemma)\t\(String(format: "%.6f", lemma.tfIdfScore))\t\(lemma.totalCount)\t\(zipf)\n"
            }
            try tfIdfOutput.write(to: tfIdfFileURL, atomically: true, encoding: .utf8)
        } catch {
            NSLog("Could not export debug lemma index: \(error)")
        }
    }
    #endif
}

private struct ImportedBook {
    let book: BookItem
    let sections: [TextSection]
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

private extension String {
    func toTextSections() -> [TextSection] {
        let sections = components(separatedBy: "\n\n")
            .enumerated()
            .compactMap { index, value -> TextSection? in
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else {
                    return nil
                }
                return TextSection(sectionId: "section-\(index)", text: trimmed)
            }
        if !sections.isEmpty {
            return sections
        }

        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? [] : [TextSection(sectionId: "section-0", text: trimmed)]
    }
}
