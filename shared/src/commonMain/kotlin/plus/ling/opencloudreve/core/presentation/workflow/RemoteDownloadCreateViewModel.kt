package plus.ling.opencloudreve.core.presentation.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.DownloadWorkflowRequest
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.NodeCapability
import plus.ling.opencloudreve.core.model.WorkflowNode
import plus.ling.opencloudreve.core.model.WorkflowTask
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.files.OperationFailure
import plus.ling.opencloudreve.core.presentation.currentUserOrNull
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.workflow.WorkflowRepository

data class RemoteDownloadCreateState(
    /** 多行输入：每行一个 URL 或磁力链接 */
    val urlsText: String = "",
    /** 种子文件 URI（`src_file`）；设置后忽略 [urlsText] */
    val torrentUri: String? = null,
    /** 保存目录 */
    val dstUri: String,
    /** 用户组允许远程下载（用户信息未取到前为 true，交给服务端判定） */
    val permitted: Boolean = true,
    /** 用户组允许选择节点（`select_node`） */
    val canSelectNode: Boolean = false,
    val nodes: List<WorkflowNode> = emptyList(),
    val loadingNodes: Boolean = false,
    /** null 表示由服务端自动分配 */
    val selectedNodeId: String? = null,
    val submitting: Boolean = false,
    /** 部分失败（40081）的源及原因 */
    val failures: List<OperationFailure> = emptyList(),
    /** 已创建的任务 */
    val created: List<WorkflowTask> = emptyList(),
) {
    val useTorrent: Boolean get() = torrentUri != null

    /** 解析出的下载源（去空行、去首尾空白） */
    val sources: List<String> get() = urlsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    val torrentName: String? get() = torrentUri?.let { CrUri.parseOrNull(it)?.name() }

    val canSubmit: Boolean
        get() = permitted && !submitting && dstUri.isNotBlank() && (useTorrent || sources.isNotEmpty())
}

sealed interface RemoteDownloadCreateIntent {
    data class SetUrls(val text: String) : RemoteDownloadCreateIntent

    /** 选择/清除种子文件 */
    data class SetTorrent(val uri: String?) : RemoteDownloadCreateIntent
    data class SetDestination(val uri: String) : RemoteDownloadCreateIntent
    data class SelectNode(val nodeId: String?) : RemoteDownloadCreateIntent
    data object Submit : RemoteDownloadCreateIntent
}

sealed interface RemoteDownloadCreateEffect {
    /** 全部或部分创建成功（部分失败项在状态 `failures`） */
    data class Created(val tasks: List<WorkflowTask>, val failedCount: Int) : RemoteDownloadCreateEffect
    data class Failed(val error: UiError) : RemoteDownloadCreateEffect
}

/**
 * 新建远程下载（URL/磁力多行，或 `.torrent` 文件）。有 `select_node` 权限时列出远程下载节点（capability 3）。
 * 40081 部分失败时成功项仍返回：失败项写入 `failures` 并只保留失败的 URL 方便重试。
 */
class RemoteDownloadCreateViewModel(
    val accountId: String,
    dstUri: String,
    torrentUri: String?,
    private val workflows: WorkflowRepository,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<RemoteDownloadCreateState, RemoteDownloadCreateIntent, RemoteDownloadCreateEffect>(
    RemoteDownloadCreateState(dstUri = dstUri, torrentUri = torrentUri),
) {
    init {
        scope.launch {
            val user = userSettings.currentUserOrNull() ?: return@launch
            updateState {
                it.copy(
                    permitted = user.hasPermission(GroupPermission.REMOTE_DOWNLOAD),
                    canSelectNode = user.hasPermission(GroupPermission.SELECT_NODE),
                )
            }
            if (user.hasPermission(GroupPermission.SELECT_NODE)) {
                updateState { it.copy(loadingNodes = true) }
                val nodes = loadNodes(workflows, NodeCapability.REMOTE_DOWNLOAD)
                updateState { it.copy(nodes = nodes, loadingNodes = false) }
            }
        }
    }

    override fun on(intent: RemoteDownloadCreateIntent) {
        when (intent) {
            is RemoteDownloadCreateIntent.SetUrls -> updateState { it.copy(urlsText = intent.text) }
            is RemoteDownloadCreateIntent.SetTorrent -> updateState { it.copy(torrentUri = intent.uri) }
            is RemoteDownloadCreateIntent.SetDestination -> updateState { it.copy(dstUri = intent.uri) }
            is RemoteDownloadCreateIntent.SelectNode -> updateState { it.copy(selectedNodeId = intent.nodeId) }
            RemoteDownloadCreateIntent.Submit -> submit()
        }
    }

    private fun submit() {
        val s = currentState
        if (!s.canSubmit) return
        val request = DownloadWorkflowRequest(
            src = if (s.useTorrent) null else s.sources,
            srcFile = s.torrentUri,
            dst = s.dstUri,
            nodeId = s.selectedNodeId?.takeIf { s.canSelectNode },
        )
        scope.launch {
            updateState { it.copy(submitting = true, failures = emptyList()) }
            try {
                val tasks = workflows.createDownload(request)
                updateState { it.copy(submitting = false, created = tasks, urlsText = "", torrentUri = null) }
                sendEffect(RemoteDownloadCreateEffect.Created(tasks, 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == ErrorCode.BATCH_PARTIAL && !e.aggregated.isNullOrEmpty()) {
                    val failures = e.aggregated.orEmpty().map { (src, env) -> OperationFailure(src, ApiException(env.code, env.msg).toUiError()) }
                    updateState {
                        it.copy(
                            submitting = false,
                            failures = failures,
                            urlsText = failures.joinToString("\n") { f -> f.uri },
                        )
                    }
                    sendEffect(RemoteDownloadCreateEffect.Created(emptyList(), failures.size))
                } else {
                    updateState { it.copy(submitting = false) }
                    sendEffect(RemoteDownloadCreateEffect.Failed(e.toUiError()))
                }
            } catch (e: Exception) {
                updateState { it.copy(submitting = false) }
                sendEffect(RemoteDownloadCreateEffect.Failed(e.toUiError()))
            }
        }
    }
}

/** 可选节点；失败返回空列表（由服务端自动分配） */
internal suspend fun loadNodes(workflows: WorkflowRepository, capability: Int): List<WorkflowNode> =
    try {
        workflows.nodes(capability)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
