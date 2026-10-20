package plus.ling.opencloudreve.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import plus.ling.opencloudreve.MainActivity
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.ui.files.formatBytes

/**
 * 传输通知：进行中的任务各有一条前台进度通知（WorkManager 前台服务，类型 dataSync），
 * 结束后按方向汇总为一条“已完成/失败”通知。点击进入“任务”Tab。
 */
internal class TransferNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        ensureChannels(context)
    }

    /** 进行中的前台通知 */
    fun foreground(item: TransferItem, bytesDone: Long, waiting: Boolean = false): ForegroundInfo {
        val percent = if (item.size > 0) ((bytesDone.coerceIn(0, item.size) * 100) / item.size).toInt() else 0
        val title = context.getString(
            if (item.kind == TransferKind.UPLOAD) R.string.transfer_notif_uploading else R.string.transfer_notif_downloading,
            item.displayName,
        )
        val text = if (waiting) {
            context.getString(R.string.transfer_notif_preparing)
        } else {
            context.getString(
                R.string.transfer_notif_progress,
                formatBytes(context, bytesDone.coerceAtMost(item.size)),
                formatBytes(context, item.size),
                percent,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(if (item.kind == TransferKind.UPLOAD) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent, waiting || item.size <= 0)
            .setContentIntent(openTasksIntent(context))
            .build()
        val id = progressId(item.id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /** 任务结束（完成/失败）后的汇总通知；暂停/取消不提示 */
    fun finished(item: TransferItem) {
        if (item.state != TransferState.COMPLETED && item.state != TransferState.FAILED) return
        if (!canPost()) return
        val upload = item.kind == TransferKind.UPLOAD
        val ok = item.state == TransferState.COMPLETED
        val title = context.getString(
            when {
                upload && ok -> R.string.transfer_notif_upload_done
                upload -> R.string.transfer_notif_upload_failed
                ok -> R.string.transfer_notif_download_done
                else -> R.string.transfer_notif_download_failed
            },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(if (upload) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(item.displayName)
            .setAutoCancel(true)
            .setContentIntent(openTasksIntent(context))
            .build()
        try {
            manager.notify(if (upload) ID_UPLOAD_RESULT else ID_DOWNLOAD_RESULT, notification)
        } catch (_: SecurityException) {
            // 权限在检查后被撤销
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_PROGRESS = "transfer_progress"
        private const val CHANNEL_RESULT = "transfer_result"
        private const val ID_UPLOAD_RESULT = 0x7E5_0001
        private const val ID_DOWNLOAD_RESULT = 0x7E5_0002

        /** 每个任务一个进度通知 id（避开结果通知的固定 id） */
        fun progressId(taskId: String): Int = (taskId.hashCode() and 0x3FFF_FFFF) or 0x4000_0000

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    context.getString(R.string.transfer_channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT,
                    context.getString(R.string.transfer_channel_result),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        /** 点击通知：打开主界面的“任务”Tab */
        fun openTasksIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TASKS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
