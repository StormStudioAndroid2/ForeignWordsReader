import SwiftUI
import shared

struct ReaderView: View {
    private let component: ReaderComponent

    @StateValue
    private var model: ReaderComponentModel
    @StateValue
    private var searchModel: SearchComponentModel
    @ObservedObject
    private var readerState: IosReaderState
    @State
    private var overlayVisible = false

    init(_ component: ReaderComponent) {
        self.component = component
        _model = StateValue(component.model)
        _searchModel = StateValue(component.search.model)
        readerState = (component as? DefaultIosReaderComponent)?.readerState ?? IosReaderState()
    }

    var body: some View {
        content
            .navigationBarTitle(readerTitle, displayMode: .inline)
            .sheet(
                isPresented: Binding(
                    get: { searchModel.isVisible },
                    set: { isPresented in
                        if isPresented {
                            component.search.onOpenRequested()
                        } else {
                            component.search.onDismissRequested()
                        }
                    }
                )
            ) {
                ReaderSearchSheet(
                    component: component.search,
                    model: searchModel
                )
            }
    }

    private var readerTitle: String {
        let trimmedTitle = model.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedTitle.isEmpty ? "EPUB Reader" : trimmedTitle
    }

    @ViewBuilder
    private var content: some View {
        switch readerState.phase {
        case .loading:
            LoadingReaderView()
        case let .ready(reader):
            ZStack {
                EpubNavigatorContainer(
                    reader: reader,
                    onCenterTap: {
                        overlayVisible.toggle()
                    }
                )
                .padding([.bottom, .top])
                ReaderChromeOverlay(
                    visible: overlayVisible && !searchModel.isVisible,
                    progress: model.readingProgress,
                    onSearchClicked: {
                        component.search.onOpenRequested()
                    },
                    onProgressSeeked: { progress in
                        Task {
                            await reader.goToProgress(progress)
                        }
                    }
                )
            }
            .ignoresSafeArea(edges: .bottom)
        case let .error(message):
            ErrorReaderView(
                message: message,
                onBackClicked: component.onBackClicked
            )
        }
    }
}

private struct ReaderChromeOverlay: View {
    let visible: Bool
    let progress: Double
    let onSearchClicked: () -> Void
    let onProgressSeeked: (Double) -> Void

    var body: some View {
        ZStack {
            if visible {
                VStack(spacing: 0) {
                    ReaderTopStripe(
                        onSearchClicked: onSearchClicked
                    )
                    Spacer()
                    ReaderBottomStripe(
                        progress: progress,
                        onProgressSeeked: onProgressSeeked
                    )
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: visible)
    }
}

private struct ReaderTopStripe: View {
    let onSearchClicked: () -> Void

    var body: some View {
        HStack {
            Button("Search", action: onSearchClicked)
                .buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground).opacity(0.94))
        .shadow(radius: 4, y: 2)
    }
}

private struct ReaderBottomStripe: View {
    let progress: Double
    let onProgressSeeked: (Double) -> Void
    @State
    private var sliderProgress = 0.0
    @State
    private var isDragging = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(Int((sliderProgress * 100).rounded()))%")
                .font(.subheadline)
            Slider(
                value: Binding(
                    get: { sliderProgress },
                    set: { value in
                        isDragging = true
                        sliderProgress = value.clampedProgress
                    }
                ),
                in: 0...1,
                onEditingChanged: { editing in
                    isDragging = editing
                    if !editing {
                        onProgressSeeked(sliderProgress.clampedProgress)
                    }
                }
            )
        }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .frame(height: 88)
            .background(Color(.systemBackground).opacity(0.94))
            .shadow(radius: 4, y: -2)
            .onAppear {
                sliderProgress = progress.clampedProgress
            }
            .onChange(of: progress) { newProgress in
                if !isDragging {
                    sliderProgress = newProgress.clampedProgress
                }
            }
    }
}

