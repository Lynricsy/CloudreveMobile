/// 自定义有效期的单位
enum ShareExpireUnit: Hashable {
    case days
    case hours

    var seconds: Int64 {
        switch self {
        case .days: 86_400
        case .hours: 3_600
        }
    }

    /// 把秒数拆成“数量 + 单位”（整天优先，其次整小时）；无法整除时返回 nil
    static func split(_ seconds: Int64) -> (unit: ShareExpireUnit, amount: Int64)? {
        guard seconds > 0 else { return nil }
        if seconds % 86_400 == 0 { return (.days, seconds / 86_400) }
        if seconds % 3_600 == 0 { return (.hours, seconds / 3_600) }
        return nil
    }
}
