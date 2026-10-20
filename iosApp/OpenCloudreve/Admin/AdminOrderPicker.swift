import SwiftUI
import OpenCloudreveCore

/// 排序字段与方向（空字段表示按 ID）
struct AdminOrderPicker<T: AnyObject>: View {
    let host: AdminListHost<T>

    var body: some View {
        Picker("排序方式", selection: orderBy) {
            ForEach(host.state.orderByOptions, id: \.self) { field in
                Text(verbatim: AdminLabels.orderField(field)).tag(field)
            }
        }
        .pickerStyle(.menu)
        Picker("顺序", selection: direction) {
            Text("降序").tag("desc")
            Text("升序").tag("asc")
        }
        .pickerStyle(.menu)
    }

    private var orderBy: Binding<String> {
        Binding(
            get: { host.state.orderBy.isEmpty ? (host.state.orderByOptions.first ?? "") : host.state.orderBy },
            set: { host.send(AdminListIntentSetOrder(orderBy: $0, orderDirection: host.state.orderDirection)) }
        )
    }

    private var direction: Binding<String> {
        Binding(
            get: { host.state.orderDirection },
            set: { host.send(AdminListIntentSetOrder(orderBy: host.state.orderBy, orderDirection: $0)) }
        )
    }
}
