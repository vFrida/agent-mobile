import SwiftUI
import UIKit

struct LoginView: View {
    @EnvironmentObject private var model: AppModel

    @State private var username = Credentials.username
    @State private var password = Credentials.password
    @State private var addingAddress = false
    @State private var addressDraft = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Comagic")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Palette.textStrong)
                Text("登录注册中心，自动发现 agents")
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.textWeak)

                Menu {
                    ForEach(model.registryURLs, id: \.self) { url in
                        Button {
                            model.selectRegistryAddress(url)
                        } label: {
                            if url == model.registryURL {
                                Label(host(of: url), systemImage: "checkmark")
                            } else {
                                Text(host(of: url))
                            }
                        }
                    }
                    Divider()
                    Button {
                        addressDraft = ""
                        addingAddress = true
                    } label: {
                        Label("添加 IP", systemImage: "plus")
                    }
                    if model.registryURLs.count > 1 {
                        Button(role: .destructive) {
                            model.removeRegistryAddress(model.registryURL)
                        } label: {
                            Label("删除当前 IP", systemImage: "trash")
                        }
                    }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("注册中心 IP")
                                .font(.system(size: 11))
                                .foregroundStyle(Palette.textWeak)
                            Text(host(of: model.registryURL))
                                .font(.system(size: 15))
                                .foregroundStyle(Palette.textStrong)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Palette.textWeak)
                    }
                    .padding(10)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(Palette.border, lineWidth: 1)
                    )
                }

                field("账号", text: $username)

                SecureField("密码", text: $password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(10)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(Palette.border, lineWidth: 1)
                    )

                if let error = model.loginError {
                    Text(error)
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.critical)
                }

                Button {
                    Task {
                        await model.login(
                            baseURL: model.registryURL,
                            username: username,
                            password: password
                        )
                    }
                } label: {
                    HStack(spacing: 6) {
                        if model.loginLoading { ProgressView().tint(.white) }
                        Text("登录")
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Palette.success, in: RoundedRectangle(cornerRadius: 10))
                    .foregroundStyle(.white)
                }
                .disabled(model.loginLoading)
            }
            .padding(24)
        }
        .background(Palette.background)
        .alert("添加注册中心 IP", isPresented: $addingAddress) {
            TextField("192.168.100.136", text: $addressDraft)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("添加") {
                model.addRegistryAddress(addressDraft)
            }
            Button("取消", role: .cancel) {}
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

    private func field(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(10)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Palette.border, lineWidth: 1)
            )
    }
}
