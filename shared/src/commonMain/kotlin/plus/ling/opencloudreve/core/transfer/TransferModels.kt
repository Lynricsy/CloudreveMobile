package plus.ling.opencloudreve.core.transfer

import plus.ling.opencloudreve.core.api.CloudreveException
import plus.ling.opencloudreve.core.api.ErrorCode

/** 传输方向 */
enum class TransferKind(val wire: String) {
    UPLOAD("upload"),
    DOWNLOAD("download");

    companion object {
        fun fromWire(value: String): TransferKind = entries.first { it.wire == value }
    }
}

/** 传输状态（DB `TransferTask.state`） */
enum class TransferState(val wire: String) {
    QUEUED("queued"),
    PREPARING("preparing"),
    RUNNING("running"),
    FINISHING("finishing"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled"),
    PAUSED("paused");

    /** 仍需执行器处理（启动时 `reattach` 应接管） */
    val isActive: Boolean get() = this == QUEUED || this == PREPARING || this == RUNNING || this == FINISHING

    /** 终态（不会再自动变化） */
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED

    companion object {
        fun fromWire(value: String): TransferState = entries.first { it.wire == value }
    }
}

/** 传输任务的只读快照（UI 与执行器使用） */
data class TransferItem(
    val id: String,
    val accountId: String,
    val kind: TransferKind,
    val state: TransferState,
    /** 上传源（平台不透明引用）；下载为 null */
    val sourceRef: String?,
    val displayName: String,
    val size: Long,
    /** 源文件修改时间（毫秒） */
    val lastModified: Long?,
    val mimeType: String?,
    /** 上传：目标文件完整 URI；下载：远端文件 URI */
    val targetUri: String,
    /** 下载的本地目标路径 */
    val destPath: String?,
    /** 上传时覆盖同名文件（创建新版本） */
    val overwrite: Boolean,
    /** 实际使用的策略类型（relay 时为 local）；会话建立前为 null */
    val policyType: String?,
    val chunkSize: Long,
    val chunkCount: Int,
    val bytesDone: Long,
    val errorCode: Int?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 0.0–1.0；大小为 0 时完成即 1 */
    val progress: Double
        get() = when {
            state == TransferState.COMPLETED -> 1.0
            size <= 0 -> 0.0
            else -> (bytesDone.toDouble() / size).coerceIn(0.0, 1.0)
        }

    /** 失败原因为同名冲突（40004），UI 应提供“重命名/覆盖”重试 */
    val isConflict: Boolean get() = state == TransferState.FAILED && errorCode == ErrorCode.OBJECT_EXIST
}

/** 账户的传输汇总（底部附件栏 / 通知用） */
data class TransferSummary(
    val activeUploads: Int,
    val activeDownloads: Int,
    val failed: Int,
    /** 活动任务的已完成字节与总字节 */
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val activeCount: Int get() = activeUploads + activeDownloads
    val progress: Double get() = if (bytesTotal <= 0) 0.0 else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0)

    companion object {
        val EMPTY = TransferSummary(0, 0, 0, 0, 0)
    }
}

/** 待上传的本地源 */
data class UploadSource(
    val sourceRef: String,
    val name: String,
    val size: Long,
    /** 毫秒时间戳 */
    val lastModified: Long? = null,
    val mimeType: String? = null,
)

/**
 * 平台执行器要发出的一个 HTTP 请求描述（分片或表单上传）。
 * 执行器只负责按描述发送字节：请求体用 [ChunkMaterializer] 取得（已按需加密），
 * 完成后把状态码、`ETag` 响应头与响应体交给 [UploadPlanner.onChunkDone]。
 */
data class ChunkRequest(
    val taskId: String,
    /** 0 起的分片序号 */
    val index: Int,
    /** `PUT` / `POST` */
    val method: String,
    val url: String,
    /** 不含 `Content-Length`（执行器按 [ChunkBody.contentLength] 设置） */
    val headers: Map<String, String>,
    val body: ChunkBody,
    /** 需要保存响应头 `ETag`（S3 系） */
    val captureEtag: Boolean,
)

