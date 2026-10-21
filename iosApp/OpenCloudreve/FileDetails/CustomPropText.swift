import Foundation
import OpenCloudreveCore

/// 自定义属性的显示文案：站点默认属性名多为网页端 i18n 键（`fileManager.description`），以及本地校验错误
enum CustomPropText {
    /// 属性名：已知 i18n 键翻译，其他原样返回
    static func name(_ raw: String) -> String {
        switch raw {
        case "fileManager.description": String(localized: "描述")
        case "fileManager.rating": String(localized: "评分")
        default: raw
        }
    }

    /// `propErrors` 中的值（SKIE 在字典里保留 Kotlin 枚举类型 `__PropValidationError`）
    static func error(_ raw: __PropValidationError, definition: CustomPropDefinition?) -> String {
        error(raw.toSwiftEnum(), definition: definition)
    }

    static func error(_ error: PropValidationError, definition: CustomPropDefinition?) -> String {
        let min = Int(definition?.min ?? 0)
        let max = Int(definition?.max ?? 0)
        return switch error {
        case .tooShort: String(localized: "至少 \(min) 个字节", comment: "自定义属性过短，参数为最小字节数")
        case .tooLong: String(localized: "最多 \(max) 个字节", comment: "自定义属性过长，参数为最大字节数")
        case .notANumber: String(localized: "请输入整数")
        case .tooSmall: String(localized: "不能小于 \(min)", comment: "自定义属性数值过小，参数为最小值")
        case .tooLarge: String(localized: "不能大于 \(max)", comment: "自定义属性数值过大，参数为最大值")
        case .invalidOption: String(localized: "选项无效")
        case .notBoolean: String(localized: "值必须为是或否")
        case .unknownProp: String(localized: "站点已删除该属性")
        }
    }
}
