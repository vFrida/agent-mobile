import SwiftUI

struct ChatView: View {
    @EnvironmentObject private var model: AppModel
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(model.lines) { line in
                            bubble(line).id(line.id)
                        }
                        if model.busy { ProgressView().padding(8) }
                        if model.reconnecting {
                            Text("连接断开，正在重连…")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if let error = model.error {
                            Text(error).font(.caption).foregroundStyle(.red)
                        }
                    }
                    .padding(12)
                }
                .onChange(of: model.lines.count) {
                    if let last = model.lines.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }

            Divider()

            HStack(spacing: 8) {
                TextField("给 agent 发消息", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                Button {
                    let text = draft
                    draft = ""
                    Task { await model.send(text) }
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy)
            }
            .padding(10)
        }
        .navigationTitle(model.current?.title ?? "会话")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("返回") { model.close() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if model.busy {
                    Button("中断") { Task { await model.interrupt() } }
                }
            }
        }
        .alert("需要确认", isPresented: .constant(model.pendingPermission != nil)) {
            Button("知道了") { model.pendingPermission = nil }
        } message: {
            Text(model.pendingPermission ?? "")
        }
    }

    @ViewBuilder
    private func bubble(_ line: ChatLine) -> some View {
        switch line.role {
        case "user":
            Text(line.text)
                .padding(10)
                .background(Color.accentColor.opacity(0.2), in: RoundedRectangle(cornerRadius: 10))
                .frame(maxWidth: .infinity, alignment: .trailing)
        case "tool":
            Text("工具调用 · \(line.tool ?? "")")
                .font(.caption)
                .padding(8)
                .background(Color.secondary.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
        case "error":
            Text(line.text).font(.caption).foregroundStyle(.red)
        default:
            Text(line.text.isEmpty ? "思考中…" : line.text)
                .padding(10)
                .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
