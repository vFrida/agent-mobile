import SwiftUI

@main
struct ComagicApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var editing = false

    var body: some View {
        NavigationStack {
            content
        }
        .task {
            if model.endpoint.ready { await model.refresh() }
        }
        .onOpenURL { url in
            if let endpoint = PairPayload.parse(url.absoluteString), endpoint.ready {
                model.connect(endpoint)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.current != nil {
            ChatView()
        } else if !model.endpoint.ready || editing {
            PairingView(editing: $editing)
        } else {
            SessionListView(editing: $editing)
        }
    }
}
