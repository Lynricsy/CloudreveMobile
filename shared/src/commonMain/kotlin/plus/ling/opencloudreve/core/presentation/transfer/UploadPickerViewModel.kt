package plus.ling.opencloudreve.core.presentation.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.transfer.TransferErrorCode
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.TransferManager
import plus.ling.opencloudreve.core.transfer.UploadRejection
import plus.ling.opencloudreve.core.transfer.UploadSource
import plus.ling.opencloudreve.core.transfer.UploadValidator

/** 预校验未通过的源（已作为 failed 任务入库，不会调度） */
data class RejectedUpload(
    val name: String,
    val size: Long,
    val reason: UploadRejection,
    val taskId: String,
)

/** 一次入队的结果 */
data class UploadEnqueueResult(
    val targetUri: String,
    val accepted: List<TransferItem>,
    val rejected: List<RejectedUpload>,
)

data class UploadPickerState(
    /** 目标目录 */
    val targetUri: String,
    /** 目标目录的存储策略（列表的 `storage_policy`，负载均衡带子策略）；未知为 null */
    val policy: StoragePolicy? = null,
    val loadingPolicy: Boolean = false,
    /** 取策略失败（不阻止上传：服务端仍会校验） */
    val policyError: UiError? = null,
    val enqueuing: Boolean = false,
    /** 最近一次入队结果（被拒绝的项给出提示） */
    val lastResult: UploadEnqueueResult? = null,
) {
    /** 目标目录名（根为空串） */
    val targetName: String get() = CrUri.parseOrNull(targetUri)?.name().orEmpty()

    /** 单文件大小上限（字节，0 不限；负载均衡取子策略最大值） */
    val maxSize: Long
        get() {
            val p = policy ?: return 0
            val children = p.children.orEmpty()
            if (children.isEmpty()) return p.maxSize
            return if (children.any { it.maxSize == 0L }) 0 else children.maxOf { it.maxSize }
        }

    /** 后缀白名单（空为不限；负载均衡时为空，交给逐项校验） */
    val allowedSuffix: List<String> get() = if (policy?.children.isNullOrEmpty()) policy?.allowedSuffix.orEmpty() else emptyList()
    val deniedSuffix: List<String> get() = if (policy?.children.isNullOrEmpty()) policy?.deniedSuffix.orEmpty() else emptyList()
}

sealed interface UploadPickerIntent {
    /** 切换目标目录；[policy] 已知时直接使用，否则重新获取 */
    data class SetTarget(val uri: String, val policy: StoragePolicy? = null) : UploadPickerIntent

    /**
     * 入队上传。[targetUri] 为空时用当前目标；与当前目标不同则先切换并获取其策略。
     * [overwrite] 为 true 时同名文件上传为新版本。
     */
    data class Enqueue(
        val sources: List<UploadSource>,
        val targetUri: String? = null,
        val overwrite: Boolean = false,
    ) : UploadPickerIntent

    data object DismissResult : UploadPickerIntent
}

sealed interface UploadPickerEffect {
    /** 已入队（[UploadEnqueueResult.rejected] 非空时平台提示原因） */
    data class Enqueued(val result: UploadEnqueueResult) : UploadPickerEffect
    data class Failed(val error: UiError) : UploadPickerEffect
}

/**
 * 统一上传入口（文件列表“上传”、照片选择、系统分享菜单、分享扩展）。
 * 入队前用目标目录的 `storage_policy` 预校验（大小上限、后缀黑白名单、文件名正则）：
 * 不通过的直接以 failed 入库并在结果中列出，其余交给传输执行器。
 */
class UploadPickerViewModel(
    val accountId: String,
    targetUri: String,
    initialPolicy: StoragePolicy?,
    private val transfers: TransferManager,
    private val files: FileRepository,
) : CoreViewModel<UploadPickerState, UploadPickerIntent, UploadPickerEffect>(
    UploadPickerState(targetUri = targetUri, policy = initialPolicy),
) {
    private var policyJob: Job? = null

    init {
        if (initialPolicy == null) loadPolicy(targetUri)
    }

    override fun on(intent: UploadPickerIntent) {
        when (intent) {
            is UploadPickerIntent.SetTarget -> setTarget(intent.uri, intent.policy)
            is UploadPickerIntent.Enqueue -> enqueue(intent.sources, intent.targetUri, intent.overwrite)
            UploadPickerIntent.DismissResult -> updateState { it.copy(lastResult = null) }
        }
    }

    /** 按当前策略预先检查单个源（选择文件后即时提示）；通过返回 null */
    fun check(name: String, size: Long): UploadRejection? = UploadValidator.validate(name, size, currentState.policy)

    private fun setTarget(uri: String, policy: StoragePolicy?) {
        updateState { it.copy(targetUri = uri, policy = policy, policyError = null) }
        if (policy == null) loadPolicy(uri) else policyJob?.cancel()
    }

    private fun loadPolicy(uri: String) {
        policyJob?.cancel()
        policyJob = scope.launch {
            updateState { it.copy(loadingPolicy = true, policyError = null) }
            try {
                val policy = files.listPage(uri).storagePolicy
                updateState { if (it.targetUri == uri) it.copy(policy = policy, loadingPolicy = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(loadingPolicy = false, policyError = e.toUiError()) }
            }
        }
    }

    private fun enqueue(sources: List<UploadSource>, targetUri: String?, overwrite: Boolean) {
        if (sources.isEmpty() || currentState.enqueuing) return
        val target = targetUri ?: currentState.targetUri
        if (target != currentState.targetUri) setTarget(target, null)
        updateState { it.copy(enqueuing = true) }
        scope.launch {
            try {
                // 等待策略就绪（切换目标或初始加载中）
                policyJob?.join()
                val items = transfers.enqueueUploads(accountId, sources, target, currentState.policy, overwrite)
                val rejected = ArrayList<RejectedUpload>()
                val accepted = ArrayList<TransferItem>()
                items.forEach { item ->
                    val reason = item.rejection()
                    if (reason != null) {
                        rejected += RejectedUpload(item.displayName, item.size, reason, item.id)
                    } else {
                        accepted += item
                    }
                }
                val result = UploadEnqueueResult(target, accepted, rejected)
                updateState { it.copy(enqueuing = false, lastResult = result) }
                sendEffect(UploadPickerEffect.Enqueued(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { it.copy(enqueuing = false) }
                sendEffect(UploadPickerEffect.Failed(e.toUiError()))
            }
        }
    }
}

/** 预校验失败入库的任务的拒绝原因（`errorCode == VALIDATION`，`errorMessage` 为 [UploadRejection] 名称） */
internal fun TransferItem.rejection(): UploadRejection? =
    if (state == TransferState.FAILED && errorCode == TransferErrorCode.VALIDATION) {
        UploadRejection.entries.firstOrNull { it.name == errorMessage }
    } else {
        null
    }
