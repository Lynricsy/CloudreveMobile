import OpenCloudreveCore

typealias SettingsHost = ViewModelHost<SettingsState, any SettingsIntent, any SettingsEffect>
typealias SecurityHost = ViewModelHost<SecurityState, any SecurityIntent, any SecurityEffect>
typealias DavAccountsHost = ViewModelHost<DavAccountsState, any DavAccountsIntent, any DavAccountsEffect>
typealias PoliciesHost = ViewModelHost<PoliciesState, any PoliciesIntent, KotlinNothing>

// 设置项绑定：读状态、写转成意图（每次写入即同步到服务端）

extension ViewModelHost where S == SettingsState, I == any SettingsIntent {
    /// 服务端界面语言；未设置时按当前 App 语言显示
    var language: String {
        get { state.language ?? AppLanguage.serverCode }
        set { send(SettingsIntentSetLanguage(language: newValue)) }
    }

    /// 文件视图设置是否在设备间同步（服务端字段为“禁用同步”，这里取反）
    var syncViewSettings: Bool {
        get { !state.disableViewSync }
        set { send(SettingsIntentSetDisableViewSync(disabled: !newValue)) }
    }

    /// 个人主页展示哪些分享（`ShareLinksInProfile`）
    var shareLinksInProfile: String {
        get { state.shareLinksInProfile }
        set { send(SettingsIntentSetShareLinksInProfile(level: newValue)) }
    }
}
