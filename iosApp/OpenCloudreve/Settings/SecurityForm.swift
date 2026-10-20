import SwiftUI
import OpenCloudreveCore

struct SecurityForm: View {
    let accountId: String
    @Bindable var security: SecurityHost
    @Bindable var settings: SettingsHost
    @State private var failure: String?
    /// 开启 2FA 弹层内显示的错误（弹层盖住本页，提示框无法从本页弹出）
    @State private var setupError: String?
    @State private var disablingTwoFactor = false
    @State private var disableCode = ""
    @State private var webPage: HandoffPage?
    @State private var openingWeb = false

    var body: some View {
        Form {
            if security.state.loading && security.state.settings == nil {
                Section {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
            }
            if let error = security.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { security.send(SecurityIntentRefresh.shared) }
                }
            }

            if security.state.settings != nil {
                PasswordSection(
                    accountId: accountId,
                    security: security,
                    canChangePassword: security.state.canChangePassword,
                    siteUrl: settings.state.siteUrl,
                    email: settings.state.email
                )
                TwoFactorSection(
                    enabled: security.state.twoFactorEnabled,
                    busy: security.state.busy == .enableTwoFactor || security.state.busy == .disableTwoFactor,
                    enable: {
                        setupError = nil
                        security.send(SecurityIntentBeginEnableTwoFactor.shared)
                    },
                    disable: {
                        disableCode = ""
                        disablingTwoFactor = true
                    }
                )
                PasskeysSection(
                    passkeys: security.state.passkeys,
                    deleting: security.state.busy == .deletePasskey,
                    openingWeb: openingWeb,
                    delete: { security.send(SecurityIntentDeletePasskey(id: $0)) },
                    addOnWeb: { Task { await openWebSecuritySettings() } }
                )
                OAuthGrantsSection(
                    grants: security.state.oauthGrants,
                    revoking: security.state.busy == .revokeGrant,
                    revoke: { security.send(SecurityIntentRevokeOAuthGrant(clientId: $0)) }
                )
            }

            ProfileVisibilitySection(settings: settings)
        }
        .refreshable { security.send(SecurityIntentRefresh.shared) }
        .sheet(isPresented: $security.isTwoFactorSetupPresented) {
            if let setup = security.state.twoFactorSetup {
                TwoFactorSetupSheet(
                    secret: setup.secret,
                    otpauthUrl: setup.otpauthUrl,
                    busy: security.state.busy == .enableTwoFactor,
                    error: setupError,
                    confirm: {
                        setupError = nil
                        security.send(SecurityIntentConfirmEnableTwoFactor(code: $0))
                    },
                    cancel: { security.send(SecurityIntentCancelEnableTwoFactor.shared) }
                )
            }
        }
        .alert("关闭两步验证", isPresented: $disablingTwoFactor) {
            TextField("6 位验证码", text: $disableCode)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
            Button("取消", role: .cancel) {}
            Button("关闭", role: .destructive) {
                security.send(SecurityIntentDisableTwoFactor(code: disableCode))
            }
        } message: {
            Text("输入验证器 App 中的验证码以关闭两步验证。")
        }
        .sheet(item: $webPage) { page in
            HandoffSafariView(url: page.url)
                .ignoresSafeArea()
        }
        .task {
            await security.effects { effect in
                handle(effect)
            }
        }
        .task {
            await settings.effects { effect in
                if case let .failed(failed) = onEnum(of: effect) {
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert(message: $failure)
    }

    private func handle(_ effect: any SecurityEffect) {
        switch onEnum(of: effect) {
        case let .failed(failed):
            let message = ErrorText.message(failed.error)
            if failed.operation == .enableTwoFactor && security.state.twoFactorSetup != nil {
                setupError = message
            } else {
                failure = message
            }
        case .passwordChanged, .twoFactorEnabled, .twoFactorDisabled, .createPasskey,
             .passkeyAdded, .passkeyDeleted, .grantRevoked:
            break
        }
    }

    /// 通过会话交接在网页端打开安全设置（添加 Passkey）
    private func openWebSecuritySettings() async {
        guard !openingWeb else { return }
        openingWeb = true
        defer { openingWeb = false }
        switch await WebHandoffLink.resolve(accountId: accountId, redirect: "/settings?tab=security") {
        case let .opened(url):
            webPage = HandoffPage(url: url)
        case let .failed(message):
            failure = message
        }
    }
}
