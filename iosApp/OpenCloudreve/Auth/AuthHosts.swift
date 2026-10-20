import OpenCloudreveCore

typealias ServerSetupHost = ViewModelHost<ServerSetupUiState, any ServerSetupIntent, any ServerSetupEffect>
typealias LoginHost = ViewModelHost<LoginUiState, any LoginIntent, any LoginEffect>
typealias AccountSwitcherHost = ViewModelHost<AccountSwitcherUiState, any AccountSwitcherIntent, KotlinNothing>

// 可写计算属性：读状态、写转成意图，配合 `@Bindable` 得到 `$host.xxx` 绑定（避免在 body 里构造 Binding）

extension ViewModelHost where S == ServerSetupUiState, I == any ServerSetupIntent {
    var url: String {
        get { state.url }
        set { send(ServerSetupIntentUrlChanged(value: newValue)) }
    }
}

extension ViewModelHost where S == LoginUiState, I == any LoginIntent {
    var email: String {
        get { state.email }
        set { send(LoginIntentEmailChanged(value: newValue)) }
    }

    var password: String {
        get { state.password }
        set { send(LoginIntentPasswordChanged(value: newValue)) }
    }

    var passwordConfirm: String {
        get { state.passwordConfirm }
        set { send(LoginIntentPasswordConfirmChanged(value: newValue)) }
    }

    var otp: String {
        get { state.otp }
        set { send(LoginIntentOtpChanged(value: newValue)) }
    }

    var captchaAnswer: String {
        get { state.captcha.answer }
        set { send(LoginIntentCaptchaAnswerChanged(value: newValue)) }
    }

    /// 一次性提示弹窗；关闭时发 `DismissNotice`
    var isNoticePresented: Bool {
        get { state.notice != nil }
        set { if !newValue { send(LoginIntentDismissNotice.shared) } }
    }
}
