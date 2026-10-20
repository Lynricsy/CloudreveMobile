import Foundation
import OpenCloudreveCore

/// 设置与管理页面共用的格式化（容量、时间）
enum SettingsFormat {
    /// 字节数 → “1.2 GB”
    static func bytes(_ value: Int64) -> String {
        value.formatted(.byteCount(style: .file))
    }

    /// Kotlin `Instant` → `Date`
    static func date(_ instant: KotlinInstant?) -> Date? {
        guard let instant else { return nil }
        return Date(timeIntervalSince1970: Double(instant.toEpochMilliseconds()) / 1000)
    }

    /// 日期与时间（中等长度）；缺失返回 nil
    static func dateTime(_ instant: KotlinInstant?) -> String? {
        date(instant)?.formatted(date: .abbreviated, time: .shortened)
    }
}
