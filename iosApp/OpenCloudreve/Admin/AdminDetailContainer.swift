import SwiftUI
import OpenCloudreveCore

/// 管理端详情页外壳：进入时加载详情（先用列表项占位）；挂载事件处理与“在网页后台打开”
struct AdminDetailContainer<T: AnyObject, Content: View>: View {
    let host: AdminListHost<T>
    let id: Int32
    let idOf: (T) -> Int32
    @ViewBuilder let content: (T) -> Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let item = current ?? placeholder {
                content(item)
            } else {
                ProgressView()
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                AdminWebButton(availability: host.state.handoff, opening: host.state.openingWeb) {
                    host.send(AdminListIntentOpenInWeb.shared)
                }
            }
        }
        .onAppear { host.send(AdminListIntentOpenDetail(id: id)) }
        .modifier(AdminListEffectHandling(host: host, onDeleted: {
            if !host.state.items.contains(where: { idOf($0) == id }) { dismiss() }
        }))
    }

    /// 详情接口返回的最新数据
    private var current: T? {
        host.state.detail.flatMap { idOf($0) == id ? $0 : nil }
    }

    /// 尚未拿到详情时用列表中的同一项
    private var placeholder: T? {
        host.state.items.first { idOf($0) == id }
    }
}
