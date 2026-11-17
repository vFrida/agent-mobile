import SwiftUI

struct SessionListView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var editing: Bool

    var body: some View {
        List {
            if let error = model.error {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
            ForEach(model.sessions) { session in
                Button {
                    Task { await model.open(session) }
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(session.title ?? session.id).font(.body)
                        Text(subtitle(session)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .overlay {
            if model.loading && model.sessions.isEmpty {
                ProgressView()
            }
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
                }
            }
        }
        .refreshable { await model.refresh() }
    }

    private func subtitle(_ session: SessionRef) -> String {
        var parts: [String] = []
        if let agent = session.agent { parts.append(agent) }
        if let model = session.model { parts.append("\(model.providerID)/\(model.id)") }
        return parts.joined(separator: " · ")
    }
}
