import SwiftUI
import shared

struct ReaderView: View {
    private let component: ReaderComponent

    @StateValue
    private var model: ReaderComponentModel
    @ObservedObject
    private var readerState: IosReaderState
    @State
    private var overlayVisible = false

    init(_ component: ReaderComponent) {
        self.component = component
        _model = StateValue(component.model)
        readerState = (component as? DefaultIosReaderComponent)?.readerState ?? IosReaderState()
    }

    var body: some View {
        content
            .navigationBarTitle("EPUB Reader", displayMode: .inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Back", action: component.onBackClicked)
                }
            }
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
                ReaderChromeOverlay(
                    visible: overlayVisible,
                    progress: model.readingProgress,
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
    let onProgressSeeked: (Double) -> Void

    var body: some View {
        ZStack {
            if visible {
                VStack(spacing: 0) {
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

extension Double {
    var clampedProgress: Double {
        min(max(self, 0), 1)
    }
}
