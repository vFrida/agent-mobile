import SwiftUI

struct DirectoryPickerView: View {
    @EnvironmentObject private var model: AppModel
    @State private var manualPath = ""
    @State private var manualVisible = false
    @State private var query = ""

    var body: some View {
        Group {
            if let picker = model.directoryPicker {
                content(picker)
                    .safeAreaInset(edge: .bottom) { footer(picker) }
            } else {
                ProgressView()
            }
        }
        .background(Palette.background)
        .navigationTitle("选择工作目录")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("取消") { model.closeDirectoryPicker() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await model.openDirectoryPicker() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .onAppear {
            manualPath = model.directoryPicker?.current ?? ""
        }
        .onChange(of: model.directoryPicker?.current) { _, value in
            manualPath = value ?? ""
        }
    }

    @ViewBuilder
    private func content(_ picker: DirectoryPickerState) -> some View {
        VStack(spacing: 0) {
            currentCard(picker)

            if manualVisible {
                manualRow(picker)
            }

            searchRow

            if picker.loading {
                ProgressView()
                    .progressViewStyle(.linear)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
            }

            if let error = picker.error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(Palette.critical)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
            }

            if directories(picker).isEmpty && !picker.loading {
                Spacer()
                VStack(spacing: 4) {
                    Text(query.isEmpty ? "没有子目录" : "没有匹配的目录")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.textStrong)
                    Text(query.isEmpty ? "可以直接选择当前目录或手动输入路径" : "换个关键词试试")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.textBase)
                }
                Spacer()
            } else {
                List(directories(picker)) { entry in
                    Button {
                        Task { await model.loadDirectory(entry.path) }
                    } label: {
                        row(entry)
                    }
                    .buttonStyle(.plain)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Palette.background)
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private func currentCard(_ picker: DirectoryPickerState) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text("当前目录")
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.textWeak)
                Spacer()
                Text("\(directories(picker).count) 个目录")
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.textWeak)
            }

            Text(picker.current.isEmpty ? "-" : picker.current)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Palette.textStrong)
                .lineLimit(2)
                .truncationMode(.middle)

            HStack(spacing: 2) {
                Button("上一级") {
                    if let parent = parentDirectory(picker.current) {
                        Task { await model.loadDirectory(parent) }
                    }
                }
                .font(.system(size: 12))
                .disabled(parentDirectory(picker.current) == nil || picker.loading)

                Button("主目录") {
                    Task { await model.loadDirectory(picker.home) }
                }
                .font(.system(size: 12))
                .disabled(picker.home.isEmpty || picker.home == picker.current || picker.loading)

                Button(manualVisible ? "收起输入" : "手动输入") {
                    manualVisible.toggle()
                }
                .font(.system(size: 12))

                Spacer()
            }
            .buttonStyle(.borderless)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.border, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func manualRow(_ picker: DirectoryPickerState) -> some View {
        HStack(spacing: 8) {
            TextField("目录路径", text: $manualPath)
                .font(.system(size: 12, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(.horizontal, 10)
                .padding(.vertical, 9)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.border, lineWidth: 1))

            Button("前往") {
                Task { await model.loadDirectory(manualPath) }
            }
            .font(.system(size: 13, weight: .semibold))
            .disabled(manualPath.trimmingCharacters(in: .whitespaces).isEmpty || picker.loading)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var searchRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13))
                .foregroundStyle(Palette.textWeak)
            TextField("筛选子目录", text: $query)
                .font(.system(size: 13))
                .autocorrectionDisabled()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.border, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private func row(_ entry: FsEntry) -> some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Palette.surfaceWeak)
                Image(systemName: "folder")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.textBase)
            }
            .frame(width: 30, height: 30)

            VStack(alignment: .leading, spacing: 2) {
                Text(name(entry.path))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Palette.textStrong)
                    .lineLimit(1)
                Text(entry.path)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Palette.textWeak)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 6)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Palette.textWeak)
        }
        .padding(11)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 12))
    }

    private func footer(_ picker: DirectoryPickerState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text("将在此目录创建会话")
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.textWeak)
                Text(picker.current.isEmpty ? "-" : picker.current)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Palette.textBase)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Button {
                Task { await model.confirmDirectory(picker.current) }
            } label: {
                Text("选择此目录")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.textStrong)
            .disabled(picker.current.isEmpty || picker.loading || model.loading)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(Palette.surface)
        .overlay(alignment: .top) {
            Rectangle().fill(Palette.border).frame(height: 1)
        }
    }

    private func directories(_ picker: DirectoryPickerState) -> [FsEntry] {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return picker.entries
            .filter { ($0.type ?? "directory") == "directory" }
            .filter { keyword.isEmpty || $0.path.localizedCaseInsensitiveContains(keyword) }
            .sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending }
    }

    private func name(_ path: String) -> String {
        let value = (path as NSString).lastPathComponent
        return value.isEmpty ? path : value
    }

    private func parentDirectory(_ path: String) -> String? {
        var trimmed = path
        if trimmed.count > 1 && trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard trimmed != "/" else { return nil }
        let parent = (trimmed as NSString).deletingLastPathComponent
        return parent.isEmpty || parent == trimmed ? nil : parent
    }
}
