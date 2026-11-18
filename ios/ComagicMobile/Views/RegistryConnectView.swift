import SwiftUI

struct RegistryConnectView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            if model.registryLoading {
                ProgressView()
            }
            Text(model.registryError == nil ? "正在连接注册中心…" : "注册中心连接失败")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Palette.textStrong)
            Text(model.registryURL)
                .font(.system(size: 12))
                .foregroundStyle(Palette.textWeak)
                .lineLimit(2)
                .multilineTextAlignment(.center)
            if let error = model.registryError {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.critical)
                    .multilineTextAlignment(.center)
            }
            Button("重新发现") {
                Task { await model.connectRegistry() }
            }
            .disabled(model.registryLoading)
            .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
        .navigationTitle("Comagic")
    }
}
