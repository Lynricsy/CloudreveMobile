import AuthenticationServices
import SwiftUI
import OpenCloudreveCore

struct LoginForm: View {
    @Bindable var host: LoginHost
    let onSignedIn: () -> Void
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession

    var body: some View {
        Form {
            LoginHeader(
                title: host.state.siteTitle,
                siteUrl: host.state.siteUrl,
                version: host.state.serverVersion
            )
            if host.state.loadingConfig {
                Section {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
            } else if let error = host.state.configError {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(LoginIntentRetryConfig.shared) }
                        .accessibilityIdentifier("login.retryConfig")
                }
            } else {
                LoginStageContent(host: host)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(host.state.canGoBack)
        .toolbar {
            if host.state.canGoBack {
                ToolbarItem(placement: .topBarLeading) {
                    Button("返回", systemImage: "chevron.backward") { host.send(LoginIntentBack.shared) }
                        .accessibilityIdentifier("login.back")
                }
            }
        }
        .alert(noticeTitle, isPresented: $host.isNoticePresented) {
            Button("好") {}
        } message: {
            noticeMessage
        }
        .task {
            await host.effects(handle)
        }
    }

    private func handle(_ effect: any LoginEffect) {
        switch onEnum(of: effect) {
        case let .launchWebLogin(launch):
            startWebLogin(url: launch.authorizeUrl, scheme: launch.callbackScheme)
        case .launchPasskey:
            // iOS 的 Passkey 走网页登录（ASWebAuthenticationSession），不使用原生 Passkey 请求
            host.send(LoginIntentPasskeyFailed(message: nil, cancelled: true))
        case .signedIn:
            onSignedIn()
        }
    }

    /// 系统认证会话打开站点 `/session/authorize`（共享 Safari Cookie，即 `prefersEphemeralWebBrowserSession = false`），
    /// 回调 `opencloudreve://oauth/callback?code=&state=` 交给 VM 校验 state 并换取 token
    private func startWebLogin(url: String, scheme: String) {
        guard let authorizeURL = URL(string: url) else {
            host.send(LoginIntentWebLoginCancelled.shared)
            return
        }
        Task {
            do {
                let callback = try await webAuthenticationSession.authenticate(
                    using: authorizeURL,
                    callback: .customScheme(scheme),
                    preferredBrowserSession: .shared,
                    additionalHeaderFields: [:]
                )
                host.send(LoginIntentWebLoginCallback(url: callback.absoluteString))
            } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
                host.send(LoginIntentWebLoginCancelled.shared)
            } catch {
                host.send(LoginIntentWebLoginCancelled.shared)
                host.send(LoginIntentPasskeyFailed(message: error.localizedDescription, cancelled: false))
            }
        }
    }

    private var title: Text {
        switch host.state.stage {
        case .email:
            host.state.siteTitle.isEmpty ? Text("登录") : Text(verbatim: host.state.siteTitle)
        case .password: Text("输入密码")
        case .twoFactor: Text("两步验证")
        case .register: Text("注册")
        case .forgotPassword: Text("找回密码")
        }
    }

    private var noticeTitle: Text {
        switch host.state.notice {
        case .resetEmailSent: Text("邮件已发送")
        default: Text("注册成功")
        }
    }

    private var noticeMessage: Text {
        switch host.state.notice {
        case .registered: Text("现在可以使用新账户登录。")
        case .registeredActivationRequired: Text("请先点击邮件中的激活链接，然后再登录。")
        case .resetEmailSent: Text("请查收邮件，按照其中的链接重置密码。")
        case nil: Text(verbatim: "")
        }
    }
}
