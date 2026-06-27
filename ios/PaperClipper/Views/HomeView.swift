import SwiftUI
import SwiftData
import UIKit

/// The clippings library. Mirrors Android's `HomeScreen`: full-width image cards grouped into date
/// sections (Google Photos style) with a status/summary scrim or a highlighted search match, an
/// always-visible search field, a sort filter, long-press multi-select + delete, a menu (Log in/out,
/// Export, Clear all, Give feedback) and a capture button that runs the capture → crop/select flow.
struct HomeView: View {
    @Environment(\.modelContext) private var context
    @Environment(AuthManager.self) private var auth
    @Query(sort: \Clipping.createdAt, order: .reverse) private var clippings: [Clipping]

    @State private var vm: ClippingsViewModel?
    @State private var path: [Clipping] = []
    @State private var query = ""
    @State private var sortDescending = true
    @State private var selection: Set<String> = []

    @State private var showCapture = false
    @State private var showMenu = false
    @State private var showClearConfirm = false
    @State private var showFeedback = false
    @State private var exportItem: IdentifiableURL?
    @State private var toast: String?

    private var inSelectionMode: Bool { !selection.isEmpty }

    /// Search filter (summary / extracted text / file name) + chosen sort. Mirrors Android's `visible`.
    private var visible: [Clipping] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = q.isEmpty ? clippings : clippings.filter {
            ($0.summary ?? "").lowercased().contains(q) ||
            ($0.extractedText ?? "").lowercased().contains(q) ||
            $0.fileName.lowercased().contains(q)
        }
        // `clippings` is already newest-first; reverse for oldest-first.
        return sortDescending ? filtered : filtered.reversed()
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle(inSelectionMode ? "\(selection.count) selected" : "Paper Clipper")
                .navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search clippings")
                .navigationDestination(for: Clipping.self) { DetailView(clipping: $0) }
                .toolbar { toolbarContent }
                .safeAreaInset(edge: .bottom) { captureBar }
                .fullScreenCover(isPresented: $showCapture) {
                    CaptureFlowView { saved in
                        showCapture = false
                        if saved { vm?.refresh() }
                    }
                }
                .sheet(isPresented: $showMenu) {
                    MenuSheet(
                        email: auth.email,
                        displayName: auth.displayName,
                        onLogin: { Task { await login() } },
                        onLogout: { auth.signOut() },
                        onExport: { export() },
                        onClearAll: { showClearConfirm = true },
                        onFeedback: { showFeedback = true }
                    )
                    .presentationDetents([.medium, .large])
                }
                .sheet(isPresented: $showFeedback) {
                    FeedbackSheet { message in
                        Task {
                            let ok = await vm?.sendFeedback(message, email: auth.email) ?? false
                            showToast(ok ? "Feedback sent — thank you!" : "Couldn't send feedback")
                        }
                    }
                }
                .sheet(item: $exportItem) { item in
                    ShareSheet(items: [item.url])
                }
                .confirmationDialog("Clear all data?", isPresented: $showClearConfirm, titleVisibility: .visible) {
                    Button("Delete everything", role: .destructive) {
                        selection = []
                        vm?.clearAll()
                    }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("This permanently deletes every clipping, tag and comment from this device. This can't be undone.")
                }
                .overlay(alignment: .bottom) { toastView }
                .task {
                    // When hosting unit tests, stay inert so the tests own the store + clippings dir.
                    guard !TestRuntime.isUnitTesting else { return }
                    if vm == nil { vm = ClippingsViewModel(context: context) }
                    #if DEBUG
                    if UITestSupport.isActive { UITestSupport.seedIfEmpty(into: context) }
                    #endif
                    auth.configureIfNeeded()
                    vm?.refresh()
                }
                .task(id: toast) {
                    guard toast != nil else { return }
                    try? await Task.sleep(for: .seconds(2))
                    toast = nil
                }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if visible.isEmpty {
            ContentUnavailableView {
                Label(clippings.isEmpty ? "No clippings yet" : "No matches", systemImage: "newspaper")
            } description: {
                Text(clippings.isEmpty
                     ? "No clippings yet — tap Take a photo below."
                     : "No clippings match your search.")
            }
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12, pinnedViews: [.sectionHeaders]) {
                    ForEach(HomeHelpers.dateSections(visible), id: \.header) { section in
                        Section {
                            ForEach(section.items) { clip in
                                ClippingCard(
                                    clipping: clip,
                                    query: query.trimmingCharacters(in: .whitespacesAndNewlines),
                                    isSelected: selection.contains(clip.fileName),
                                    inSelectionMode: inSelectionMode
                                )
                                .onTapGesture {
                                    if inSelectionMode { toggle(clip) } else { path.append(clip) }
                                }
                                .onLongPressGesture(minimumDuration: 0.4) {
                                    selection.insert(clip.fileName)
                                }
                            }
                        } header: {
                            DateHeader(text: section.header)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
        }
    }

    private var captureBar: some View {
        Button { showCapture = true } label: {
            Text("Take a photo").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .accessibilityIdentifier("takePhotoButton")
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        if inSelectionMode {
            ToolbarItem(placement: .topBarLeading) {
                Button { selection = [] } label: { Image(systemName: "xmark") }
                    .accessibilityLabel("Clear selection")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(role: .destructive) {
                    vm?.delete(fileNames: selection)
                    selection = []
                } label: { Image(systemName: "trash") }
                    .accessibilityLabel("Delete selected")
            }
        } else {
            ToolbarItem(placement: .topBarLeading) {
                Button { showMenu = true } label: { Image(systemName: "line.3.horizontal") }
                    .accessibilityLabel("Open menu")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort by date", selection: $sortDescending) {
                        Text("Date — newest first").tag(true)
                        Text("Date — oldest first").tag(false)
                    }
                } label: { Image(systemName: "slider.horizontal.3") }
                    .accessibilityLabel("Filter")
            }
        }
    }

