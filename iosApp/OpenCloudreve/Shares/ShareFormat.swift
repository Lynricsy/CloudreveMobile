import Foundation
import OpenCloudreveCore

/// 分享列表与表单用到的格式化：显示名、过期判断、时长文案、源文件所在目录
enum ShareFormat {
    /// 分享显示名（未取到名称时用 id）；也用于无障碍标识 `share.<名称>`
    static func title(_ share: Share) -> String {
        if let name = share.name, !name.isEmpty { return name }
        return share.id
    }

    /// 已过期：服务端标记或到期时间已过
    static func isExpired(_ share: Share, now: Date = .now) -> Bool {
        if share.expired { return true }
        guard let date = FileFormat.date(share.expires) else { return false }
        return date <= now
    }

    /// 有效期时长（“5 分钟”“1 小时”“7 天”）
    static func duration(seconds: Int64) -> String {
        if seconds >= 86_400 && seconds % 86_400 == 0 {
            let days = Int(seconds / 86_400)
            return String(localized: "\(days) 天", comment: "分享有效期（天）")
        }
        if seconds >= 3_600 && seconds % 3_600 == 0 {
            let hours = Int(seconds / 3_600)
            return String(localized: "\(hours) 小时", comment: "分享有效期（小时）")
        }
        let minutes = Int((seconds + 59) / 60)
        return String(localized: "\(minutes) 分钟", comment: "分享有效期（分钟）")
    }

    /// “前往源文件”要打开的目录：目录分享为其本身，文件分享为其上级目录
    static func sourceFolder(sourceUri: String, isFolder: Bool) -> String {
        guard !isFolder, let parent = CrUri.companion.parseOrNull(s: sourceUri)?.parent() else { return sourceUri }
        return parent.description()
    }
}
