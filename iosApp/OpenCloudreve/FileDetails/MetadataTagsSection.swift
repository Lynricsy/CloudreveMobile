import SwiftUI
import OpenCloudreveCore

/// 标签草稿：颜色圆点 + 名称，点按改颜色，左滑删除；有改动时“保存标签”/“还原”
struct MetadataTagsSection: View {
    let host: MetadataEditHost
    /// 点按标签：由外层呈现颜色选择
    let editColor: (TagColorTarget) -> Void

    var body: some View {
        let state = host.state
        let tags = state.tags
        Section {
            if tags.isEmpty {
                Text("暂无标签")
                    .foregroundStyle(.secondary)
            }
            ForEach(tags, id: \.name) { tag in
                Button {
                    editColor(TagColorTarget(name: tag.name, color: tag.color))
                } label: {
                    Label {
                        Text(verbatim: tag.name)
                            .foregroundStyle(Color.primary)
                    } icon: {
                        tagDot(tag.color)
                    }
                }
                .disabled(!state.canEdit)
                .accessibilityIdentifier("metadata.tag.\(tag.name)")
            }
            .onDelete { offsets in
                for name in offsets.map({ tags[$0].name }) {
                    host.send(MetadataEditIntentRemoveTag(name: name))
                }
            }
            .deleteDisabled(!state.canEdit)
            if state.tagsDirty {
                Button("还原") { host.send(MetadataEditIntentResetTags.shared) }
            }
        } header: {
            Text("标签")
        } footer: {
            if state.tagsDirty {
                Text("标签修改需要保存后才会生效。")
            } else if !state.isSingle {
                Text("显示所选文件的全部标签，保存时统一应用到每个文件。")
            }
        }
        if state.tagsDirty {
            Section {
                PrimaryActionButton(title: "保存标签", busy: state.saving) {
                    host.send(MetadataEditIntentSaveTags.shared)
                }
                .disabled(state.saving)
                .accessibilityIdentifier("metadata.saveTags")
            }
        }
    }

    private func tagDot(_ hex: String?) -> some View {
        Group {
            if let color = FileIconTheme.color(hex: hex) {
                Circle().fill(color)
            } else {
                Circle().strokeBorder(.secondary, lineWidth: 1.5)
            }
        }
        .frame(width: 14, height: 14)
    }
}
