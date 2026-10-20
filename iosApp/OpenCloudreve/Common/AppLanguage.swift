import Foundation

/// 当前界面语言对应的服务端语言代码（注册/找回密码邮件、验证码页 `lng`）
enum AppLanguage {
    static var serverCode: String {
        let preferred = Bundle.main.preferredLocalizations.first ?? "zh-Hans"
        return preferred.hasPrefix("zh") ? "zh-CN" : "en-US"
    }

    /// 语言自身的名称（不随界面语言翻译）
    static func displayName(serverCode: String) -> String {
        serverCode.hasPrefix("zh") ? "简体中文" : "English"
    }

    /// 在本机覆盖界面语言（`AppleLanguages`），下次启动 App 时生效
    static func override(serverCode: String) {
        UserDefaults.standard.set([serverCode.hasPrefix("zh") ? "zh-Hans" : "en"], forKey: "AppleLanguages")
    }
}