private struct LoadingReaderView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Opening EPUB...")
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ErrorReaderView: View {
    let message: String
    let onBackClicked: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(message)
                .multilineTextAlignment(.center)
            Button("Back to library", action: onBackClicked)
                .buttonStyle(.borderedProminent)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct EpubNavigatorContainer: UIViewControllerRepresentable {
    let reader: IosEpubReaderViewController
    let onCenterTap: () -> Void

    func makeUIViewController(context: Context) -> IosEpubReaderViewController {
        reader.onCenterTap = onCenterTap
        return reader
    }

    func updateUIViewController(_ uiViewController: IosEpubReaderViewController, context: Context) {
        uiViewController.onCenterTap = onCenterTap
    }
}

private struct ReaderSearchSheet: View {
    let component: SearchComponent
    let model: SearchComponentModel

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                TextField(
                    "Search in book",
                    text: Binding(
                        get: { model.query },
                        set: { component.onQueryChanged(query: $0) }
                    )
                )
                .textFieldStyle(.roundedBorder)

                Button("Search") {
                    component.onSearchSubmitted()
                }
                .buttonStyle(.borderedProminent)
            }

            HStack {
                Text(summaryText)
                    .font(.subheadline)
                    .foregroundColor(summaryColor)
                Spacer()
                if !model.query.isEmpty || !model.results.isEmpty {
                    Button("Clear") {
                        component.onClearQueryClicked()
                    }
                }
                Button("Close") {
                    component.onDismissRequested()
                }
            }

            switch model.status {
            case .idle:
                ReaderSearchEmptyState(message: "Search opens matching passages with context.")
            case .loading:
                Spacer()
                ProgressView()
                Spacer()
            case .empty:
                ReaderSearchEmptyState(message: "No matches found for \"\(model.query)\".")
            case .error:
                ReaderSearchEmptyState(message: model.errorMessage ?? "Search failed.")
            case .results:
                List {
                    ForEach(model.results, id: \.id) { item in
                        ReaderSearchResultRow(
                            item: item,
                            isSelected: item.locatorJson == model.selectedLocatorJson
                        ) {
                            component.onResultClicked(locatorJson: item.locatorJson)
                        }
                        .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
                        .listRowBackground(Color.clear)
                        .onAppear {
                            if item.id == model.results.last?.id, model.canLoadMore, !model.isLoadingMore {
                                component.onLoadNextPage()
                            }
                        }
                    }

                    if model.isLoadingMore {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                        .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
            default:
                ReaderSearchEmptyState(message: "Search is unavailable.")
            }
        }
        .padding(16)
    }

    private var summaryText: String {
        switch model.status {
        case .idle:
            return "Enter a word or phrase."
        case .loading:
            return "Searching..."
        case .results:
            return "\(model.results.count) results loaded"
        case .empty:
            return "No matches found."
        case .error:
            return model.errorMessage ?? "Search failed."
        default:
            return ""
        }
    }

    private var summaryColor: Color {
        model.status == .error ? .red : .secondary
    }
}

private struct ReaderSearchResultRow: View {
    let item: ReaderSearchResultItem
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 8) {
                Text(item.title)
                    .font(.headline)
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                (
                    Text(item.before)
                        .foregroundColor(.primary)
                    + Text(item.highlight)
                        .foregroundColor(.primary)
                        .fontWeight(.bold)
                    + Text(item.after)
                        .foregroundColor(.primary)
                )
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(searchProgressLabel(item))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(isSelected ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground))
            )
        }
        .buttonStyle(.plain)
    }
}

private struct ReaderSearchEmptyState: View {
    let message: String

    var body: some View {
        VStack {
            Spacer()
            Text(message)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

private func searchProgressLabel(_ item: ReaderSearchResultItem) -> String {
    let percent = min(max(Int(item.progression * 100), 0), 100)
    if item.position > 0 {
        return "Position \(item.position) • \(percent)%"
    } else {
        return "\(percent)%"
    }
}

extension Double {
    var clampedProgress: Double {
        min(max(self, 0), 1)
    }
}
