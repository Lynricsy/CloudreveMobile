import SwiftUI
import OpenCloudreveCore

/// 标签与元数据：标签（草稿 + 保存）、图标（emoji、文件夹颜色，立即生效）、自定义属性（单个文件，立即生效）
struct MetadataEditContent: View {
    let host: MetadataEditHost

    @State private var editingTag: TagColorTarget?
    @State private var failure: String?

    var body: some View {
        let state = host.state
        Form {
            if let error = state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(MetadataEditIntentRefresh.shared) }
                }
            } else if state.files.isEmpty {
                Section {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
            } else {
                if !state.canEdit {
                    Section {
                        Label("没有修改这些项目元数据的权限", systemImage: "lock")
                            .foregroundStyle(.secondary)
                    }
                }
                MetadataTagsSection(host: host) { editingTag = $0 }
                if state.canEdit {
                    MetadataNewTagSection(host: host)
                }
                MetadataEmojiSection(host: host)
                if state.canChangeIconColor {
                    MetadataIconColorSection(host: host)
                }
                if state.canEditProps || (state.isSingle && !state.props.isEmpty) {
                    MetadataPropsSection(host: host)
                }
            }
        }
        .refreshable { host.send(MetadataEditIntentRefresh.shared) }
        .sheet(item: $editingTag) { target in
            TagColorSheet(target: target, colors: state.presetColors) { color in
                host.send(MetadataEditIntentSetTagColor(name: target.name, color: color))
            }
        }
        .errorMessageAlert(message: $failure)
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .updated:
                    break
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
    }
}
