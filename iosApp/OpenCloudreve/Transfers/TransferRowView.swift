import SwiftUI
import OpenCloudreveCore

/// 传输列表的一行：名称、进度条、状态说明，右侧为主操作（暂停/继续/重试/分享已下载文件）；
/// 其余操作在滑动菜单与上下文菜单中。
struct TransferRowView: View {
    let row: TransferRow
    let busy: Bool
    let send: (any TransferListIntent) -> Void
    /// 40004 冲突：改名后重试（弹输入框）
    let rename: () -> Void

    var body: some View {
        let item = row.item
        HStack(spacing: 12) {
            Image(systemName: item.kind == .upload ? "arrow.up.doc" : "arrow.down.doc")
                .font(.title2)
                .foregroundStyle(iconStyle(item.state))
                .frame(width: 32)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: item.displayName)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .accessibilityIdentifier("transfer." + item.displayName)
                if showsProgress(item.state) {
                    ProgressView(value: min(max(item.progress, 0), 1))
                }
                Text(verbatim: TransferText.detail(row))
                    .font(.caption)
                    .foregroundStyle(item.state == .failed ? Color.red : Color.secondary)
                    .lineLimit(2)
                    .accessibilityIdentifier("transfer.detail." + item.displayName)
            }
            Spacer(minLength: 0)
            primaryAction
        }
        .padding(.vertical, 2)
        .swipeActions(edge: .trailing) {
            if row.canCancel {
                Button("取消", systemImage: "xmark", role: .destructive) { send(TransferListIntentCancel(id: row.id)) }
            } else {
                Button("移除", systemImage: "trash", role: .destructive) { send(TransferListIntentRemove(id: row.id)) }
            }
        }
        .contextMenu { menu }
    }

    @ViewBuilder
    private var primaryAction: some View {
        if busy {
            ProgressView()
        } else if row.canPause {
            Button("暂停", systemImage: "pause.circle") { send(TransferListIntentPause(id: row.id)) }
                .labelStyle(.iconOnly)
                .font(.title2)
                .buttonStyle(.borderless)
        } else if row.canResume {
            Button("继续", systemImage: "play.circle") { send(TransferListIntentResume(id: row.id)) }
                .labelStyle(.iconOnly)
                .font(.title2)
                .buttonStyle(.borderless)
        } else if row.canRetry {
            Button("重试", systemImage: "arrow.clockwise.circle") { send(TransferListIntentRetry(id: row.id)) }
                .labelStyle(.iconOnly)
                .font(.title2)
                .buttonStyle(.borderless)
        } else if let url = completedFile {
            ShareLink(item: url) {
                Label("导出", systemImage: "square.and.arrow.up")
                    .labelStyle(.iconOnly)
                    .font(.title2)
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier("transfer.share." + row.item.displayName)
        }
    }

    @ViewBuilder
    private var menu: some View {
        if row.canPause {
            Button("暂停", systemImage: "pause") { send(TransferListIntentPause(id: row.id)) }
        }
        if row.canResume {
            Button("继续", systemImage: "play") { send(TransferListIntentResume(id: row.id)) }
        }
        if row.canRetry {
            Button("重试", systemImage: "arrow.clockwise") { send(TransferListIntentRetry(id: row.id)) }
        }
        if row.needsConflictResolution {
            Button("重命名后上传", systemImage: "pencil") { rename() }
            Button("覆盖为新版本", systemImage: "square.on.square") { send(TransferListIntentRetryWithOverwrite(id: row.id)) }
        }
        if let url = completedFile {
            ShareLink(item: url) {
                Label("导出或分享", systemImage: "square.and.arrow.up")
            }
        }
        if row.canCancel {
            Button("取消", systemImage: "xmark", role: .destructive) { send(TransferListIntentCancel(id: row.id)) }
        }
        Button("从列表移除", systemImage: "trash", role: .destructive) { send(TransferListIntentRemove(id: row.id)) }
    }

    /// 已完成下载的本地文件（文件已被删除时为 nil）
    private var completedFile: URL? {
        let item = row.item
        guard item.kind == .download, item.state == .completed, let path = item.destPath else { return nil }
        let url = DownloadDestination.resolve(path)
        return FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) ? url : nil
    }

    private func showsProgress(_ state: TransferState) -> Bool {
        switch state {
        case .queued, .preparing, .running, .finishing, .paused: true
        default: false
        }
    }

    private func iconStyle(_ state: TransferState) -> Color {
        switch state {
        case .failed: .red
        case .completed: .green
        case .canceled, .paused: .secondary
        default: .accentColor
        }
    }
}
