import SwiftUI
import OpenCloudreveCore

struct PoliciesList: View {
    let host: PoliciesHost

    var body: some View {
        List {
            if host.state.loading && host.state.items.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(PoliciesIntentRefresh.shared) }
                }
            }
            if !host.state.loading && host.state.error == nil && host.state.items.isEmpty {
                Text("没有可用的存储策略")
                    .foregroundStyle(.secondary)
            }
            ForEach(host.state.items, id: \.policy.id) { item in
                Section {
                    PolicyRow(item: item)
                    ForEach(item.children, id: \.policy.id) { child in
                        PolicyWeightRow(weight: child)
                    }
                }
            }
        }
        .refreshable { host.send(PoliciesIntentRefresh.shared) }
    }
}
