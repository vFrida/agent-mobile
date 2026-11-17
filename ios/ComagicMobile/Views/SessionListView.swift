import SwiftUI

struct SessionListView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var editing: Bool
    @State private var pendingDelete: SessionRef?
    @State private var query = ""

    private var filtered: [SessionRef] {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { return model.sessions }
        return model.sessions.filter { session in
            [
                session.title,
                session.agent,
                session.model?.id,
                session.model?.providerID,
                session.location?.directory
            ]
            .compactMap { $0 }
            .contains { $0.localizedCaseInsensitiveContains(keyword) }
        }
    }

    var body: some View {
        List {
            if let error = model.error {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.footnote)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Palette.background)
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
            }

            ForEach(filtered) { session in
                row(session)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Palette.background)
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button {
                            // 收起左滑操作
                        } label: {
                            Label("取消", systemImage: "xmark")
                        }
                        .tint(Palette.textBase)

                        Button(role: .destructive) {
                            pendingDelete = session
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .searchable(
            text: $query,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: "搜索标题、目录或模型"
        )
        .overlay {
            if filtered.isEmpty {
                emptyState
            }
        }
        .confirmationDialog(
            "删除会话",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { session in
            Button("删除", role: .destructive) {
                pendingDelete = nil
                Task { await model.delete(session) }
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: { session in
            Text("确定删除「\(session.title ?? session.id)」吗？删除后无法恢复。")
        }
        .navigationTitle("会话")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await model.refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    editing = true
                } label: {
                    Image(systemName: "gearshape")
                }
            }
            ToolbarItem(placement: .bottomBar) {
                Button {
                    Task { await model.newSession() }
                } label: {
                    Label("新建会话", systemImage: "plus")
                        .font(.system(size: 14, weight: .semibold))
                }
            }
        }
        .refreshable { await model.refresh() }
    }

    private func row(_ session: SessionRef) -> some View {
        Button {
            Task { await model.open(session) }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Palette.surfaceWeak)
                    Image(systemName: "terminal")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Palette.textStrong)
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 7) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(session.title ?? session.id)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Palette.textStrong)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        Text(relativeTime(session.time?.updated ?? session.time?.created))
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.textWeak)
                    }

                    HStack(spacing: 4) {
                        Text("目录")
                            .font(.system(size: 10))
                            .foregroundStyle(Palette.textWeak)
                        Text(sessionDirectory(session))
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Palette.textBase)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }

                    HStack(spacing: 6) {
                        badge(session.agent ?? "build")
                        if let label = modelLabel(session) {
                            badge(label)
                        }
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(12)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Palette.border, lineWidth: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            if model.loading {
                ProgressView()
            } else {
                Image(systemName: query.isEmpty ? "bubble.left.and.bubble.right" : "magnifyingglass")
                    .font(.system(size: 28))
                    .foregroundStyle(Palette.textWeak)
                Text(query.isEmpty ? "还没有会话" : "没有匹配的会话")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Palette.textStrong)
                Text(query.isEmpty ? "创建一个会话开始和 agent 协作" : "换个关键词试试")
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.textBase)
                if query.isEmpty && !model.loading {
                    Button("新建会话") {
                        Task { await model.newSession() }
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .padding(.top, 4)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
    }

    private func badge(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .medium))
            .foregroundStyle(Palette.textBase)
            .lineLimit(1)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Palette.surfaceWeak, in: RoundedRectangle(cornerRadius: 5))
    }

    private func modelLabel(_ session: SessionRef) -> String? {
        guard let ref = session.model else { return nil }
        return model.models.first {
            $0.id == ref.id && (($0.providerID ?? "") == ref.providerID || ($0.providerID ?? "").isEmpty)
        }?.name ?? ref.id
    }

    private func sessionDirectory(_ session: SessionRef) -> String {
        let directory = session.location?.directory?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return directory.isEmpty ? "未设置工作目录" : directory
    }

    private func relativeTime(_ ms: Int64?) -> String {
        guard let ms, ms > 0 else { return "" }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
