import SwiftUI

struct PairingView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var editing: Bool
    var initial: Endpoint? = nil
    var onDone: (() -> Void)? = nil

    @State private var name = ""
    @State private var baseURL = ""
    @State private var username = Credentials.username
    @State private var password = Credentials.password
    @State private var directory = ""
    @State private var scanning = false
    @State private var scanError: String?

    var body: some View {
        Form {
            Section {
                Button {
                    scanning = true
                } label: {
                    Label("扫码配对", systemImage: "qrcode.viewfinder")
                }
            } footer: {
                Text("在开发机执行 scripts/pair.sh 生成二维码")
            }

            Section("连接") {
                TextField("名称（可选）", text: $name)
                TextField("http://192.168.1.10:4907", text: $baseURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("用户名", text: $username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("密码", text: $password)
                TextField("工作目录，如 /Users/txc", text: $directory)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }

            if let scanError {
                Section { Text(scanError).foregroundStyle(.red).font(.footnote) }
            }
            if let error = model.error {
                Section { Text(error).foregroundStyle(.red).font(.footnote) }
            }

            Section {
                Button("连接") { connect() }
                    .disabled(baseURL.isEmpty || password.isEmpty || model.loading)
                if model.endpoint.ready && editing {
                    Button("返回列表") { editing = false }
                }
            }
        }
        .navigationTitle("连接 Comagic")
        .onAppear(perform: load)
        .sheet(isPresented: $scanning) {
            NavigationStack {
                QRScannerView { code in
                    scanning = false
                    apply(code)
                }
                .ignoresSafeArea()
                .navigationTitle("扫描二维码")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { scanning = false }
                    }
                }
            }
        }
    }

    private func load() {
        let source = initial ?? model.endpoint
        name = source.name
        baseURL = source.baseURL
        username = source.username
        password = source.password
        directory = source.directory
    }

    private func apply(_ code: String) {
        guard let payload = PairPayload.parse(code) else {
            scanError = "二维码内容无法识别"
            return
        }
        scanError = nil
        baseURL = payload.baseURL
        username = payload.username
        if !payload.password.isEmpty { password = payload.password }
        if !payload.directory.isEmpty { directory = payload.directory }
        if !password.isEmpty {
            connect()
        }
    }

    private func connect() {
        var endpoint = initial ?? Endpoint()
        endpoint.name = name.trimmingCharacters(in: .whitespaces)
        endpoint.baseURL = baseURL.trimmingCharacters(in: .whitespaces)
        while endpoint.baseURL.hasSuffix("/") { endpoint.baseURL.removeLast() }
        endpoint.username = username
        endpoint.password = password
        endpoint.directory = directory
        model.connect(endpoint)
        editing = false
        onDone?()
        Task { await model.refresh() }
    }
}
