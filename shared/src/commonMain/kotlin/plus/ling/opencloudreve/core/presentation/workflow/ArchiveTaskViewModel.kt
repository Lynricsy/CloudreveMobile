package plus.ling.opencloudreve.core.presentation.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.model.ArchiveWorkflowRequest
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.NodeCapability
import plus.ling.opencloudreve.core.model.RelocateWorkflowRequest
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.model.WorkflowNode
import plus.ling.opencloudreve.core.model.WorkflowTask
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.currentUserOrNull
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.workflow.WorkflowRepository

/** 后台任务类型 */
enum class ArchiveTaskMode {
    /** 压缩为 zip（`POST /workflow/archive`） */
    COMPRESS,

    /** 解压到目录（`POST /workflow/extract`） */
    EXTRACT,

    /** 转移存储策略（`POST /workflow/relocate`） */
    RELOCATE,
}

/** 转移目标策略候选；负载均衡与加密策略不可选 */
data class RelocatePolicyOption(val policy: StoragePolicy) {
    val id: String get() = policy.id
    val name: String get() = policy.name
    val selectable: Boolean get() = policy.type != StoragePolicy.TYPE_LOAD_BALANCE && !policy.encryption
}

data class ArchiveTaskState(
    val mode: ArchiveTaskMode,
    /** 源（压缩：多个；解压：第一个为压缩包；转移：多个，须在“我的文件”中） */
    val srcUris: List<String>,
    /** 目标目录（压缩：压缩包所在目录；解压：解压到） */
    val dstUri: String,
    /** 压缩包文件名 */
    val archiveName: String = DEFAULT_ARCHIVE_NAME,
    /** 文件名编码（仅 zip 解压；空串为默认） */
    val encoding: String = "",
    /** 压缩包密码（zip/7z 解压） */
    val password: String = "",
    /** 只解压匹配的条目（来自压缩包预览的选择） */
    val fileMask: List<String> = emptyList(),
    /** 用户组允许该任务（用户信息未取到前为 true） */
    val permitted: Boolean = true,
    val canSelectNode: Boolean = false,
    val nodes: List<WorkflowNode> = emptyList(),
    /** null 自动分配 */
    val selectedNodeId: String? = null,
    val policies: List<RelocatePolicyOption> = emptyList(),
    val selectedPolicyId: String? = null,
    val loading: Boolean = false,
    val loadError: UiError? = null,
    val submitting: Boolean = false,
    val created: WorkflowTask? = null,
) {
    /** 第一个源的名称（解压时为压缩包名） */
    val sourceName: String get() = srcUris.firstOrNull()?.let { CrUri.parseOrNull(it)?.name() }.orEmpty()
    private val sourceExt: String get() = sourceName.substringAfterLast('.', "").lowercase()

    /** 解压 zip 时可选文件名编码 */
    val showEncoding: Boolean get() = mode == ArchiveTaskMode.EXTRACT && sourceExt == "zip"

    /** 解压 zip/7z 时可填密码 */
    val showPassword: Boolean get() = mode == ArchiveTaskMode.EXTRACT && (sourceExt == "zip" || sourceExt == "7z")

    /** 压缩包完整目标 URI */
    val archiveUri: String?
        get() = if (mode != ArchiveTaskMode.COMPRESS || archiveName.isBlank()) null
        else CrUri.parseOrNull(dstUri)?.join(archiveName.trim())?.toString()

    val canSubmit: Boolean
        get() = permitted && !submitting && !loading && srcUris.isNotEmpty() && when (mode) {
            ArchiveTaskMode.COMPRESS -> archiveUri != null
            ArchiveTaskMode.EXTRACT -> dstUri.isNotBlank()
            ArchiveTaskMode.RELOCATE -> policies.any { it.id == selectedPolicyId && it.selectable }
        }

    companion object {
        const val DEFAULT_ARCHIVE_NAME = "archive.zip"

        /** 解压文件名编码（与网页端一致；空串为默认） */
        val ENCODINGS: List<String> = listOf(
            "ibm866", "iso8859_2", "iso8859_3", "iso8859_4", "iso8859_5", "iso8859_6", "iso8859_7", "iso8859_8",
            "iso8859_8I", "iso8859_10", "iso8859_13", "iso8859_14", "iso8859_15", "iso8859_16", "koi8r", "koi8u",
            "macintosh", "windows874", "windows1250", "windows1251", "windows1252", "windows1253", "windows1254",
            "windows1255", "windows1256", "windows1257", "windows1258", "macintoshcyrillic", "gbk", "gb18030", "big5",
            "eucjp", "iso2022jp", "shiftjis", "euckr", "utf16be", "utf16le",
        )
    }
}

