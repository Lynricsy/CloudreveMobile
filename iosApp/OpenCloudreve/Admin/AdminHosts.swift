import OpenCloudreveCore

typealias AdminSummaryHost = ViewModelHost<AdminSummaryState, any AdminSummaryIntent, any AdminSummaryEffect>
typealias AdminUserEditHost = ViewModelHost<AdminUserEditState, any AdminUserEditIntent, any AdminUserEditEffect>
/// 管理端通用列表（用户/分享/文件/任务/节点）
typealias AdminListHost<T: AnyObject> = ViewModelHost<AdminListState<T>, any AdminListIntent, any AdminListEffect>

// 用户编辑表单绑定：读表单状态、写转成意图

extension ViewModelHost where S == AdminUserEditState, I == any AdminUserEditIntent {
    var email: String {
        get { state.form.email }
        set { send(AdminUserEditIntentSetEmail(email: newValue)) }
    }

    var nick: String {
        get { state.form.nick }
        set { send(AdminUserEditIntentSetNick(nick: newValue)) }
    }

    var status: String {
        get { state.form.status }
        set { send(AdminUserEditIntentSetStatus(status: newValue)) }
    }

    var groupId: Int32 {
        get { state.form.groupId }
        set { send(AdminUserEditIntentSetGroup(groupId: newValue)) }
    }

    var newPassword: String {
        get { state.form.newPassword }
        set { send(AdminUserEditIntentSetNewPassword(password: newValue)) }
    }

    var clearTwoFactor: Bool {
        get { state.form.clearTwoFactor }
        set { send(AdminUserEditIntentSetClearTwoFactor(clear: newValue)) }
    }
}