/** 请求体描述 */
sealed class ChunkBody {
    /** 实际发送的字节数（加密不改变长度） */
    abstract val contentLength: Long

    /** 源文件 `[offset, offset + length)` 的原始字节；[encrypted] 时按绝对偏移做 AES-256-CTR */
    data class FileRange(val offset: Long, val length: Long, val encrypted: Boolean) : ChunkBody() {
        override val contentLength: Long get() = length
    }

    /**
     * `multipart/form-data`（又拍云表单上传）：先依次写 [fields]，最后是文件字段 [fileField]（必须最后）。
     * 字节布局 = [head] + 文件内容（[FileRange] 规则）+ [tail]。
     */
    data class Multipart(
        val boundary: String,
        val fields: List<FormField>,
        val fileField: String,
        val fileName: String,
        val fileContentType: String,
        val file: FileRange,
    ) : ChunkBody() {
        fun head(): ByteArray = buildString {
            fields.forEach { f ->
                append("--").append(boundary).append(CRLF)
                append("Content-Disposition: form-data; name=\"").append(escape(f.name)).append('"').append(CRLF)
                append(CRLF)
                append(f.value).append(CRLF)
            }
            append("--").append(boundary).append(CRLF)
            append("Content-Disposition: form-data; name=\"").append(escape(fileField))
                .append("\"; filename=\"").append(escape(fileName)).append('"').append(CRLF)
            append("Content-Type: ").append(fileContentType).append(CRLF)
            append(CRLF)
        }.encodeToByteArray()

        fun tail(): ByteArray = "$CRLF--$boundary--$CRLF".encodeToByteArray()

        override val contentLength: Long get() = head().size + file.length + tail().size

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")

        private companion object {
            const val CRLF = "\r\n"
        }
    }
}

data class FormField(val name: String, val value: String)

/** 执行器发送分片后得到的响应 */
data class ChunkResponse(
    val statusCode: Int,
    /** 响应头 `ETag` 原样（含引号） */
    val etag: String? = null,
    val body: String? = null,
)

/** 规划器对一次分片结果（或一次失败）的判定，执行器据此推进循环 */
sealed class TransferOutcome {
    /** 已记录；继续调用 `nextRequests`。[allChunksDone] 为真时应调用 `finalize` */
    data class Continue(val allChunksDone: Boolean) : TransferOutcome()

    /** 稍后重试：等待 [delayMillis] 后重新调用 `nextRequests`（下载为重新调用 `request`） */
    data class RetryAfter(val delayMillis: Long) : TransferOutcome()

    /** 上传会话已失效，任务已重置为 queued：执行器应停止该任务的在途请求并重新 `prepare` */
    data object Restart : TransferOutcome()

    /** 任务已标记失败；执行器应中止该任务的其它在途请求 */
    data class Failed(val code: Int, val message: String) : TransferOutcome()
}

/** 客户端侧传输错误码（负数，避免与服务端冲突） */
object TransferErrorCode {
    /** 源文件不可读（权限丢失、已删除） */
    const val SOURCE_UNAVAILABLE = -200

    /** OneDrive 不接受空文件 */
    const val ONEDRIVE_EMPTY_FILE = -201

    /** 上传前预校验未通过（见 [UploadValidator]） */
    const val VALIDATION = -202

    /** 存储方（第三方）拒绝了请求 */
    const val STORAGE_REJECTED = -203

    /** S3 系分片成功但没有返回 ETag */
    const val MISSING_ETAG = -204

    /** 不支持的存储策略类型 */
    const val UNSUPPORTED_POLICY = -205

    /** 重试次数用尽 */
    const val RETRIES_EXHAUSTED = -206
}

/** 传输失败（任务已在 DB 中标记为 failed） */
open class TransferFailedException(
    val taskId: String,
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : CloudreveException(message, cause)

/** 目标已存在（40004）：UI 提供“重命名”（`TransferManager.retry(newName=)`）或“覆盖”（`overwrite=true`） */
class UploadConflictException(taskId: String, message: String) :
    TransferFailedException(taskId, ErrorCode.OBJECT_EXIST, message)
