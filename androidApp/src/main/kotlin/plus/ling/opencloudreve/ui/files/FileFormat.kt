package plus.ling.opencloudreve.ui.files

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import java.text.DateFormat
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant

/** 字节数的本地化短格式（1.2 MB） */
fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

@Composable
fun bytesText(bytes: Long): String = formatBytes(LocalContext.current, bytes)

/** 日期时间（系统短格式） */
fun formatDateTime(instant: Instant): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(instant.toEpochMilliseconds()))

/** 相对时间（“3 分钟前”）；一周以上显示日期 */
fun formatRelative(instant: Instant, now: Instant = Clock.System.now()): String {
    val millis = instant.toEpochMilliseconds()
    return DateUtils.getRelativeTimeSpanString(
        millis,
        now.toEpochMilliseconds(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

/** 目录 URI 的显示名：根目录按文件系统给出“我的文件”“回收站”等 */
@Composable
fun folderTitle(uri: String): String {
    val parsed = CrUri.parseOrNull(uri) ?: return uri
    return folderTitle(LocalContext.current, parsed)
}

fun folderTitle(context: Context, uri: CrUri): String {
    if (!uri.isRoot()) return uri.name()
    return when (uri.fs) {
        CrUri.FS_MY -> if (uri.id == null) context.getString(R.string.files_my) else context.getString(R.string.files_user_root, uri.id)
        CrUri.FS_TRASH -> context.getString(R.string.files_trash)
        CrUri.FS_SHARED_WITH_ME -> context.getString(R.string.files_shared_with_me)
        CrUri.FS_SHARE -> context.getString(R.string.files_share_root)
        else -> uri.fs
    }
}

/** 回收站项的原位置（`sys:restore_uri` 的上级目录，可读形式） */
fun restoreLocation(context: Context, file: FileItem): String? {
    val raw = file.metadata[MetadataKeys.RESTORE_URI] ?: return null
    val uri = CrUri.parseOrNull(raw) ?: return raw
    val parent = uri.parent() ?: return folderTitle(context, uri)
    return if (parent.isRoot()) folderTitle(context, parent) else folderTitle(context, parent.root()) + parent.path
}

/** 回收站项距离被自动清除的剩余时间文案；没有该元数据返回 null */
fun collectCountdown(context: Context, file: FileItem, now: Instant = Clock.System.now()): String? {
    val seconds = file.metadata[MetadataKeys.EXPECTED_COLLECT_TIME]?.toLongOrNull() ?: return null
    val remain = seconds - now.epochSeconds
    return when {
        remain <= 0 -> context.getString(R.string.trash_collect_soon)
        remain >= 86_400 -> context.resources.getQuantityString(R.plurals.trash_collect_days, (remain / 86_400).toInt(), (remain / 86_400).toInt())
        else -> context.resources.getQuantityString(R.plurals.trash_collect_hours, ((remain + 3_599) / 3_600).toInt(), ((remain + 3_599) / 3_600).toInt())
    }
}

/** 回收站条目副标题：原位置 · 清除倒计时 */
fun trashSubtitle(context: Context, file: FileItem): String = listOfNotNull(
    restoreLocation(context, file)?.let { context.getString(R.string.trash_original_location, it) },
    collectCountdown(context, file),
).joinToString(" · ")