    @ViewBuilder
    private var toastView: some View {
        if let toast {
            Text(toast)
                .font(.subheadline)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.bottom, 80)
                .transition(.opacity)
        }
    }

    // MARK: - Actions

    private func toggle(_ clip: Clipping) {
        if selection.contains(clip.fileName) { selection.remove(clip.fileName) }
        else { selection.insert(clip.fileName) }
    }

    private func login() async {
        guard auth.isConfigured, let presenter = topViewController() else {
            showToast("Sign-in isn't set up yet — add Firebase config.")
            return
        }
        if let error = await auth.signIn(presenting: presenter) {
            showToast(error)
        }
    }

    private func export() {
        if let url = vm?.writeExportToTemp() {
            exportItem = IdentifiableURL(url: url)
        } else {
            showToast("Export failed")
        }
    }

    private func showToast(_ message: String) {
        withAnimation { toast = message }
    }
}

// MARK: - Cards

/// One full-width clipping card: image with a bottom scrim showing either the status + summary or,
/// while searching, the highlighted matching excerpt. Mirrors the Android list item.
private struct ClippingCard: View {
    let clipping: Clipping
    let query: String
    let isSelected: Bool
    let inSelectionMode: Bool

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: ClippingStore.fileURL(clipping.fileName)) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Color.secondary.opacity(0.2)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 200)
            .clipped()
            .accessibilityElement()
            .accessibilityLabel("Saved clipping")

            caption
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Color.black.opacity(0.45))

            if inSelectionMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(.white)
                    .padding(8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .frame(height: 200)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var caption: some View {
        if let field = matchField {
            VStack(alignment: .leading, spacing: 2) {
                Text("Match")
                    .font(.caption).fontWeight(.medium)
                    .foregroundStyle(Color(red: 1.0, green: 0.878, blue: 0.510))
                Text(HomeHelpers.attributed(HomeHelpers.searchSnippet(field, query)))
                    .font(.caption).foregroundStyle(.white)
                    .lineLimit(3)
            }
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text(HomeHelpers.statusLabel(clipping.status))
                    .font(.caption).fontWeight(.medium).foregroundStyle(.white)
                if let text = clipping.summary ?? clipping.errorMessage, !text.isEmpty {
                    Text(text).font(.caption).foregroundStyle(.white).lineLimit(2)
                }
            }
        }
    }

    /// The field whose text matches the query (extracted text preferred, else summary), or nil.
    private var matchField: String? {
        guard !query.isEmpty else { return nil }
        if let extracted = clipping.extractedText, extracted.range(of: query, options: .caseInsensitive) != nil {
            return extracted
        }
        if let summary = clipping.summary, summary.range(of: query, options: .caseInsensitive) != nil {
            return summary
        }
        return nil
    }
}

private struct DateHeader: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline).fontWeight(.medium)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 6)
            .background(Color(.systemBackground))
    }
}

// MARK: - Menu / Feedback / Share

/// The menu (drawer equivalent): Log in / Log out + Export + Clear all + Give feedback. Mirrors
/// Android's `ModalNavigationDrawer`.
private struct MenuSheet: View {
    let email: String?
    let displayName: String?
    let onLogin: () -> Void
    let onLogout: () -> Void
    let onExport: () -> Void
    let onClearAll: () -> Void
    let onFeedback: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Paper Clipper") {
                    if let email {
                        Text("Logged in as \(displayName ?? email)")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Button("Log in") { dismiss(); onLogin() }
                    }
                }
                Section {
                    Button("Export") { dismiss(); onExport() }
                    Button("Clear all", role: .destructive) { dismiss(); onClearAll() }
                }
                Section {
                    if email != nil {
                        Button("Log out") { dismiss(); onLogout() }
                    }
                    Button("Give feedback") { dismiss(); onFeedback() }
                }
            }
            .navigationTitle("Menu")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// Multi-line feedback composer. Mirrors Android's feedback `AlertDialog`.
private struct FeedbackSheet: View {
    let onSend: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading) {
                Text("What's working, what's broken, ideas…")
                    .font(.footnote).foregroundStyle(.secondary)
                TextEditor(text: $text)
                    .frame(minHeight: 140)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.4)))
            }
            .padding()
            .navigationTitle("Give feedback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") { onSend(text); dismiss() }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

/// Wraps `UIActivityViewController` so the export ZIP can be shared/saved. Replaces Android's SAF
/// `CreateDocument` export.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct IdentifiableURL: Identifiable {
    let id = UUID()
    let url: URL
}
