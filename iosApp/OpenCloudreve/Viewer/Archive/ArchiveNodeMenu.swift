import SwiftUI

/// 处理节点选择（nil = 自动分配）
struct ArchiveNodeMenu: View {
    let nodes: [ArchiveNodeOption]
    let selectedId: String?
    let onSelect: (String?) -> Void

    var body: some View {
        Menu {
            Button {
                onSelect(nil)
            } label: {
                if selectedId == nil {
                    Label("自动", systemImage: "checkmark")
                } else {
                    Text("自动")
                }
            }
            ForEach(nodes) { node in
                Button {
                    onSelect(node.id)
                } label: {
                    if node.id == selectedId {
                        Label(node.name, systemImage: "checkmark")
                    } else {
                        Text(verbatim: node.name)
                    }
                }
            }
        } label: {
            LabeledContent("节点") {
                Text(verbatim: nodes.first { $0.id == selectedId }?.name ?? String(localized: "自动"))
            }
        }
    }
}