sealed interface ArchiveTaskIntent {
    data class SetDestination(val uri: String) : ArchiveTaskIntent
    data class SetArchiveName(val name: String) : ArchiveTaskIntent
    data class SetEncoding(val encoding: String) : ArchiveTaskIntent
    data class SetPassword(val password: String) : ArchiveTaskIntent
    data class SelectNode(val nodeId: String?) : ArchiveTaskIntent
    data class SelectPolicy(val policyId: String) : ArchiveTaskIntent
    data object Submit : ArchiveTaskIntent
}

sealed interface ArchiveTaskEffect {
    /** 任务已创建（平台提示并可跳转任务页） */
    data class Created(val task: WorkflowTask) : ArchiveTaskEffect
    data class Failed(val error: UiError) : ArchiveTaskEffect
}

/**
 * 压缩 / 解压 / 转移存储策略的创建表单。
 * 压缩、解压需要 `archive_task`；转移需要 `relocate`；有 `select_node` 时列出对应能力的节点（压缩 1、解压 2）。
 * 转移的候选策略来自 `GET /user/setting/policies`，负载均衡与加密策略不可选。
 */
class ArchiveTaskViewModel(
    val accountId: String,
    mode: ArchiveTaskMode,
    srcUris: List<String>,
    dstUri: String,
    fileMask: List<String>,
    private val workflows: WorkflowRepository,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<ArchiveTaskState, ArchiveTaskIntent, ArchiveTaskEffect>(
    ArchiveTaskState(mode = mode, srcUris = srcUris, dstUri = dstUri, fileMask = fileMask),
) {
    init {
        load()
    }

    override fun on(intent: ArchiveTaskIntent) {
        when (intent) {
            is ArchiveTaskIntent.SetDestination -> updateState { it.copy(dstUri = intent.uri) }
            is ArchiveTaskIntent.SetArchiveName -> updateState { it.copy(archiveName = intent.name) }
            is ArchiveTaskIntent.SetEncoding -> updateState { it.copy(encoding = intent.encoding.trim()) }
            is ArchiveTaskIntent.SetPassword -> updateState { it.copy(password = intent.password) }
            is ArchiveTaskIntent.SelectNode -> updateState { it.copy(selectedNodeId = intent.nodeId) }
            is ArchiveTaskIntent.SelectPolicy -> updateState { it.copy(selectedPolicyId = intent.policyId) }
            ArchiveTaskIntent.Submit -> submit()
        }
    }

    private fun load() {
        scope.launch {
            val mode = currentState.mode
            val user = userSettings.currentUserOrNull()
            if (user != null) {
                val required = if (mode == ArchiveTaskMode.RELOCATE) GroupPermission.RELOCATE else GroupPermission.ARCHIVE_TASK
                updateState {
                    it.copy(
                        permitted = user.hasPermission(required),
                        canSelectNode = mode != ArchiveTaskMode.RELOCATE && user.hasPermission(GroupPermission.SELECT_NODE),
                    )
                }
            }
            if (currentState.canSelectNode) {
                val capability = if (mode == ArchiveTaskMode.COMPRESS) NodeCapability.CREATE_ARCHIVE else NodeCapability.EXTRACT_ARCHIVE
                val nodes = loadNodes(workflows, capability)
                updateState { it.copy(nodes = nodes) }
            }
            if (mode == ArchiveTaskMode.RELOCATE) {
                updateState { it.copy(loading = true, loadError = null) }
                try {
                    val options = userSettings.policies().map(::RelocatePolicyOption)
                    updateState { it.copy(loading = false, policies = options) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateState { it.copy(loading = false, loadError = e.toUiError()) }
                }
            }
        }
    }

    private fun submit() {
        val s = currentState
        if (!s.canSubmit) return
        val nodeId = s.selectedNodeId?.takeIf { s.canSelectNode }
        scope.launch {
            updateState { it.copy(submitting = true) }
            try {
                val task = when (s.mode) {
                    ArchiveTaskMode.COMPRESS -> workflows.createArchive(
                        ArchiveWorkflowRequest(src = s.srcUris, dst = s.archiveUri!!, nodeId = nodeId),
                    )
                    ArchiveTaskMode.EXTRACT -> workflows.extractArchive(
                        ArchiveWorkflowRequest(
                            src = listOf(s.srcUris.first()),
                            dst = s.dstUri,
                            encoding = s.encoding.takeIf { s.showEncoding && it.isNotEmpty() },
                            password = s.password.takeIf { s.showPassword && it.isNotEmpty() },
                            fileMask = s.fileMask.takeIf { it.isNotEmpty() },
                            nodeId = nodeId,
                        ),
                    )
                    ArchiveTaskMode.RELOCATE -> workflows.relocate(RelocateWorkflowRequest(s.srcUris, s.selectedPolicyId!!))
                }
                updateState { it.copy(submitting = false, created = task) }
                sendEffect(ArchiveTaskEffect.Created(task))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(submitting = false) }
                sendEffect(ArchiveTaskEffect.Failed(e.toUiError()))
            }
        }
    }
}
