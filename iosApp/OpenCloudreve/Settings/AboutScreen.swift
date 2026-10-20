import SwiftUI
import OpenCloudreveCore

/// 关于：App 版本、服务端版本、站点、开源许可
struct AboutScreen: View {
    let accountId: String
    let siteUrl: String
    @State private var serverVersion: String?
    @State private var serverVersionFailed = false

    var body: some View {
        List {
            Section {
                LabeledContent("App 版本") {
                    Text(verbatim: Self.appVersion)
                }
                LabeledContent("服务端版本") {
                    if let serverVersion {
                        Text(verbatim: serverVersion)
                    } else if serverVersionFailed {
                        Text("无法获取")
                    } else {
                        ProgressView()
                    }
                }
                .accessibilityIdentifier("about.serverVersion")
                if !siteUrl.isEmpty {
                    LabeledContent("站点") {
                        Text(verbatim: siteUrl)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
            }

            Section {
                if let url = URL(string: "https://github.com/Lynricsy/CloudreveMobile") {
                    Link(destination: url) {
                        Label("源代码", systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                }
                NavigationLink {
                    LicensesScreen()
                } label: {
                    Label("开源许可", systemImage: "doc.text")
                }
            }
        }
        .navigationTitle("关于")
        .task {
            if let version = await Self.fetchServerVersion(accountId: accountId) {
                serverVersion = version
            } else {
                serverVersionFailed = true
            }
        }
    }

    private static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    private nonisolated static func fetchServerVersion(accountId: String) async -> String? {
        try? await CoreGraph.shared.webHandoff().serverVersion(accountId: accountId, forceRefresh: true)
    }
}
