package plus.ling.opencloudreve.ui.admin

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.EntityType
import plus.ling.opencloudreve.core.model.User

/** 服务端枚举值 → 本地化文案；未知值原样显示 */
@Composable
private fun labelOr(@StringRes res: Int?, raw: String): String = res?.let { stringResource(it) } ?: raw.ifEmpty { EMPTY_VALUE }

@StringRes
private fun userStatusRes(status: String): Int? = when (status) {
    User.STATUS_ACTIVE -> R.string.admin_user_status_active
    User.STATUS_INACTIVE -> R.string.admin_user_status_inactive
    User.STATUS_MANUAL_BANNED -> R.string.admin_user_status_manual_banned
    User.STATUS_SYS_BANNED -> R.string.admin_user_status_sys_banned
    else -> null
}

@Composable
internal fun userStatusLabel(status: String): String = labelOr(userStatusRes(status), status)

internal fun userStatusTone(status: String): ChipTone = when (status) {
    User.STATUS_ACTIVE -> ChipTone.Positive
    User.STATUS_INACTIVE -> ChipTone.Neutral
    else -> ChipTone.Negative
}

/** 任务状态（`queue.TaskStatus`） */
internal val TASK_STATUSES = listOf("queued", "processing", "suspending", "error", "canceled", "completed")

/** 常见任务类型（筛选用） */
internal val TASK_TYPES = listOf(
    "create_archive",
    "extract_archive",
    "relocate",
    "remote_download",
    "import",
    "media_meta",
    "entity_recycle_routine",
    "explicit_entity_recycle",
    "upload_sentinel_check",
)

@StringRes
private fun taskStatusRes(status: String): Int? = when (status) {
    "queued" -> R.string.admin_task_status_queued
    "processing" -> R.string.admin_task_status_processing
    "suspending" -> R.string.admin_task_status_suspending
    "error" -> R.string.admin_task_status_error
    "canceled" -> R.string.admin_task_status_canceled
    "completed" -> R.string.admin_task_status_completed
    else -> null
}

@Composable
internal fun taskStatusLabel(status: String): String = labelOr(taskStatusRes(status), status)

internal fun taskStatusTone(status: String): ChipTone = when (status) {
    "completed" -> ChipTone.Positive
    "error" -> ChipTone.Negative
    "processing", "suspending" -> ChipTone.Warning
    else -> ChipTone.Neutral
}

@StringRes
private fun taskTypeRes(type: String): Int? = when (type) {
    "create_archive" -> R.string.admin_task_type_create_archive
    "extract_archive" -> R.string.admin_task_type_extract_archive
    "relocate" -> R.string.admin_task_type_relocate
    "remote_download" -> R.string.admin_task_type_remote_download
    "import" -> R.string.admin_task_type_import
    "media_meta" -> R.string.admin_task_type_media_meta
    "entity_recycle_routine" -> R.string.admin_task_type_entity_recycle_routine
    "explicit_entity_recycle" -> R.string.admin_task_type_explicit_entity_recycle
    "upload_sentinel_check" -> R.string.admin_task_type_upload_sentinel_check
    else -> null
}

@Composable
internal fun taskTypeLabel(type: String): String = labelOr(taskTypeRes(type), type)

@StringRes
private fun queueNameRes(name: String): Int? = when (name) {
    "media_meta" -> R.string.admin_queue_name_media_meta
    "recycle" -> R.string.admin_queue_name_recycle
    "io_intense" -> R.string.admin_queue_name_io_intense
    "remote_download" -> R.string.admin_queue_name_remote_download
    "thumb" -> R.string.admin_queue_name_thumb
    else -> null
}

@Composable
internal fun queueNameLabel(name: String): String = labelOr(queueNameRes(name), name)

internal const val NODE_STATUS_ACTIVE = "active"
internal const val NODE_STATUS_SUSPENDED = "suspended"

@Composable
internal fun nodeStatusLabel(status: String): String = labelOr(
    when (status) {
        NODE_STATUS_ACTIVE -> R.string.admin_node_status_active
        NODE_STATUS_SUSPENDED -> R.string.admin_node_status_suspended
        else -> null
    },
    status,
)

@Composable
internal fun nodeTypeLabel(type: String): String = labelOr(
    when (type) {
        "master" -> R.string.admin_node_type_master
        "slave" -> R.string.admin_node_type_slave
        else -> null
    },
    type,
)

@Composable
internal fun entityTypeLabel(type: Int): String = when (type) {
    EntityType.VERSION -> stringResource(R.string.admin_entity_version)
    EntityType.THUMBNAIL -> stringResource(R.string.admin_entity_thumbnail)
    EntityType.LIVE_PHOTO -> stringResource(R.string.admin_entity_live_photo)
    else -> type.toString()
}

/** 排序字段文案 */
@Composable
internal fun orderLabel(key: String): String = labelOr(
    when (key) {
        "id" -> R.string.admin_order_id
        "nick" -> R.string.admin_order_nick
        "email" -> R.string.admin_order_email
        "storage" -> R.string.admin_order_storage
        "updated_at" -> R.string.admin_order_updated_at
        "created_at" -> R.string.admin_order_created_at
        "views" -> R.string.admin_order_views
        "downloads" -> R.string.admin_order_downloads
        "remain_downloads" -> R.string.admin_order_remain_downloads
        "name" -> R.string.admin_order_name
        "size" -> R.string.admin_order_size
        "weight" -> R.string.admin_order_weight
        else -> null
    },
    key,
)
