import SwiftUI

struct ServersView: View {
    @EnvironmentObject private var model: AppModel

    var onClose: () -> Void

    @State private var pendingDelete: Endpoint?

    var body: some View {
        List {
            if !model.registryServers.isEmpty {
                Text("已发现 \(model.registryServers.count) 个 agents")
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.textWeak)
            }

            if model.servers.isEmpty {
                Section {
                    Text("还没有 agents")
                        .foregroundStyle(Palette.textWeak)
                }
            }

            ForEach(model.servers) { server in
                Button {
                    Task {
                        await model.selectServer(server)
                        onClose()
                    }
                } label: {
                    row(server)
                }
                .listRowBackground(Palette.background)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button {
                        // 收起左滑操作
                    } label: {
                        Label("取消", systemImage: "xmark")
                    }
                    .tint(Palette.textBase)

                    Button(role: .destructive) {
                        pendingDelete = server
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                }
            }

        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .navigationTitle("agents")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    model.logout()
                } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
                .accessibilityLabel("退出登录")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task {
                        await model.refreshRegistry()
                        await model.refreshServerStatuses()
                    }
                } label: {
                    if model.registryLoading {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(model.registryLoading)
                .accessibilityLabel("刷新")
            }
        }
        .task {
            await model.refreshRegistry()
            await model.refreshServerStatuses()
        }
        .confirmationDialog(
            "删除 agents",
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

    private func statusRow(_ status: ServerAvailability?) -> some View {
        let resolved = status ?? .checking
        let color: Color = {
            switch resolved {
            case .checking: return Palette.textWeak
            case .online: return Palette.success
            case .offline: return Palette.critical
            }
        }()
        return HStack(spacing: 5) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
            Text(resolved.label)
                .font(.system(size: 11))
                .foregroundStyle(color)
                .lineLimit(1)
        }
    }

    private func row(_ server: Endpoint) -> some View {
        let isActive = server.id == model.endpoint.id
        let accent = Palette.sessionPalette[abs(server.id.hashValue) % Palette.sessionPalette.count]
        return HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(accent)
                Text(String(server.label.prefix(1)).uppercased())
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
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
                            .background(Palette.accentBlue.opacity(0.12), in: Capsule())
                            .foregroundStyle(Palette.accentBlue)
                    }
                }
                Text(server.agentID)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Palette.textWeak)
                    .lineLimit(1)
                statusRow(model.serverStatus[server.id])
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
