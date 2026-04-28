import SwiftUI
import UIKit
import UniformTypeIdentifiers
import shared

struct MainView: View {
    private let component: MainComponent
    @StateValue
    private var model: MainComponentModel
    @State private var isEpubPickerPresented = false
    
    init(_ component: MainComponent) {
        self.component = component
        _model = StateValue(component.model)
    }
    
    var body: some View {
        VStack(spacing: 0) {
            if let errorMessage = model.errorMessage {
                Text(errorMessage)
                    .foregroundColor(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
            }

            if model.isLoading && model.books.isEmpty {
                VStack(spacing: 14) {
                    ProgressView()
                    Text("Loading books...")
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if model.books.isEmpty {
                Text("No books yet.")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(model.books, id: \.id) { book in
                            BookRow(book: book) {
                                component.onBookClicked(uriString: book.uriString)
                            }
                            Divider()
                                .padding(.horizontal, 20)
                        }
                    }
                    .padding(.vertical, 8)
                }
            }

            Divider()
            Button {
                isEpubPickerPresented = true
            } label: {
                Text("Open new book")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(16)
        }
        .navigationBarTitle("Foreign Words Reader", displayMode: .inline)
        .fileImporter(
            isPresented: $isEpubPickerPresented,
            allowedContentTypes: [.epub, .data],
            allowsMultipleSelection: false
        ) { result in
            guard
                case let .success(urls) = result,
                let url = urls.first
            else {
                return
            }

            IosReaderPersistence.saveSelectedEpub(url: url)
            component.onEpubSelected(uriString: url.absoluteString)
        }
    }
}

struct MainView_Previews: PreviewProvider {
    static var previews: some View {
        MainView(PreviewMainComponent.shared)
    }
}

private struct BookRow: View {
    let book: BookItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 16) {
                BookCover(book: book)
                VStack(alignment: .leading, spacing: 6) {
                    Text(book.title)
                        .font(.headline)
                        .foregroundColor(.primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(book.author)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: 124)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct BookCover: View {
    let book: BookItem
    @StateObject
    private var imageLoader: BookCoverImageLoader

    init(book: BookItem) {
        self.book = book
        _imageLoader = StateObject(wrappedValue: BookCoverImageLoader(book: book))
    }

    var body: some View {
        Group {
            if let image = imageLoader.image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    Color(red: 224.0 / 255.0, green: 231.0 / 255.0, blue: 239.0 / 255.0)
                    Text(book.title.initials)
                        .font(.headline)
                        .fontWeight(.semibold)
                        .foregroundColor(Color(red: 38.0 / 255.0, green: 50.0 / 255.0, blue: 56.0 / 255.0))
                }
            }
        }
        .frame(width: 72, height: 104)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .onAppear {
            imageLoader.load()
        }
    }
}

private extension String {
    var initials: String {
        let parts = split(whereSeparator: \.isWhitespace)
            .prefix(2)
            .compactMap(\.first)
            .map { String($0).uppercased() }
            .joined()
        return parts.isEmpty ? "B" : parts
    }
}

private extension UTType {
    static let epub = UTType(importedAs: "org.idpf.epub-container", conformingTo: .data)
}
