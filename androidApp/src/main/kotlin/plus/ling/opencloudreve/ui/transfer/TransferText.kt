package plus.ling.opencloudreve.ui.transfer

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.transfer.TransferRow
import plus.ling.opencloudreve.core.transfer.TransferErrorCode
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.UploadRejection
import plus.ling.opencloudreve.ui.files.displayPath
import plus.ling.opencloudreve.ui.files.formatBytes

/** 预校验拒绝原因 */
@Composable
fun rejectionText(reason: UploadRejection): String = stringResource(
    when (reason) {
        UploadRejection.ILLEGAL_NAME -> R.string.upload_reject_illegal_name
        UploadRejection.SUFFIX_NOT_ALLOWED -> R.string.upload_reject_suffix_not_allowed
        UploadRejection.SUFFIX_DENIED -> R.string.upload_reject_suffix_denied
        UploadRejection.NAME_NOT_ALLOWED -> R.string.upload_reject_name_not_allowed
        UploadRejection.NAME_DENIED -> R.string.upload_reject_name_denied
        UploadRejection.TOO_LARGE -> R.string.upload_reject_too_large
    },
)

/** 失败原因：执行器/规划器的本地错误码有专门文案，其它显示服务端/存储端原文 */
fun failureText(context: Context, item: TransferItem): String {
    val code = item.errorCode
    val msg = item.errorMessage?.takeIf { it.isNotBlank() }
    val res = when (code) {
        TransferErrorCode.SOURCE_UNAVAILABLE -> R.string.transfer_error_source
        TransferErrorCode.ONEDRIVE_EMPTY_FILE -> R.string.transfer_error_onedrive_empty
        TransferErrorCode.STORAGE_REJECTED -> R.string.transfer_error_storage
        TransferErrorCode.MISSING_ETAG -> R.string.transfer_error_etag
        TransferErrorCode.UNSUPPORTED_POLICY -> R.string.transfer_error_unsupported
        ErrorCode.TRANSPORT -> R.string.transfer_error_network
        else -> null
    }
    return when {
        res != null && msg != null -> context.getString(R.string.transfer_error_with_detail, context.getString(res), msg)
        res != null -> context.getString(res)
        code != null -> context.getString(R.string.transfer_error_code, code, msg ?: "")
        else -> msg ?: context.getString(R.string.transfer_state_failed)
    }
}

/** 行的状态说明（进度、速度、剩余时间或结束原因） */
@Composable
fun statusText(row: TransferRow): String {
    val context = LocalContext.current
    val item = row.item
    val size = formatBytes(context, item.size)
    return when (item.state) {
        TransferState.QUEUED -> stringResource(R.string.transfer_state_queued)
        TransferState.PREPARING -> stringResource(R.string.transfer_state_preparing)
        TransferState.RUNNING -> {
            val progress = stringResource(R.string.transfer_progress, formatBytes(context, item.bytesDone.coerceAtMost(item.size)), size)
            if (row.speedBytesPerSecond > 0) {
                val speed = stringResource(R.string.transfer_speed, formatBytes(context, row.speedBytesPerSecond))
                val eta = row.etaSeconds?.let { stringResource(R.string.transfer_eta, DateUtils.formatElapsedTime(it)) }
                listOfNotNull(progress, speed, eta).joinToString(" · ")
            } else {
                progress
            }
        }
        TransferState.FINISHING -> stringResource(R.string.transfer_state_finishing)
        TransferState.COMPLETED -> stringResource(R.string.transfer_state_completed, size)
        TransferState.PAUSED -> stringResource(
            R.string.transfer_state_paused,
            formatBytes(context, item.bytesDone.coerceAtMost(item.size)),
            size,
        )
        TransferState.CANCELED -> stringResource(R.string.transfer_state_canceled)
        TransferState.FAILED -> when {
            row.rejection != null -> rejectionText(row.rejection!!)
            row.needsConflictResolution -> stringResource(R.string.transfer_conflict)
            else -> failureText(context, item)
        }
    }
}

/** 行的位置说明：上传为目标目录，下载为保存位置 */
@Composable
fun locationText(item: TransferItem): String {
    val context = LocalContext.current
    return if (item.kind == TransferKind.UPLOAD) {
        val dir = CrUri.parseOrNull(item.targetUri)?.let { it.parent() ?: it }
        stringResource(R.string.transfer_upload_to, dir?.let { displayPath(context, it) } ?: item.targetUri)
    } else {
        stringResource(
            if (item.destPath?.startsWith("content://") == true) R.string.transfer_saved_public else R.string.transfer_saved_app,
        )
    }
}
