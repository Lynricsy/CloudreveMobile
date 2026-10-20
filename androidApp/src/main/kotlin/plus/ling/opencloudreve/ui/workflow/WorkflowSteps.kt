package plus.ling.opencloudreve.ui.workflow

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.ui.graphics.vector.ImageVector
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.TaskStatus
import plus.ling.opencloudreve.core.model.TaskType
import plus.ling.opencloudreve.core.model.WorkflowTask

/** 任务阶段时间线的一步；[phase] 为 `summary.phase` 的取值（空串表示由位置推断） */
internal data class TaskStep(@param:StringRes val title: Int, val phase: String = "", val supportsProgress: Boolean = false)

private val queued = TaskStep(R.string.task_step_queued)
private val finished = TaskStep(R.string.task_step_finished)

/** 与网页端 `TaskProgress.tsx` 的 stepOptions 一致：主节点 / 从节点两套 */
private val masterSteps: Map<String, List<TaskStep>> = mapOf(
    TaskType.REMOTE_DOWNLOAD to listOf(
        queued,
        TaskStep(R.string.task_step_download, "monitor"),
        TaskStep(R.string.task_step_transfer, "transfer", true),
        TaskStep(R.string.task_step_seeding, "seeding"),
        finished,
    ),
    TaskType.IMPORT to listOf(queued, TaskStep(R.string.task_step_importing, supportsProgress = true)),
    TaskType.RELOCATE to listOf(
        queued,
        TaskStep(R.string.task_step_indexing),
        TaskStep(R.string.task_step_transfer, "transfer", true),
        TaskStep(R.string.task_step_committing, "finish"),
    ),
    TaskType.EXTRACT_ARCHIVE to listOf(
        queued,
        TaskStep(R.string.task_step_download_zip, "download_zip", true),
        TaskStep(R.string.task_step_extracting, supportsProgress = true),
    ),
    TaskType.CREATE_ARCHIVE to listOf(
        queued,
        TaskStep(R.string.task_step_prepare),
        TaskStep(R.string.task_step_compress, "compress_files", true),
        TaskStep(R.string.task_step_transfer, "upload_archive", true),
    ),
    TaskType.GDRIVE_RESTORE to listOf(queued, TaskStep(R.string.task_step_restoring, supportsProgress = true), finished),
)

private val slaveSteps: Map<String, List<TaskStep>> = mapOf(
    TaskType.EXTRACT_ARCHIVE to listOf(
        queued,
        TaskStep(R.string.task_step_send),
        TaskStep(R.string.task_step_extracting, "await_slave_complete", true),
    ),
    TaskType.CREATE_ARCHIVE to listOf(
        queued,
        TaskStep(R.string.task_step_indexing),
        TaskStep(R.string.task_step_compress, "await_slave_compressing", true),
        TaskStep(R.string.task_step_transfer, "await_slave_uploading", true),
        TaskStep(R.string.task_step_committing, "complete_upload"),
    ),
)

/** 任务的阶段列表（未知类型为空） */
internal fun taskSteps(task: WorkflowTask): List<TaskStep> {
    val slave = task.node?.type == "slave"
    return (if (slave) slaveSteps[task.type] ?: masterSteps[task.type] else masterSteps[task.type]).orEmpty()
}

/** 当前所在步骤：排队 0；完成 = steps.size；否则按 phase 匹配（缺省第 1 步） */
internal fun activeStep(task: WorkflowTask, steps: List<TaskStep>): Int = when (task.status) {
    TaskStatus.QUEUED -> 0
    TaskStatus.COMPLETED -> steps.size
    else -> {
        var active = 1
        for (i in 1 until steps.size) if (steps[i].phase.isNotEmpty() && steps[i].phase == task.summary?.phase) active = i
        active
    }
}

internal fun taskTypeIcon(type: String): ImageVector = when (type) {
    TaskType.CREATE_ARCHIVE -> Icons.Outlined.Archive
    TaskType.EXTRACT_ARCHIVE -> Icons.Outlined.Unarchive
    TaskType.RELOCATE -> Icons.Outlined.SwapHoriz
    TaskType.REMOTE_DOWNLOAD -> Icons.Outlined.CloudDownload
    TaskType.IMPORT -> Icons.AutoMirrored.Outlined.Input
    TaskType.GDRIVE_BACKUP -> Icons.Outlined.Backup
    TaskType.GDRIVE_RESTORE -> Icons.Outlined.Restore
    else -> Icons.Outlined.PendingActions
}

@StringRes
internal fun taskTypeLabel(type: String): Int = when (type) {
    TaskType.CREATE_ARCHIVE -> R.string.task_type_create_archive
    TaskType.EXTRACT_ARCHIVE -> R.string.task_type_extract_archive
    TaskType.RELOCATE -> R.string.task_type_relocate
    TaskType.REMOTE_DOWNLOAD -> R.string.task_type_remote_download
    TaskType.IMPORT -> R.string.task_type_import
    TaskType.GDRIVE_BACKUP -> R.string.task_type_gdrive_backup
    TaskType.GDRIVE_RESTORE -> R.string.task_type_gdrive_restore
    else -> R.string.task_type_unknown
}

@StringRes
internal fun taskStatusLabel(status: String): Int = when (status) {
    TaskStatus.QUEUED -> R.string.task_status_queued
    TaskStatus.PROCESSING -> R.string.task_status_processing
    TaskStatus.SUSPENDING -> R.string.task_status_suspending
    TaskStatus.ERROR -> R.string.task_status_error
    TaskStatus.CANCELED -> R.string.task_status_canceled
    TaskStatus.COMPLETED -> R.string.task_status_completed
    else -> R.string.task_status_unknown
}
