import SwiftUI
import OpenCloudreveCore

/// 自定义属性（仅单个文件）：已设置的属性按类型编辑，“添加属性”菜单列出尚未添加的定义
struct MetadataPropsSection: View {
    let host: MetadataEditHost

    var body: some View {
        let state = host.state
        Section {
            if state.props.isEmpty {
                Text("尚未设置自定义属性")
                    .foregroundStyle(.secondary)
            }
            ForEach(state.props, id: \.id) { entry in
                MetadataPropEditor(entry: entry, host: host)
            }
            if state.canEditProps {
                Menu {
                    ForEach(state.availableProps, id: \.id) { definition in
                        Button {
                            host.send(MetadataEditIntentAddProp(id: definition.id))
                        } label: {
                            Text(verbatim: CustomPropText.name(definition.name))
                        }
                    }
                } label: {
                    Label("添加属性", systemImage: "plus")
                }
                .disabled(state.availableProps.isEmpty || state.saving)
                .accessibilityIdentifier("metadata.addProp")
            }
        } header: {
            Text("自定义属性")
        }
    }
}
