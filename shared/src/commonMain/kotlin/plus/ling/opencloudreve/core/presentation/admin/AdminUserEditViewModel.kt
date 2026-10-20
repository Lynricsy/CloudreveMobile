package plus.ling.opencloudreve.core.presentation.admin

import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.admin.AdminRepository
import plus.ling.opencloudreve.core.model.AdminGroup
import plus.ling.opencloudreve.core.model.AdminUser
import plus.ling.opencloudreve.core.model.AdminUserPatch
import plus.ling.opencloudreve.core.model.AdminUserUpsertRequest
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.User
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.handoff.AdminWebSection
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.core.presentation.handoff.attempt
import plus.ling.opencloudreve.core.presentation.handoff.availabilityOrNull

/** 用户编辑表单（未保存的修改） */
data class AdminUserForm(
    val email: String = "",
    val nick: String = "",
    /** active / inactive / manual_banned / sys_banned */
    val status: String = User.STATUS_ACTIVE,
    val groupId: Int = 0,
    /** 非空则重置密码（6–128 位） */
    val newPassword: String = "",
    /** 清除两步验证 */
    val clearTwoFactor: Boolean = false,
) {
    companion object {
        fun from(user: AdminUser) = AdminUserForm(user.email, user.nick, user.status, user.groupUsers)
    }
}

