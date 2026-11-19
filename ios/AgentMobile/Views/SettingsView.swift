import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    var onClose: () -> Void

    var body: some View {
        List {
            Section {
                LabeledContent("注册中心 IP", value: host(of: model.registryURL))
                LabeledContent("账号", value: model.account?.username ?? "")
            }

            Section("会话配置") {
                Toggle(isOn: Binding(
                    get: { model.showHiddenDirs },
                    set: { model.setShowHiddenDirs($0) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("显示隐藏目录")
                        Text("新建会话选择工作目录时显示以 . 开头的目录")
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.textWeak)
                    }
                }
            }

            if !model.hiddenAgentIDs.isEmpty {
                Section {
                    Button {
                        Task { await model.restoreHiddenAgents() }
                    } label: {
                        Text("恢复已删除的 agents（\(model.hiddenAgentIDs.count)）")
                    }
                }
            }

            Section {
                Button(role: .destructive) {
                    model.logout()
                } label: {
                    Text("退出登录")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    onClose()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                }
                .accessibilityLabel("返回")
            }
        }
    }

    private func host(of url: String) -> String {
        var value = url
        if let range = value.range(of: "://") {
            value = String(value[range.upperBound...])
        }
        value = value.components(separatedBy: "/")[0]
        value = value.components(separatedBy: ":")[0]
        return value.isEmpty ? url : value
    }
}
