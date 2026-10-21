import SwiftUI
import OpenCloudreveCore

/// 详细信息内容：入口（版本、标签与元数据、直链）、基本信息与图标、目录统计、存储策略、哈希、标签、
/// 直链与分享列表、媒体元数据与自定义属性
struct FileInfoContent: View {
    let accountId: String
    let host: FileInfoHost
    /// 信息加载前先显示列表里已有的字段
    let fallback: FileItem

    @State private var failure: String?

    var body: some View {
        let state = host.state
        let file = state.file ?? fallback
        let extended = file.extendedInfo
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
            FileInfoEntriesSection(
                accountId: accountId,
                file: file,
                fallback: fallback,
                versionCount: state.versions.count,
                directLinkCount: extended?.directLinks.count ?? 0,
                onReturn: { host.send(FileInfoIntentRefresh.shared) }
            )
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
                if let emoji = file.metadata[MetadataKeys.shared.EMOJI], !emoji.isEmpty {
                    LabeledContent("图标") {
                        Text(verbatim: emoji)
                    }
                }
                if let hex = file.metadata[MetadataKeys.shared.ICON_COLOR], let color = FileIconTheme.color(hex: hex) {
                    LabeledContent("图标颜色") {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(color)
                                .frame(width: 14, height: 14)
                            Text(verbatim: hex)
                        }
                    }
                }
            }
            if file.isFolder {
                Section("文件夹统计") {
                    if state.summaryPending || (file.folderSummary == nil && state.loading) {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在统计…")
                                .foregroundStyle(.secondary)
                        }
                    }
                    if let summary = file.folderSummary {
                        LabeledContent("总大小", value: FileFormat.bytes(summary.size))
                        LabeledContent("文件", value: "\(summary.files)")
                        LabeledContent("文件夹", value: "\(summary.folders)")
                        if !state.summaryPending, let calculated = FileFormat.dateTime(summary.calculatedAt) {
                            LabeledContent("统计时间", value: calculated)
                        }
                    }
                }
            }
            if let extended {
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
                    }
                }
            }
            if !file.isFolder {
                FileInfoHashSection(hash: state.hash_, canCompute: canComputeHash(file)) { compute in
                    host.send(FileInfoIntentLoadHash(compute: compute))
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
            if let extended {
                FileInfoDirectLinksSection(links: extended.directLinks, mutating: state.mutating) { id in
                    host.send(FileInfoIntentDeleteSourceLink(id: id))
                }
                FileInfoSharesSection(shares: extended.shares)
            }
            FileInfoMetadataSections(metadata: file.metadata, customProps: state.customProps)
        }
        .refreshable { host.send(FileInfoIntentRefresh.shared) }
        .errorMessageAlert(message: $failure)
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .updated:
                    // VM 已重新拉取信息
                    break
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
    }

    /// 所在目录（可读形式）
    private func location(_ file: FileItem) -> String? {
        CrUri.companion.parseOrNull(s: file.path)?.parent().map(FileFormat.displayPath)
    }

    /// 只能为本人“我的文件”中的文件创建哈希计算任务
    private func canComputeHash(_ file: FileItem) -> Bool {
        file.owned && CrUri.companion.parseOrNull(s: file.path)?.fs == CrUri.companion.FS_MY
    }
}
