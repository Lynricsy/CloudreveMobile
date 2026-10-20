import Foundation

/// 当前界面语言对应的服务端语言代码（注册/找回密码邮件、验证码页 `lng`）
enum AppLanguage {
    static var serverCode: String {
        let preferred = Bundle.main.preferredLocalizations.first ?? "zh-Hans"
        return preferred.hasPrefix("zh") ? "zh-CN" : "en-US"
    }
}
