import SwiftUI
import OpenCloudreveCore

/// 详细信息内容：基本信息、目录统计、存储策略、分享/直链/版本数量、标签
struct FileInfoContent: View {
    let host: FileInfoHost
    /// 信息加载前先显示列表里已有的字段
    let fallback: FileItem

    var body: some View {
        let state = host.state
        let file = state.file ?? fallback
        List {
            Section {
                HStack(spacing: 12) {
                    FileTypeIconView(file: file, size: 44)
                    Text(verbatim: file.name)
                        .font(.headline)
                        .textSelection(.enabled)
                }
            }
            if let error = state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(FileInfoIntentRefresh.shared) }
                }
            }
            Section("基本信息") {
                LabeledContent("类型", value: file.isFolder ? String(localized: "文件夹") : String(localized: "文件"))
                if !file.isFolder && !file.isSymbolic {
                    LabeledContent("大小", value: FileFormat.bytes(file.size))
                }
                if let location = location(file) {
                    LabeledContent("位置", value: location)
                }
                if let created = FileFormat.dateTime(file.createdAt) {
                    LabeledContent("创建时间", value: created)
                }
                if let updated = FileFormat.dateTime(file.updatedAt) {
                    LabeledContent("修改时间", value: updated)
                }
            }
            if file.isFolder {
                Section("文件夹统计") {
                    if let summary = file.folderSummary {
                        LabeledContent("总大小", value: FileFormat.bytes(summary.size))
                        LabeledContent("文件", value: "\(summary.files)")
                        LabeledContent("文件夹", value: "\(summary.folders)")
                        if state.summaryPending {
                            Label("正在统计…", systemImage: "hourglass")
                                .foregroundStyle(.secondary)
                        }
                    } else if state.loading {
                        ProgressView()
                    }
                }
            }
            if let extended = file.extendedInfo {
                Section("存储") {
                    if let policy = extended.storagePolicy {
                        LabeledContent("存储策略") {
                            Text(verbatim: policy.name)
                        }
                        if file.isFolder && extended.storagePolicyInherited {
                            Text("继承自上级文件夹")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if !file.isFolder {
                        LabeledContent("占用空间", value: FileFormat.bytes(extended.storageUsed))
                        LabeledContent("版本", value: "\(state.versions.count)")
                    }
                    LabeledContent("分享", value: "\(extended.shares.count)")
                    LabeledContent("直链", value: "\(extended.directLinks.count)")
                }
            }
            let tags = state.tags.keys.sorted()
            if !tags.isEmpty {
                Section("标签") {
                    ForEach(tags, id: \.self) { tag in
                        Label {
                            Text(verbatim: tag)
                        } icon: {
                            Image(systemName: "tag.fill")
                                .foregroundStyle(FileIconTheme.color(hex: state.tags[tag]) ?? .secondary)
                        }
                    }
                }
            }
        }
        .refreshable { host.send(FileInfoIntentRefresh.shared) }
    }

    /// 所在目录（可读形式）
    private func location(_ file: FileItem) -> String? {
        CrUri.companion.parseOrNull(s: file.path)?.parent().map(FileFormat.displayPath)
    }
}