data class AdminUserEditState(
    val userId: Int,
    val user: AdminUser? = null,
    val groups: List<AdminGroup> = emptyList(),
    val form: AdminUserForm = AdminUserForm(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val calibrating: Boolean = false,
    val deleting: Boolean = false,
    /** 加载失败；操作失败通过 [AdminUserEditEffect.Failed] 通知 */
    val error: UiError? = null,
    val handoff: HandoffAvailability = HandoffAvailability.Checking,
    val openingWeb: Boolean = false,
) {
    /** 1 号用户且在管理员组：不允许修改状态与用户组（服务端 `CodeInvalidActionOnDefaultUser`） */
    val statusAndGroupLocked: Boolean
        get() = userId == 1 && user?.edges?.group?.permissions?.has(GroupPermission.IS_ADMIN) == true

    /** 1 号用户不能删除 */
    val canDelete: Boolean get() = user != null && userId != 1 && !deleting

    val dirty: Boolean
        get() = user?.let { u ->
            form.email != u.email || form.nick != u.nick || form.status != u.status || form.groupId != u.groupUsers ||
                form.newPassword.isNotEmpty() || form.clearTwoFactor
        } ?: false

    /** 表单校验：邮箱基本格式、昵称非空、密码为空或 6–128 位 */
    val formValid: Boolean
        get() = form.email.trim().let { it.contains('@') && !it.startsWith('@') && !it.endsWith('@') } &&
            form.nick.isNotBlank() &&
            (form.newPassword.isEmpty() || form.newPassword.length in 6..128)

    val canSave: Boolean get() = dirty && formValid && !saving

    /** 浏览该用户“我的文件”的 URI */
    val filesUri: String? get() = user?.filesRoot()?.toString()

    val statusOptions: List<String> get() = STATUSES

    companion object {
        val STATUSES = listOf(User.STATUS_ACTIVE, User.STATUS_INACTIVE, User.STATUS_MANUAL_BANNED, User.STATUS_SYS_BANNED)
    }
}

sealed interface AdminUserEditIntent {
    data object Refresh : AdminUserEditIntent
    data class SetEmail(val email: String) : AdminUserEditIntent
    data class SetNick(val nick: String) : AdminUserEditIntent
    data class SetStatus(val status: String) : AdminUserEditIntent
    data class SetGroup(val groupId: Int) : AdminUserEditIntent
    data class SetNewPassword(val password: String) : AdminUserEditIntent
    data class SetClearTwoFactor(val clear: Boolean) : AdminUserEditIntent

    /** 放弃未保存的修改 */
    data object ResetForm : AdminUserEditIntent
    data object Save : AdminUserEditIntent

    /** 重新计算已用空间 */
    data object Calibrate : AdminUserEditIntent

    /** 删除用户（连同其全部文件，不可恢复） */
    data object Delete : AdminUserEditIntent

    /** 浏览该用户的文件（[AdminUserEditEffect.OpenFiles]） */
    data object OpenFiles : AdminUserEditIntent
    data object OpenInWeb : AdminUserEditIntent
    data object DismissError : AdminUserEditIntent
}

sealed interface AdminUserEditEffect {
    data object Saved : AdminUserEditEffect
    data object Calibrated : AdminUserEditEffect
    data object Deleted : AdminUserEditEffect

    /** 用 `FileListViewModel(accountId, uri)` 打开 */
    data class OpenFiles(val uri: String) : AdminUserEditEffect
    data class OpenUrl(val url: String) : AdminUserEditEffect
    data class Failed(val error: UiError) : AdminUserEditEffect
}

/** 管理端用户详情与编辑（`GET/PUT /admin/user/:id`、校准、删除） */
class AdminUserEditViewModel(
    val accountId: String,
    val userId: Int,
    private val admin: AdminRepository,
    private val handoff: WebHandoff,
) : CoreViewModel<AdminUserEditState, AdminUserEditIntent, AdminUserEditEffect>(AdminUserEditState(userId)) {

    init {
        load()
        scope.launch { runCatching { loadAllGroups(admin) }.getOrNull()?.let { g -> updateState { it.copy(groups = g) } } }
        scope.launch { handoff.availabilityOrNull(accountId)?.let { a -> updateState { it.copy(handoff = a) } } }
    }

    override fun on(intent: AdminUserEditIntent) {
        when (intent) {
            AdminUserEditIntent.Refresh -> load()
            is AdminUserEditIntent.SetEmail -> form { it.copy(email = intent.email) }
            is AdminUserEditIntent.SetNick -> form { it.copy(nick = intent.nick) }
            is AdminUserEditIntent.SetStatus -> if (!currentState.statusAndGroupLocked) form { it.copy(status = intent.status) }
            is AdminUserEditIntent.SetGroup -> if (!currentState.statusAndGroupLocked) form { it.copy(groupId = intent.groupId) }
            is AdminUserEditIntent.SetNewPassword -> form { it.copy(newPassword = intent.password) }
            is AdminUserEditIntent.SetClearTwoFactor -> form { it.copy(clearTwoFactor = intent.clear) }
            AdminUserEditIntent.ResetForm -> updateState { s -> s.copy(form = s.user?.let(AdminUserForm::from) ?: s.form) }
            AdminUserEditIntent.Save -> save()
            AdminUserEditIntent.Calibrate -> calibrate()
            AdminUserEditIntent.Delete -> delete()
            AdminUserEditIntent.OpenFiles -> currentState.filesUri?.let { sendEffect(AdminUserEditEffect.OpenFiles(it)) }
            AdminUserEditIntent.OpenInWeb -> openInWeb()
            AdminUserEditIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun form(reducer: (AdminUserForm) -> AdminUserForm) = updateState { it.copy(form = reducer(it.form)) }

    private fun load() {
        updateState { it.copy(loading = true, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val u = admin.user(userId)
            updateState { it.copy(loading = false, user = u, form = AdminUserForm.from(u)) }
        }
    }

    private fun save() {
        val s = currentState
        val user = s.user ?: return
        if (s.saving) return
        if (!s.formValid) return sendEffect(AdminUserEditEffect.Failed(UiError(ErrorKind.Validation)))
        val f = s.form
        val request = AdminUserUpsertRequest(
            // 服务端只写入 email/nick/avatar/status/group_users，必须带回原 avatar
            user = AdminUserPatch.from(user).copy(
                email = f.email.trim(),
                nick = f.nick.trim(),
                status = if (s.statusAndGroupLocked) user.status else f.status,
                groupUsers = if (s.statusAndGroupLocked) user.groupUsers else f.groupId,
            ),
            password = f.newPassword.ifEmpty { null },
            // 非空字符串即清除 2FA：不需要时必须为 null
            twoFa = if (f.clearTwoFactor) AdminUserUpsertRequest.CLEAR_TWO_FA else null,
        )
        updateState { it.copy(saving = true) }
        launchCatching(onError = { e ->
            updateState { it.copy(saving = false) }
            sendEffect(AdminUserEditEffect.Failed(e))
        }) {
            val updated = admin.updateUser(request)
            updateState { it.copy(saving = false, user = updated, form = AdminUserForm.from(updated)) }
            sendEffect(AdminUserEditEffect.Saved)
        }
    }

    private fun calibrate() {
        if (currentState.calibrating || currentState.user == null) return
        updateState { it.copy(calibrating = true) }
        launchCatching(onError = { e ->
            updateState { it.copy(calibrating = false) }
            sendEffect(AdminUserEditEffect.Failed(e))
        }) {
            val updated = admin.calibrateUser(userId)
            // 保留表单中未保存的修改，只更新用户信息（容量）
            updateState { it.copy(calibrating = false, user = updated) }
            sendEffect(AdminUserEditEffect.Calibrated)
        }
    }

    private fun delete() {
        if (!currentState.canDelete) return
        updateState { it.copy(deleting = true) }
        launchCatching(onError = { e ->
            updateState { it.copy(deleting = false) }
            sendEffect(AdminUserEditEffect.Failed(e))
        }) {
            admin.deleteUsers(listOf(userId))
            updateState { it.copy(deleting = false) }
            sendEffect(AdminUserEditEffect.Deleted)
        }
    }

    private fun openInWeb() {
        if (currentState.openingWeb) return
        updateState { it.copy(openingWeb = true) }
        scope.launch {
            val r = handoff.attempt(accountId, WebHandoff.adminRedirect(AdminWebSection.Users))
            updateState { it.copy(openingWeb = false, handoff = r.availability ?: it.handoff) }
            r.url?.let { sendEffect(AdminUserEditEffect.OpenUrl(it)) }
            r.error?.let { sendEffect(AdminUserEditEffect.Failed(it)) }
        }
    }
}
