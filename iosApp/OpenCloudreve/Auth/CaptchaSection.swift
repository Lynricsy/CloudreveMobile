import SwiftUI
import OpenCloudreveCore

/// 当前阶段的验证码：图形验证码原生渲染；recaptcha/turnstile/cap 用 WebView 加载站点 `/captcha` 页
struct CaptchaSection: View {
    @Bindable var host: LoginHost
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL

    var body: some View {
        let captcha = host.state.captcha
        switch captcha.kind {
        case .none:
            EmptyView()
        case .image:
            Section("验证码") {
                HStack(spacing: 12) {
                    TextField("输入右侧字符", text: $host.captchaAnswer)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("captcha.answer")
                    CaptchaImageButton(base64: captcha.image, loading: captcha.loading) {
                        host.send(LoginIntentRefreshCaptcha.shared)
                    }
                }
                if let error = captcha.loadError {
                    ErrorRow(message: ErrorText.message(error))
                }
            }
        case .web:
            Section {
                if let url = URL(string: host.state.captchaPageUrl(dark: colorScheme == .dark, language: AppLanguage.serverCode)) {
                    WebCaptchaView(url: url) { json in
                        host.send(LoginIntentWebCaptchaSolved(json: json))
                    }
                    // 失败后服务端已作废 token，generation 递增时重建 WebView 重新验证
                    .id(captcha.generation)
                    .frame(minHeight: 80)
                    .accessibilityIdentifier("captcha.web")
                }
            } header: {
                Text("人机验证")
            } footer: {
                if captcha.webToken != nil {
                    Label("验证已完成", systemImage: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                }
            }
        case .unsupported:
            Section {
                Text("此站点的验证码需要较新的服务端（4.19.11 及以上）才能在 App 内完成，请在网页端登录。")
                if let url = URL(string: host.state.siteUrl + "/session") {
                    Button("在浏览器中打开") { openURL(url) }
                }
            }
        }
    }
}
