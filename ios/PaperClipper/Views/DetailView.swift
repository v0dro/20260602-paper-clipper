import SwiftUI
import SwiftData

/// Full clipping view: image, AI summary + extracted text, global tags, and comments.
/// Mirrors Android's DetailScreen (+ tags/comments sections). FOUNDATION-complete.
struct DetailView: View {
    @Environment(\.modelContext) private var context
    @Bindable var clipping: Clipping
    @Query(sort: \Tag.name) private var allTags: [Tag]

    @State private var vm: ClippingsViewModel?
    @State private var newTag = ""
    @State private var newComment = ""
    @State private var showViewer = false
    @State private var showShare = false

    /// The AI heading if present, else the static "Summary" label. Mirrors Android's DetailScreen.
    private var headingTitle: String {
        if let h = clipping.heading, !h.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return h
        }
        return "Summary"
    }

    /// Share payload: the image file plus the summary (or article) text, mirroring Android's shareClipping.
    private var shareItems: [Any] {
        var items: [Any] = [ClippingStore.fileURL(clipping.fileName)]
        let summary = clipping.summary?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let article = clipping.extractedText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let text = !summary.isEmpty ? summary : article
        if !text.isEmpty { items.append(text) }
        return items
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                AsyncImage(url: ClippingStore.fileURL(clipping.fileName)) { image in
                    image.resizable().scaledToFit()
                } placeholder: {
                    Color.secondary.opacity(0.2).frame(height: 220)
                }
                .frame(maxHeight: 240)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .contentShape(Rectangle())
                .onTapGesture { showViewer = true }
                .accessibilityLabel("Clipping image (tap to zoom)")

                Text("Tap the image to view full screen and zoom")
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                switch clipping.status {
                case .pending, .processing:
                    HStack { ProgressView(); Text("Analyzing with Gemini…") }
                case .error:
                    Text("Analysis failed").font(.headline)
                    Text(clipping.errorMessage ?? "Unknown error")
                    Button("Retry") { vm?.retry(clipping) }
                case .success:
                    if let s = clipping.summary, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        // The AI heading replaces the static "Summary" label (falls back to it).
                        Text(headingTitle).font(.headline)
                        Text(s).textSelection(.enabled)
                    }
                    if let t = clipping.extractedText, !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text("Article").font(.headline)
                        Text(t).textSelection(.enabled)
                    }
                }

                Divider()
                tagsSection
                Divider()
                commentsSection
            }
            .padding()
        }
        .navigationTitle("Clipping")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showShare = true } label: { Image(systemName: "square.and.arrow.up") }
                    .accessibilityLabel("Share")
            }
        }
        .fullScreenCover(isPresented: $showViewer) {
            FullScreenImageView(imageURL: ClippingStore.fileURL(clipping.fileName)) {
                showViewer = false
            }
        }
        .sheet(isPresented: $showShare) {
            ShareSheet(items: shareItems)
        }
        .task { if vm == nil { vm = ClippingsViewModel(context: context) } }
    }

    private var tagsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tags").font(.headline)
            Text("Tags are shared across all clippings — tap to add or remove.")
                .font(.caption).foregroundStyle(.secondary)
            FlowLayout(spacing: 8) {
                ForEach(allTags) { tag in
                    let on = clipping.tags.contains { $0.name == tag.name }
                    Button { vm?.toggle(tag, on: clipping) } label: {
                        Text(tag.name)
                            .font(.subheadline)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(on ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.15))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            HStack {
                TextField("New tag", text: $newTag)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("newTagField")
                Button("Add") {
                    vm?.createAndAssignTag(named: newTag, to: clipping); newTag = ""
                }
                .accessibilityIdentifier("addTagButton")
                .disabled(newTag.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private var commentsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Comments").font(.headline)
            HStack {
                TextField("Add a comment", text: $newComment)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("newCommentField")
                Button("Add") {
                    vm?.addComment(newComment, to: clipping); newComment = ""
                }
                .accessibilityIdentifier("addCommentButton")
                .disabled(newComment.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            ForEach(clipping.comments.sorted { $0.createdAt > $1.createdAt }) { comment in
                HStack(alignment: .top) {
                    VStack(alignment: .leading) {
                        Text(comment.text)
                        Text(comment.createdAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) { vm?.deleteComment(comment) } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
    }
}

/// Minimal wrapping layout for tag chips (SwiftUI Layout, iOS 16+).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > maxWidth {
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth == .infinity ? x : maxWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
