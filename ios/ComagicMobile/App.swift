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
    @State private var showingAgents = true

    var body: some View {
        NavigationStack {
            content
        }
        .task {
            #if DEBUG
            await model.applyDebugArguments()
            #else
            if model.endpoint.ready { await model.refresh() }
            #endif
        }
        .onOpenURL { url in
            if let endpoint = PairPayload.parse(url.absoluteString), endpoint.ready {
                model.connect(endpoint)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !model.loggedIn {
            LoginView()
        } else if model.current != nil {
            ChatView()
        } else if model.directoryPicker != nil {
            DirectoryPickerView()
        } else if showingAgents {
            ServersView(onClose: { showingAgents = false })
        } else {
            SessionListView(onAgents: { showingAgents = true })
        }
    }
}
