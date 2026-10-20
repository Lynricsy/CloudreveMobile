import SwiftUI
import OpenCloudreveCore

struct AdminHomeContent: View {
    let accountId: String
    let host: AdminSummaryHost
    @State private var failure: String?
    @State private var webPage: HandoffPage?

    var body: some View {
        List {
            if host.state.loading {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(AdminSummaryIntentRefresh.shared) }
                }
            }

            if host.state.summary != nil {
                Section {
                    AdminTotalsGrid(
                        files: Int(host.state.fileTotal),
                        users: Int(host.state.userTotal),
                        shares: Int(host.state.shareTotal),
                        entities: Int(host.state.entitiesTotal)
                    )
                    .listRowInsets(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
                } header: {
                    Text("概览")
                }

                if !host.state.daily.isEmpty {
                    Section {
                        AdminDailyChart(daily: host.state.daily)
                    } header: {
                        Text("近 12 天新增")
                    } footer: {
                        if let generated = SettingsFormat.dateTime(host.state.generatedAt) {
                            Text("统计于 \(generated)")
                        }
                    }
                }
            }

            AdminEntriesSection(accountId: accountId)

            if host.state.summary != nil {
                AdminSiteSection(version: host.state.version, commit: host.state.commit, siteUrls: host.state.siteUrls)
            }
        }
        .refreshable {
            host.send(AdminSummaryIntentRefresh.shared)
            while host.state.refreshing {
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                AdminWebButton(availability: host.state.handoff, opening: host.state.openingWeb) {
                    host.send(AdminSummaryIntentOpenInWeb.shared)
                }
            }
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .openUrl(open):
                    if let url = URL(string: open.url) { webPage = HandoffPage(url: url) }
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert(message: $failure)
        .sheet(item: $webPage) { page in
            HandoffSafariView(url: page.url)
                .ignoresSafeArea()
        }
    }
}
