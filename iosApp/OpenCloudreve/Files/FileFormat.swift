import Foundation
import OpenCloudreveCore

/// 文件浏览器用到的格式化：大小、时间、目录标题、回收站原位置与清除倒计时
enum FileFormat {
    /// 字节数短格式（1.2 MB）。与 Web 端 `sizeToString` 一致按 1024 进位：配额通常是 2 的幂，十进制会显示成“1.1 TB”
    static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(value, 0), countStyle: .binary)
    }

    /// Kotlin `Instant` → `Date`
    static func date(_ instant: KotlinInstant?) -> Date? {
        guard let instant else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(instant.toEpochMilliseconds()) / 1000)
    }

    /// 日期时间（中等日期 + 短时间）
    static func dateTime(_ instant: KotlinInstant?) -> String? {
        date(instant)?.formatted(date: .abbreviated, time: .shortened)
    }

    /// 目录 URI 的显示名：根目录按文件系统给出“我的文件”“回收站”等
    static func folderTitle(_ uri: String) -> String {
        guard let parsed = CrUri.companion.parseOrNull(s: uri) else { return uri }
        return folderTitle(parsed)
    }

    static func folderTitle(_ uri: CrUri) -> String {
        if !uri.isRoot() { return uri.name() }
        switch uri.fs {
        case CrUri.companion.FS_MY:
            if let uid = uri.id {
                return String(localized: "用户 \(uid) 的文件", comment: "管理员查看他人文件时的根目录标题，参数为用户 id")
            }
            return String(localized: "我的文件")
        case CrUri.companion.FS_TRASH: return String(localized: "回收站")
        case CrUri.companion.FS_SHARED_WITH_ME: return String(localized: "与我共享")
        case CrUri.companion.FS_SHARE: return String(localized: "分享")
        default: return uri.fs
        }
    }

    /// 回收站项的原位置（`sys:restore_uri` 的上级目录，可读形式）
    static func restoreLocation(_ file: FileItem) -> String? {
        guard let raw = file.metadata[MetadataKeys.shared.RESTORE_URI] else { return nil }
        guard let uri = CrUri.companion.parseOrNull(s: raw) else { return raw }
        return displayPath(uri.parent() ?? uri)
    }

    /// URI 的可读完整路径（“我的文件/文档/a.txt”）
    static func displayPath(_ uri: CrUri) -> String {
        uri.isRoot() ? folderTitle(uri) : folderTitle(uri.root()) + uri.path
    }

    /// 回收站项距离自动清除的剩余时间（向上取整：刚删除的 7 天保留期显示“7 天后”）；没有该元数据返回 nil
    static func collectCountdown(_ file: FileItem, now: Date = .now) -> String? {
        guard let raw = file.metadata[MetadataKeys.shared.EXPECTED_COLLECT_TIME],
              let seconds = TimeInterval(raw) else { return nil }
        let remain = seconds - now.timeIntervalSince1970
        if remain <= 0 { return String(localized: "即将清除") }
        if remain > 86_400 {
            let days = Int((remain + 86_399) / 86_400)
            return String(localized: "\(days) 天后清除", comment: "回收站自动清除倒计时（天）")
        }
        let hours = Int((remain + 3_599) / 3_600)
        return String(localized: "\(hours) 小时后清除", comment: "回收站自动清除倒计时（小时）")
    }

    /// 回收站条目副标题：原位置 · 清除倒计时
    static func trashSubtitle(_ file: FileItem) -> String {
        [
            restoreLocation(file).map { String(localized: "原位置：\($0)", comment: "回收站条目的原始位置") },
            collectCountdown(file),
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    /// 文件扩展名（小写，不含点）；目录返回空串
    static func ext(_ file: FileItem) -> String {
        file.isFolder ? "" : (file.name as NSString).pathExtension.lowercased()
    }
}
