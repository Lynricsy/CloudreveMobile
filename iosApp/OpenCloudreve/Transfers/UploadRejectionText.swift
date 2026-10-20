import Foundation
import OpenCloudreveCore

/// 上传预校验（存储策略）不通过的原因文案
enum UploadRejectionText {
    static func message(_ rejection: UploadRejection) -> String {
        switch rejection {
        case .illegalName: String(localized: "文件名包含不允许的字符")
        case .suffixNotAllowed: String(localized: "存储策略不允许上传此类型的文件")
        case .suffixDenied: String(localized: "存储策略禁止上传此类型的文件")
        case .nameNotAllowed: String(localized: "文件名不符合存储策略的命名规则")
        case .nameDenied: String(localized: "存储策略禁止此文件名")
        case .tooLarge: String(localized: "文件超过存储策略的大小限制")
        }
    }
}
