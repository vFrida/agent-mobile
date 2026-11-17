import PhotosUI
import SwiftUI
import UIKit

struct ChatView: View {
    @EnvironmentObject private var model: AppModel
    @State private var draft = ""
    @State private var voiceMode = false
    @State private var holding = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showActions = false
    @State private var atBottom = true
    @State private var showPhotoPicker = false
    @State private var pendingImage: UIImage?
    @State private var pendingData: Data?
    @StateObject private var speech = SpeechInput()

    var body: some View {
        VStack(spacing: 0) {
            infoBar
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if model.historyHasMore || model.loadingOlder {
                            Button {
                                Task { await model.loadOlder() }
                            } label: {
                                if model.loadingOlder {
                                    ProgressView()
                                } else {
                                    Text("加载更早消息")
                                        .font(.system(size: 12))
                                        .foregroundStyle(Palette.accentBlue)
                                }
                            }
                            .disabled(model.loadingOlder)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                        }
                        ForEach(model.lines) { line in
                            row(line).id(line.id)
                        }
                        if model.busy { ProgressView().padding(8) }
                        if model.reconnecting {
                            Text("连接断开，正在重连…")
                                .font(.caption)
                                .foregroundStyle(Palette.accentOrange)
                        }
                        if let error = model.error {
                            Text(error).font(.caption).foregroundStyle(.red)
                        }
                        Color.clear
                            .frame(height: 1)
                            .onAppear { atBottom = true }
                            .onDisappear { atBottom = false }
                    }
                    .padding(12)
                }
                .overlay(alignment: .bottomTrailing) {
                    if !atBottom {
                        Button {
                            if let last = model.lines.last {
                                withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                            }
                        } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Palette.accentBlue)
                                .frame(width: 34, height: 34)
                                .background(Palette.assistantBubble, in: Circle())
                                .shadow(color: .black.opacity(0.12), radius: 4, y: 1)
                        }
                        .padding(.trailing, 14)
                        .padding(.bottom, 10)
                    }
                }
                .background(
                    LinearGradient(
                        colors: [Palette.chatGradientTop, Palette.chatGradientBottom],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .onChange(of: model.lines.last) { _, last in
                    if let last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }

            VStack(spacing: 6) {
                if let speechError = speech.error {
                    Text(speechError)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if let image = pendingImage {
                    HStack(spacing: 8) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        Text("发送图片").font(.system(size: 13)).foregroundStyle(Palette.textBase)
                        Spacer()
                        Button("取消") {
                            pendingImage = nil
                            pendingData = nil
                        }
                        .font(.system(size: 13))
                        Button("发送") {
                            guard let data = pendingData else { return }
                            let caption = draft
                            draft = ""
                            pendingImage = nil
                            pendingData = nil
                            Task { await model.sendImage(data, caption: caption) }
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .disabled(model.busy)
                    }
                    .padding(.horizontal, 4)
                }

                HStack(alignment: .bottom, spacing: 8) {
                    Button {
                        voiceMode.toggle()
                        if !voiceMode { speech.stop() }
                    } label: {
                        Image(systemName: voiceMode ? "keyboard" : "mic.fill")
                            .font(.system(size: 17))
                            .foregroundStyle(Palette.textStrong)
                            .frame(width: 38, height: 38)
                    }

                    if voiceMode {
                        Text(speech.recording ? "松开结束" : "按住 说话")
                            .font(.system(size: 14))
                            .foregroundStyle(speech.recording ? Palette.textStrong : Palette.textBase)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(speech.recording ? Palette.textStrong : Palette.border, lineWidth: 1)
                            )
                            .contentShape(Rectangle())
                            .gesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { _ in
                                        guard !holding else { return }
                                        holding = true
                                        speech.start()
                                    }
                                    .onEnded { _ in
                                        holding = false
                                        speech.stop()
                                        if !speech.transcript.isEmpty {
                                            draft = speech.transcript
                                            voiceMode = false
                                        }
                                    }
                            )
                    } else {
                        TextField("给 agent 发消息", text: $draft, axis: .vertical)
                            .lineLimit(1...5)
                            .font(.system(size: 14))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(draft.isEmpty ? Palette.border : Palette.textStrong, lineWidth: 1)
                            )
                    }

                    Button {
                        showActions.toggle()
                        if showActions { voiceMode = false }
                    } label: {
                        Image(systemName: showActions ? "xmark.circle" : "plus.circle")
                            .font(.system(size: 22))
                            .foregroundStyle(Palette.textStrong)
                    }

                    if !voiceMode {
                        Button {
                            let text = draft
                            draft = ""
                            Task { await model.send(text) }
                        } label: {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 38, height: 38)
                                .background(Palette.sendGreen, in: Circle())
                                .opacity(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy ? 0.35 : 1)
                        }
                        .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Palette.inputBar)

            if showActions {
                actionPanel
            }

            selectorBar

        }
        .navigationTitle(model.current?.title ?? "会话")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.topBarTint, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
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
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .alert("需要确认", isPresented: .constant(model.pendingPermission != nil)) {
            Button("知道了") { model.pendingPermission = nil }
        } message: {
            Text(model.pendingPermission ?? "")
        }
        .onChange(of: speech.transcript) { _, text in
            if !text.isEmpty { draft = text }
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    pendingData = data
                    pendingImage = image
                }
                photoItem = nil
            }
        }
    }

    private var selectorBar: some View {
        HStack(spacing: 12) {
            Menu {
                ForEach(model.agents) { agent in
                    Button {
                        Task { await model.selectAgent(agent.id) }
                    } label: {
                        if agent.id == model.currentAgent {
                            Label(agent.id, systemImage: "checkmark")
                        } else {
                            Text(agent.id)
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text("模式")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.accentBlue)
                    Text(model.currentAgent == "-" ? "build" : model.currentAgent)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Palette.textStrong)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(Palette.textWeak)
                }
            }

            Spacer()

            Menu {
                ForEach(model.models, id: \.self) { item in
                    Button {
                        Task { await model.selectModel(item.ref()) }
                    } label: {
                        Text(item.name ?? item.id)
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text("模型")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.accentOrange)
                    Text(model.currentModelLabel)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Palette.textStrong)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(Palette.textWeak)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Palette.inputBar)
        .overlay(alignment: .top) {
            Rectangle().fill(Palette.border.opacity(0.6)).frame(height: 1)
        }
    }

    private var infoBar: some View {
        HStack(spacing: 6) {
            Text("目录").font(.caption2).foregroundStyle(.secondary)
            Text(model.currentDirectory.isEmpty ? "未设置" : model.currentDirectory)
                .font(.system(size: 12))
                .foregroundStyle(Palette.accentBlue)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(red: 0.961, green: 0.976, blue: 1.0))
        .overlay(alignment: .bottom) {
            Rectangle().fill(Palette.border).frame(height: 1)
        }
    }

    private func row(_ line: ChatLine) -> some View {
        Group {
            if line.role == "tool" || line.role == "error" {
                bubble(line)
                    .frame(maxWidth: .infinity, alignment: .center)
            } else {
                HStack(alignment: .top, spacing: 8) {
                    if line.role != "user" { agentAvatar }
                    bubble(line)
                        .frame(maxWidth: .infinity, alignment: line.role == "user" ? .trailing : .leading)
                    if line.role == "user" { userAvatar }
                }
            }
        }
    }

    private var actionPanel: some View {
        HStack(alignment: .top, spacing: 26) {
            actionItem("图", "相片", tint: Palette.accentBlue, enabled: true) {
                showActions = false
                showPhotoPicker = true
            }
            actionItem("音", "语音", tint: Palette.sendGreen, enabled: true) {
                showActions = false
                voiceMode = true
            }
            actionItem("断", "中断", tint: Palette.accentRed, enabled: model.busy) {
                showActions = false
                Task { await model.interrupt() }
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(Palette.inputBar)
    }

    private func actionItem(_ glyph: String, _ label: String, tint: Color, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Text(glyph)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(enabled ? Color.white : Palette.systemText)
                    .frame(width: 52, height: 52)
                    .background(enabled ? tint : Color(red: 0.92, green: 0.92, blue: 0.92),
                                in: RoundedRectangle(cornerRadius: 12))
                Text(label)
                    .font(.system(size: 11))
                    .foregroundStyle(enabled ? Palette.textBase : Palette.systemText)
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private var agentAvatar: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.30, green: 0.88, blue: 0.76),
                         Color(red: 0.49, green: 0.42, blue: 0.96)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Text(">_")
                .font(.system(size: 12, weight: .black, design: .monospaced))
                .foregroundStyle(Color(red: 0.05, green: 0.08, blue: 0.13))
        }
        .frame(width: 28, height: 28)
        .clipShape(Circle())
    }

    private var userAvatar: some View {
        ZStack {
            Circle().fill(Palette.accentBlue)
            Text("我")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: 28, height: 28)
    }

    @ViewBuilder
    private func bubble(_ line: ChatLine) -> some View {
        if let source = line.image {
            VStack(alignment: line.role == "user" ? .trailing : .leading, spacing: 6) {
                imageView(source)
                if !line.text.isEmpty {
                    Text(line.text)
                        .font(.system(size: 14))
                        .foregroundStyle(Palette.textStrong)
                }
            }
        } else {
            bubbleText(line)
        }
    }

    @ViewBuilder
    private func imageView(_ source: String) -> some View {
        if let image = Self.image(from: source) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 220, maxHeight: 260)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border, lineWidth: 1))
        } else if source.hasPrefix("http"), let url = URL(string: source) {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFit()
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: 220, maxHeight: 260)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border, lineWidth: 1))
        } else {
            Text("图片").font(.caption).foregroundStyle(Palette.textWeak)
        }
    }

    private static func image(from source: String) -> UIImage? {
        guard source.hasPrefix("data:"), let comma = source.firstIndex(of: ",") else { return nil }
        let encoded = String(source[source.index(after: comma)...])
        guard let data = Data(base64Encoded: encoded) else { return nil }
        return UIImage(data: data)
    }

    @ViewBuilder
    private func bubbleText(_ line: ChatLine) -> some View {
        switch line.role {
        case "user":
            Text(line.text)
                .font(.system(size: 14))
                .foregroundStyle(Palette.textStrong)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Palette.userBubble,
                            in: UnevenRoundedRectangle(topLeadingRadius: 10, bottomLeadingRadius: 10,
                                                       bottomTrailingRadius: 2, topTrailingRadius: 10))
        case "tool":
            ToolBubbleView(line: line)
        case "error":
            Text(line.text)
                .font(.system(size: 12))
                .foregroundStyle(Palette.critical)
                .frame(maxWidth: .infinity, alignment: .center)
        default:
            Text(line.text.isEmpty ? "思考中…" : line.text)
                .font(.system(size: 14))
                .foregroundStyle(Palette.textStrong)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Palette.assistantBubbleTint,
                            in: UnevenRoundedRectangle(topLeadingRadius: 10, bottomLeadingRadius: 2,
                                                       bottomTrailingRadius: 10, topTrailingRadius: 10))
        }
    }
}

