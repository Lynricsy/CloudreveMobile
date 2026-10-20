import SwiftUI
import OpenCloudreveCore

/// 过滤菜单里的单选条件（选“全部”即清除该条件）
struct AdminConditionPicker<T: AnyObject>: View {
    let host: AdminListHost<T>
    let key: String
    let title: LocalizedStringKey
    let options: [AdminOption]

    var body: some View {
        Picker(title, selection: selection) {
            Text("全部").tag("")
            ForEach(options) { option in
                Text(verbatim: option.label).tag(option.value)
            }
        }
        .pickerStyle(.menu)
    }

    private var selection: Binding<String> {
        Binding(
            get: { host.state.conditions[key] ?? "" },
            set: { host.send(AdminListIntentSetCondition(key: key, value: $0.isEmpty ? nil : $0)) }
        )
    }
}
