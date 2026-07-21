import Foundation
import shared

private let readerWordsAnalysisLanguage = "en"

final class IosReaderWordsGateway: ReaderWordsGateway {
    private let uriString: String

    init(uriString: String) {
        self.uriString = uriString
    }

    func loadWords(
        onResult: @escaping ([ReaderWordItem]) -> Void,
        onError: @escaping (String) -> Void
    ) {
        DispatchQueue.global(qos: .userInitiated).async {
            let store = IosBookLibraryStoreFactory().create()
            guard let book = store.getBook(
                uriString: self.uriString,
                language: readerWordsAnalysisLanguage
            ) else {
                DispatchQueue.main.async {
                    onError("Book is not in the local library yet.")
                }
                return
            }

            let items = store
                .getLemmaCounts(bookId: book.id, language: readerWordsAnalysisLanguage)
                .map { lemmaCount in
                    let surfaceWords = lemmaCount.surfaceWords
                        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                        .filter { !$0.isEmpty }
                    return ReaderWordItem(
                        lemma: lemmaCount.lemma,
                        displayWord: surfaceWords.first ?? lemmaCount.lemma,
                        totalCount: lemmaCount.totalCount,
                        surfaceWords: surfaceWords
                    )
                }

            DispatchQueue.main.async {
                onResult(items)
            }
        }
    }
}
