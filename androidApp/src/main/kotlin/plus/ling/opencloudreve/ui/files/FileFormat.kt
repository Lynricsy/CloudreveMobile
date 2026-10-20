package plus.ling.opencloudreve.ui.files

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

/**
 * 字节数短格式（1.2 MB）。与 Web 端 `sizeToString` 一致按 1024 进位：
 * 容量配额通常是 2 的幂（1 GB = 2^30），系统 `Formatter` 的十进制单位会显示成“1.1 GB”。
 */
@Suppress("UNUSED_PARAMETER")
fun formatBytes(context: Context, bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    if (value < 1024) return "$value B"
    var unit = 0
    var scaled = value.toDouble()
    while (scaled >= 1024 && unit < SIZE_UNITS.lastIndex) {
        scaled /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", scaled, SIZE_UNITS[unit])
}

@Composable
fun bytesText(bytes: Long): String = formatBytes(LocalContext.current, bytes)

/**
 * 站点配置里的显示名可能是 Web 端的 i18n 键（如 viewer 模板的 `fileManager.diagram`），
 * 已知键映射到本地字符串，其他原样返回。
 */
fun siteText(context: Context, raw: String): String {
    val res = when (raw) {
        "fileManager.diagram" -> R.string.site_text_diagram
        "fileManager.whiteboard" -> R.string.site_text_whiteboard
        "fileManager.text" -> R.string.site_text_text
        "fileManager.markdownEditor" -> R.string.site_text_markdown_editor
        "fileManager.monacoEditor" -> R.string.site_text_monaco_editor
        else -> return raw
    }
    return context.getString(res)
}

/** 新建文件模板的显示名：翻译 i18n 键，缺省用大写扩展名 */
@Composable
fun templateLabel(displayName: String, ext: String): String =
    siteText(LocalContext.current, displayName).ifBlank { ext.uppercase() }

/** 日期时间（系统短格式） */
fun formatDateTime(instant: Instant): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(instant.toEpochMilliseconds()))

/** 相对时间（“3 分钟前”）；一分钟内显示“刚刚”（系统按分钟精度会给出“0 分钟前”），一周以上显示日期 */
fun formatRelative(context: Context, instant: Instant, now: Instant = Clock.System.now()): String {
    val millis = instant.toEpochMilliseconds()
    val nowMillis = now.toEpochMilliseconds()
    if (abs(nowMillis - millis) < DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.time_just_now)
    return DateUtils.getRelativeTimeSpanString(
        millis,
        nowMillis,
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

/** URI 的可读完整路径（“我的文件/文档/a.txt”） */
fun displayPath(context: Context, uri: CrUri): String =
    if (uri.isRoot()) folderTitle(context, uri) else folderTitle(context, uri.root()) + uri.path

/** 回收站项的原位置（`sys:restore_uri` 的上级目录，可读形式） */
fun restoreLocation(context: Context, file: FileItem): String? {
    val raw = file.metadata[MetadataKeys.RESTORE_URI] ?: return null
    val uri = CrUri.parseOrNull(raw) ?: return raw
    return displayPath(context, uri.parent() ?: uri)
}

/** 回收站项距离被自动清除的剩余时间文案（向上取整，刚删除的 7 天保留期显示“7 天后”）；没有该元数据返回 null */
fun collectCountdown(context: Context, file: FileItem, now: Instant = Clock.System.now()): String? {
    val seconds = file.metadata[MetadataKeys.EXPECTED_COLLECT_TIME]?.toLongOrNull() ?: return null
    val remain = seconds - now.epochSeconds
    return when {
        remain <= 0 -> context.getString(R.string.trash_collect_soon)
        remain > 86_400 -> ((remain + 86_399) / 86_400).toInt().let {
            context.resources.getQuantityString(R.plurals.trash_collect_days, it, it)
        }
        else -> ((remain + 3_599) / 3_600).toInt().let {
            context.resources.getQuantityString(R.plurals.trash_collect_hours, it, it)
        }
    }
}

/** 回收站条目副标题：原位置 · 清除倒计时 */
fun trashSubtitle(context: Context, file: FileItem): String = listOfNotNull(
    restoreLocation(context, file)?.let { context.getString(R.string.trash_original_location, it) },
    collectCountdown(context, file),
).joinToString(" · ")
