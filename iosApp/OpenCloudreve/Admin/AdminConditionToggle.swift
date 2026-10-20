import SwiftUI
import OpenCloudreveCore

/// 过滤菜单里的开关条件（开启时条件值为 `true`）
struct AdminConditionToggle<T: AnyObject>: View {
    let host: AdminListHost<T>
    let key: String
    let title: LocalizedStringKey

    var body: some View {
        Toggle(title, isOn: isOn)
    }

    private var isOn: Binding<Bool> {
        Binding(
            get: { host.state.conditions[key] == "true" },
            set: { host.send(AdminListIntentSetCondition(key: key, value: $0 ? "true" : nil)) }
        )
    }
}
