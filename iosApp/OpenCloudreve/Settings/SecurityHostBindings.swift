import OpenCloudreveCore

extension ViewModelHost where S == SecurityState, I == any SecurityIntent {
    /// 开启 2FA 弹层：有密钥时显示；用户下滑关闭视为取消
    var isTwoFactorSetupPresented: Bool {
        get { state.twoFactorSetup != nil }
        set { if !newValue && state.twoFactorSetup != nil { send(SecurityIntentCancelEnableTwoFactor.shared) } }
    }
}
