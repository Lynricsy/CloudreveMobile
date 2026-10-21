import SwiftUI
import OpenCloudreveCore

/// 后台任务的类型/状态/分类文案与图标，以及 URI、时长的显示形式
enum WorkflowText {
    /// 任务类型名称
    static func typeLabel(_ type: String) -> LocalizedStringResource {
        switch type {
        case TaskType.shared.CREATE_ARCHIVE: "压缩"
        case TaskType.shared.EXTRACT_ARCHIVE: "解压"
        case TaskType.shared.RELOCATE: "转移存储策略"
        case TaskType.shared.REMOTE_DOWNLOAD: "离线下载"
        case TaskType.shared.IMPORT: "导入"
        case TaskType.shared.GDRIVE_BACKUP: "Google Drive 备份"
        case TaskType.shared.GDRIVE_RESTORE: "Google Drive 恢复"
        default: "后台任务"
        }
    }

    /// 任务类型图标（SF Symbols）
    static func icon(_ type: String) -> String {
        switch type {
        case TaskType.shared.CREATE_ARCHIVE: "doc.zipper"
        case TaskType.shared.EXTRACT_ARCHIVE: "arrow.up.bin"
        case TaskType.shared.RELOCATE: "arrow.left.arrow.right"
        case TaskType.shared.REMOTE_DOWNLOAD: "icloud.and.arrow.down"
        case TaskType.shared.IMPORT: "square.and.arrow.down.on.square"
        case TaskType.shared.GDRIVE_BACKUP: "externaldrive.badge.icloud"
        case TaskType.shared.GDRIVE_RESTORE: "clock.arrow.circlepath"
        default: "gearshape.2"
        }
    }

    /// 任务状态文案（UI 测试按“已完成”等待）
    static func statusLabel(_ status: String) -> LocalizedStringResource {
        switch status {
        case TaskStatus.shared.QUEUED: "排队中"
        case TaskStatus.shared.PROCESSING: "处理中"
        case TaskStatus.shared.SUSPENDING: "等待中"
        case TaskStatus.shared.ERROR: "失败"
        case TaskStatus.shared.CANCELED: "已取消"
        case TaskStatus.shared.COMPLETED: "已完成"
        default: "未知"
        }
    }

    /// 状态文字颜色
    static func statusColor(_ status: String) -> Color {
        switch status {
        case TaskStatus.shared.ERROR: .red
        case TaskStatus.shared.COMPLETED: .green
        case TaskStatus.shared.PROCESSING: .blue
        default: .secondary
        }
    }

    /// 列表分类名称
    static func categoryLabel(_ category: String) -> LocalizedStringResource {
        switch category {
        case TaskCategory.shared.DOWNLOADING: "下载中"
        case TaskCategory.shared.DOWNLOADED: "已完成下载"
        default: "任务"
        }
    }

    /// 行标题：远程下载用下载器给出的名称，其余用第一个源的名称；都没有时返回 nil（显示类型名）
    static func title(_ row: WorkflowTaskRow) -> String? {
        if let name = row.download?.name, !name.isEmpty { return name }
        guard let source = row.sources.first else { return nil }
        return uriName(source)
    }

    /// URI 的名称（根目录给出“我的文件”等）；不是 Cloudreve URI（下载链接）时原样返回
    static func uriName(_ raw: String) -> String {
        guard let uri = CrUri.companion.parseOrNull(s: raw) else { return raw }
        return FileFormat.folderTitle(uri)
    }

    /// URI 的可读完整路径；不是 Cloudreve URI 时原样返回
    static func uriPath(_ raw: String) -> String {
        guard let uri = CrUri.companion.parseOrNull(s: raw) else { return raw }
        return FileFormat.displayPath(uri)
    }

    /// 已执行时长（毫秒 → h:mm:ss / m:ss）
    static func duration(_ millis: Int64) -> String {
        let pattern: Duration.TimeFormatStyle.Pattern = millis >= 3_600_000 ? .hourMinuteSecond : .minuteSecond
        return Duration.milliseconds(millis).formatted(.time(pattern: pattern))
    }

    /// 传输速度（“1.2 MB/s”）
    static func speed(_ bytesPerSecond: Int64) -> String {
        FileFormat.bytes(bytesPerSecond) + "/s"
    }
}
