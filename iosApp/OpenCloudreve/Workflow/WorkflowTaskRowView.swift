import SwiftUI
import OpenCloudreveCore

/// 任务列表行：类型图标、名称、状态、源/目标、离线下载进度与速度、失败数
struct WorkflowTaskRowView: View {
    let row: WorkflowTaskRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: WorkflowText.icon(row.type))
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                if let title = WorkflowText.title(row) {
                    Text(verbatim: title)
                        .lineLimit(1)
                        .truncationMode(.middle)
                } else {
                    Text(WorkflowText.typeLabel(row.type))
                }
                subtitle
                if let destination = row.destination {
                    Text("目标：\(WorkflowText.uriName(destination))", comment: "任务行的目标目录或压缩包名称")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                if row.isActive, let progress = row.downloadProgress?.doubleValue {
                    ProgressView(value: progress)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(.primary)
        // 整行可点（行按钮为 plain 样式，只有内容形状响应点按）
        .contentShape(.rect)
    }

    /// 类型 · 状态 · 下载量/速度 · 失败数 · 多个源
    private var subtitle: some View {
        HStack(spacing: 6) {
            Text(WorkflowText.typeLabel(row.type))
                .foregroundStyle(.secondary)
            Text(WorkflowText.statusLabel(row.status))
                .foregroundStyle(WorkflowText.statusColor(row.status))
            if let download = row.download, download.total > 0 {
                Text(verbatim: "\(FileFormat.bytes(download.downloaded)) / \(FileFormat.bytes(download.total))")
                    .foregroundStyle(.secondary)
                if row.isActive && download.downloadSpeed > 0 {
                    Text(verbatim: WorkflowText.speed(download.downloadSpeed))
                        .foregroundStyle(.secondary)
                }
            }
            if row.failedCount > 0 {
                Text("\(row.failedCount) 项失败", comment: "任务中失败的条目数")
                    .foregroundStyle(.red)
            }
            if row.sources.count > 1 {
                Text("共 \(row.sources.count) 项", comment: "任务源的条目数")
                    .foregroundStyle(.secondary)
            }
        }
        .font(.footnote)
        .lineLimit(1)
    }
}