private func toolTintColor(_ name: String?) -> Color {
    let value = (name ?? "").lowercased()
    if value.contains("write") || value.contains("edit") || value.contains("patch") { return Palette.accentOrange }
    if value.contains("read") || value.contains("grep") || value.contains("glob") { return Palette.sendGreen }
    if value.contains("web") || value.contains("fetch") { return Palette.accentPurple }
    if value.contains("todo") || value.contains("task") { return Palette.accentRed }
    return Palette.accentBlue
}

private struct ToolBubbleView: View {
    let line: ChatLine
    @State private var expanded = false

    private var tint: Color { toolTintColor(line.tool) }

    private var output: String? {
        guard let value = line.toolOutput?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else { return nil }
        return value
    }

    private var expandable: Bool {
        guard let output else { return false }
        return output.components(separatedBy: "\n").count > 4
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Circle().fill(tint).frame(width: 6, height: 6)
                Text(line.tool ?? "tool")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(tint)
            }
            if let title = line.toolTitle, !title.isEmpty {
                Text(title)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Palette.textBase)
            }
            if let output {
                Text(String(output.prefix(4000)))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Palette.textWeak)
                    .lineLimit(expanded ? nil : 4)
                    .truncationMode(.tail)
            }
            if expandable {
                Text(expanded ? "收起" : "展开")
                    .font(.system(size: 11))
                    .foregroundStyle(tint)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture {
            if expandable {
                withAnimation { expanded.toggle() }
            }
        }
    }
}
