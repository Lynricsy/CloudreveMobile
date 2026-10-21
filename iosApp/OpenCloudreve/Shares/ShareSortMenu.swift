import SwiftUI
import OpenCloudreveCore

/// 排序菜单：排序字段（创建时间、访问次数、下载次数、剩余下载次数）与方向
struct ShareSortMenu: View {
    @Bindable var host: ShareListHost

    /// 与 `ShareListState.ORDER_BY_OPTIONS` 一致（nil 为按创建顺序）。
    /// 不直接读 `orderByOptions`：Kotlin `List<String?>` 导出到 Swift 后元素可空性不可靠。
    static let options: [String?] = [
        nil,
        ShareOrderBy.shared.VIEWS,
        ShareOrderBy.shared.DOWNLOADS,
        ShareOrderBy.shared.REMAIN_DOWNLOADS,
    ]

    var body: some View {
        Menu {
            Picker(selection: $host.sortField) {
                ForEach(Self.options, id: \.self) { option in
                    Text(Self.title(option)).tag(option)
                }
            } label: {
                Label("排序方式", systemImage: "arrow.up.arrow.down")
            }
            .pickerStyle(.inline)
            Picker(selection: $host.sortDirection) {
                Text("降序").tag(OrderDirection.shared.DESC)
                Text("升序").tag(OrderDirection.shared.ASC)
            } label: {
                Label("顺序", systemImage: "arrow.up.and.down.text.horizontal")
            }
            .pickerStyle(.inline)
        } label: {
            Label("排序", systemImage: "arrow.up.arrow.down")
        }
        .accessibilityIdentifier("shares.sort")
    }

    static func title(_ option: String?) -> LocalizedStringKey {
        switch option {
        case ShareOrderBy.shared.VIEWS: "访问次数"
        case ShareOrderBy.shared.DOWNLOADS: "下载次数"
        case ShareOrderBy.shared.REMAIN_DOWNLOADS: "剩余下载次数"
        default: "创建时间"
        }
    }
}
