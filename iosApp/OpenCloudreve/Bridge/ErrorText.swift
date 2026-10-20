import Foundation
import OpenCloudreveCore

/// `UiError` → 本地化文案（各页面共用）。先按具体错误码，再按错误类别兜底。
enum ErrorText {
    /// 同一错误码在不同页面含义不同时用于区分
    enum Context {
        case general
        /// 服务器地址页：`Validation` 表示地址格式不对
        case serverSetup
        /// 登录页邮箱阶段：`404` 表示邮箱未注册
        case loginEmail
    }

    static func message(_ error: UiError, context: Context = .general) -> String {
        let code = error.code.map { Int($0.int32Value) }
        switch context {
        case .serverSetup where error.kind == .validation:
            return String(localized: "请输入有效的服务器地址")
        case .loginEmail where code == 404:
            return String(localized: "该邮箱尚未注册")
        default:
            break
        }
        if let text = codeMessage(code) { return text }
        return kindMessage(error)
    }

    /// 服务端错误码（`core.api.ErrorCode`）
    private static func codeMessage(_ code: Int?) -> String? {
        switch code {
        case 40017: String(localized: "该账户已被封禁")
        case 40018: String(localized: "该账户尚未激活，请先点击邮件中的激活链接")
        case 40022: String(localized: "两步验证码不正确")
        case 40025: String(localized: "Passkey 验证失败")
        case 40026: String(localized: "验证码不正确")
        case 40032: String(localized: "该邮箱已被注册")
        case 40080: String(localized: "邮箱或密码不正确")
        default: nil
        }
    }

    private static func kindMessage(_ error: UiError) -> String {
        switch error.kind {
        case .network: return String(localized: "无法连接到服务器，请检查网络和服务器地址")
        case .badResponse: return String(localized: "服务器返回了无法识别的响应，请确认这是 OpenCloudreve 站点")
        case .unauthorized, .sessionExpired: return String(localized: "登录已过期，请重新登录")
        case .notFound: return String(localized: "请求的内容不存在")
        case .permission: return String(localized: "没有权限执行此操作")
        case .conflict: return String(localized: "已存在同名项目")
        case .lockConflict: return String(localized: "文件已被锁定")
        case .staleVersion: return String(localized: "文件已在其他地方被修改")
        case .validation: return String(localized: "输入内容无效")
        case .captcha: return String(localized: "验证码不正确")
        case .twoFactor: return String(localized: "两步验证码不正确")
        case .incorrectPassword: return String(localized: "密码不正确")
        case .quotaExceeded: return String(localized: "存储空间不足")
        case .fileTooLarge: return String(localized: "文件太大")
        case .partialFailure: return String(localized: "部分操作未能完成")
        case .featureDisabled: return String(localized: "站点未启用此功能")
        case .accountRestricted: return String(localized: "账户受限，无法执行此操作")
        case .linkExpired: return String(localized: "链接已失效")
        case .unknown: break
        }
        if let msg = error.serverMsg, !msg.isEmpty {
            return String(localized: "发生错误：\(msg)", comment: "服务端或系统给出的原始错误信息")
        }
        return String(localized: "发生未知错误")
    }
}
