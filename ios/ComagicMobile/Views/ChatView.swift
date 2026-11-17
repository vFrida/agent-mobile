import PhotosUI
import SwiftUI
import UIKit

struct ChatView: View {
    @EnvironmentObject private var model: AppModel
    @State private var draft = ""
    @State private var voiceMode = false
    @State private var holding = false
    @State private var photoItem: PhotosPickerItem?
    @State private var pendingImage: UIImage?
    @State private var pendingData: Data?
    @StateObject private var speech = SpeechInput()

    var body: some View {
        VStack(spacing: 0) {
            infoBar
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(model.lines) { line in
                            row(line).id(line.id)
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
                .background(Palette.background)
                .onChange(of: model.lines.count) {
                    if let last = model.lines.last {
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

                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Image(systemName: "plus.circle")
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
                                .background(Palette.textStrong, in: Circle())
                                .opacity(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy ? 0.35 : 1)
                        }
                        .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.busy)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Palette.background)

            selectorBar
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
        HStack(spacing: 16) {
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
                    Text(model.currentAgent == "-" ? "模式" : model.currentAgent)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Palette.textStrong)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(Palette.textWeak)
                }
            }

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
                    Text(model.currentModelLabel)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Palette.textStrong)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(Palette.textWeak)
                }
            }

            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Palette.background)
        .overlay(alignment: .top) {
            Rectangle().fill(Palette.border).frame(height: 1)
        }
    }

    private var infoBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text("目录").font(.caption2).foregroundStyle(.secondary)
                Text(model.currentDirectory.isEmpty ? "未设置" : model.currentDirectory)
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.textStrong)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            HStack(spacing: 6) {
                Text("agent").font(.caption2).foregroundStyle(.secondary)
                Text("\(model.currentAgent)  ·  \(model.currentModelLabel)")
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.textBase)
                    .lineLimit(1)
            }
            Text(model.currentID)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Palette.background)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Palette.border).frame(height: 1)
        }
    }

    private func row(_ line: ChatLine) -> some View {
        HStack(alignment: .top, spacing: 8) {
            if line.role != "user" { agentAvatar }
            bubble(line)
                .frame(maxWidth: .infinity, alignment: line.role == "user" ? .trailing : .leading)
            if line.role == "user" { userAvatar }
        }
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
            Circle().fill(Color.accentColor.opacity(0.85))
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
                .background(Palette.surfaceWeak, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.border, lineWidth: 1))
        case "tool":
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle().fill(Palette.textWeak).frame(width: 6, height: 6)
                    Text("\(line.tool ?? "tool")")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Palette.textBase)
                }
                if let title = line.toolTitle {
                    Text(title)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Palette.textStrong)
                }
                if let output = line.toolOutput {
                    Text(String(output.prefix(1500)))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Palette.textWeak)
                        .lineLimit(12)
                        .truncationMode(.tail)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border, lineWidth: 1))
        case "error":
            Text(line.text).font(.system(size: 13)).foregroundStyle(Palette.critical)
        default:
            Text(line.text.isEmpty ? "思考中…" : line.text)
                .font(.system(size: 14))
                .foregroundStyle(Palette.textStrong)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.border, lineWidth: 1))
        }
    }
}
