import SwiftUI
import SwiftData

/// The clippings library. Mirrors Android's HomeScreen: a list of clippings with status + summary,
/// search, a sort filter, a left menu (Log in / Export), and a capture button.
///
/// FOUNDATION: list + search + sort + capture + navigation to detail are implemented. Multi-select
/// delete and the export action are marked TODO (see ANDROID_TO_IOS.md).
struct HomeView: View {
    @Environment(\.modelContext) private var context
    @Environment(AuthManager.self) private var auth
    @Query(sort: \Clipping.createdAt, order: .reverse) private var clippings: [Clipping]

    @State private var vm: ClippingsViewModel?
    @State private var query = ""
    @State private var sortDescending = true
    @State private var showCapture = false
    @State private var showMenu = false

    private var visible: [Clipping] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = q.isEmpty ? clippings : clippings.filter {
            ($0.summary ?? "").lowercased().contains(q) ||
            ($0.extractedText ?? "").lowercased().contains(q) ||
            $0.fileName.lowercased().contains(q)
        }
        return sortDescending ? filtered : filtered.reversed()
    }

    var body: some View {
        NavigationStack {
            Group {
                if visible.isEmpty {
                    ContentUnavailableView(
                        clippings.isEmpty ? "No clippings yet" : "No matches",
                        systemImage: "newspaper",
                        description: Text(clippings.isEmpty
                            ? "Tap the camera to add one."
                            : "No clippings match your search.")
                    )
                } else {
                    List(visible) { clip in
                        NavigationLink(value: clip) {
                            ClippingRow(clipping: clip)
                        }
                        .swipeActions {
                            Button(role: .destructive) { vm?.delete(clip) } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Paper Clipper")
            .searchable(text: $query, prompt: "Search clippings")
            .navigationDestination(for: Clipping.self) { clip in
                DetailView(clipping: clip)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showMenu = true } label: { Image(systemName: "line.3.horizontal") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort by date", selection: $sortDescending) {
                            Text("Newest first").tag(true)
                            Text("Oldest first").tag(false)
                        }
                    } label: { Image(systemName: "slider.horizontal.3") }
                }
                ToolbarItem(placement: .bottomBar) {
                    Button { showCapture = true } label: {
                        Label("Take a photo", systemImage: "camera").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .sheet(isPresented: $showCapture) {
                CaptureView { savedFileName in
                    showCapture = false
                    if savedFileName != nil { vm?.refresh() }
                }
            }
            .sheet(isPresented: $showMenu) {
                MenuSheet().presentationDetents([.medium])
            }
            .task {
                if vm == nil { vm = ClippingsViewModel(context: context) }
                auth.configureIfNeeded()
                vm?.refresh()
            }
        }
    }
}

/// Drawer-equivalent menu: Log in / Log out + Export. Mirrors Android's ModalNavigationDrawer.
private struct MenuSheet: View {
    @Environment(AuthManager.self) private var auth
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section("Paper Clipper") {
                if let email = auth.email {
                    Text(email).font(.footnote).foregroundStyle(.secondary)
                    Button("Log out") { auth.signOut(); dismiss() }
                } else {
                    Button("Log in") {
                        // TODO(parity): present Google Sign-In via the top UIViewController, then
                        // `await auth.signIn(presenting:)`. Inert until GoogleService-Info.plist exists.
                        dismiss()
                    }
                }
            }
            Section {
                Button("Export") {
                    // TODO(parity): build ZIP (images + metadata.json + index.html) and present
                    // a ShareLink / Files exporter. See ANDROID_TO_IOS.md.
                    dismiss()
                }
            }
        }
    }
}

private struct ClippingRow: View {
    let clipping: Clipping

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: ClippingStore.fileURL(clipping.fileName)) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Color.secondary.opacity(0.2)
            }
            .frame(width: 64, height: 64)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 4) {
                Text(statusLabel).font(.caption).foregroundStyle(.secondary)
                Text(clipping.summary ?? clipping.errorMessage ?? "")
                    .font(.subheadline).lineLimit(2)
            }
        }
    }

    private var statusLabel: String {
        switch clipping.status {
        case .pending, .processing: return "Analyzing…"
        case .success: return "Summary"
        case .error: return "Analysis failed"
        }
    }
}
