import SwiftUI

struct ServersView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var editor: EditorTarget?
    @State private var pendingDelete: Endpoint?

    private struct EditorTarget: Identifiable {
        let id: String
        let server: Endpoint
    }

    var body: some View {
        NavigationStack {
            List {
                if model.servers.isEmpty {
                    Section {
                        Text("还没有服务器")
                            .foregroundStyle(Palette.textWeak)
                    }
                }

                ForEach(model.servers) { server in
                    Button {
                        Task {
                            await model.selectServer(server)
                            dismiss()
                        }
                    } label: {
                        row(server)
                    }
                    .listRowBackground(Palette.background)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            pendingDelete = server
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                        Button {
                            editor = EditorTarget(id: server.id, server: server)
                        } label: {
                            Label("编辑", systemImage: "pencil")
                        }
                        .tint(Palette.textBase)
                    }
                }

                Section {
                    Button {
                        editor = EditorTarget(id: "new", server: Endpoint())
                    } label: {
                        Label("添加服务器", systemImage: "plus")
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .navigationTitle("服务器")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .sheet(item: $editor) { target in
                NavigationStack {
                    PairingView(editing: .constant(true), initial: target.server, onDone: {
                        editor = nil
                    })
                }
            }
            .confirmationDialog(
                "删除服务器",
                isPresented: Binding(
                    get: { pendingDelete != nil },
                    set: { if !$0 { pendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { server in
                Button("删除", role: .destructive) {
                    pendingDelete = nil
                    Task { await model.deleteServer(server) }
                }
                Button("取消", role: .cancel) { pendingDelete = nil }
            } message: { server in
                Text("确定删除「\(server.label)」吗？会话仍保留在对应机器上。")
            }
        }
    }

    private func row(_ server: Endpoint) -> some View {
        let isActive = server.id == model.endpoint.id
        return HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Palette.surfaceWeak)
                Image(systemName: "server.rack")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.textStrong)
            }
            .frame(width: 38, height: 38)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(server.label)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.textStrong)
                        .lineLimit(1)
                    if isActive {
                        Text("当前")
                            .font(.system(size: 10, weight: .semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Palette.surfaceWeak, in: Capsule())
                            .foregroundStyle(Palette.textWeak)
                    }
                }
                Text(server.baseURL.isEmpty ? "未填写地址" : server.baseURL)
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.textWeak)
                    .lineLimit(1)
                Text(server.directory.isEmpty ? "未设置工作目录" : server.directory)
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.textWeak)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            if isActive {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Palette.success)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}
