import SwiftUI
import OpenCloudreveCore

struct ServerSetupForm: View {
    @Bindable var host: ServerSetupHost
    let onProceed: (String) -> Void
    @FocusState private var urlFocused: Bool

    var body: some View {
        Form {
            Section {
                ServerSetupHeader()
            }
            .listRowBackground(Color.clear)

            Section {
                TextField("服务器地址", text: $host.url, prompt: Text(verbatim: "cloud.example.com"))
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($urlFocused)
                    .onSubmit(submit)
                    .accessibilityIdentifier("server.url")
            } footer: {
                Text("例如 https://cloud.example.com；未填写协议时默认使用 HTTPS。")
            }

            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error, context: .serverSetup))
                }
            }

            Section {
                PrimaryActionButton(title: "继续", busy: host.state.checking, action: submit)
                    .disabled(!host.state.canSubmit)
                    .accessibilityIdentifier("server.continue")
            }
        }
        .navigationTitle("连接服务器")
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .proceed(proceed):
                    onProceed(proceed.server.siteUrl)
                }
            }
        }
    }

    private func submit() {
        urlFocused = false
        host.send(ServerSetupIntentSubmit.shared)
    }
}
