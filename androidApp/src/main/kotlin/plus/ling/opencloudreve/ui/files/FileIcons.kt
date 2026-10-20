package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileTypeIcon
import plus.ling.opencloudreve.core.model.MetadataKeys

/**
 * 文件类型图标表：站点 `explorer.icons`（缺失时用服务端默认表 `inventory/setting.go:defaultIcons`），
 * 文件元数据 `customize:emoji` 覆盖图标、`customize:icon_color` 覆盖颜色（规格 §7.4）。
 */
@Immutable
class FileIconTheme(icons: List<FileTypeIcon>, private val siteUrl: String) {
    private val byExt: Map<String, FileTypeIcon> = buildMap {
        icons.forEach { setting -> setting.exts.forEach { putIfAbsent(it.lowercase(), setting) } }
    }

    /** 文件（非目录）对应的图标设置；未知扩展名为 null（显示通用文档图标） */
    fun settingFor(file: FileItem): FileTypeIcon? = if (file.isFolder) null else byExt[file.extension]

    /** `img`/`iconify` 的绝对地址 */
    fun imageUrl(setting: FileTypeIcon, color: Color?): String? {
        setting.img?.takeIf { it.isNotBlank() }?.let { img ->
            return if (img.startsWith("http://") || img.startsWith("https://")) img else siteUrl.trimEnd('/') + "/" + img.trimStart('/')
        }
        // iconify 图标名形如 `fluent:slide-text-24-filled`，与网页端一样通过 Iconify API 取 SVG
        val iconify = setting.iconify?.takeIf { ':' in it } ?: return null
        val (prefix, name) = iconify.split(':', limit = 2)
        val colorQuery = color?.let { "?color=%23" + it.toHex() }.orEmpty()
        return "https://api.iconify.design/$prefix/$name.svg$colorQuery"
    }

    companion object {
        /** 服务端默认图标表（站点未返回 `icons` 时使用） */
        val DefaultIcons: List<FileTypeIcon> = listOf(
            FileTypeIcon(listOf("mp3", "flac", "ape", "wav", "acc", "ogg", "m4a"), icon = "audio", color = "#651fff"),
            FileTypeIcon(listOf("m3u8", "mp4", "flv", "avi", "wmv", "mkv", "rm", "rmvb", "mov", "ogv"), icon = "video", color = "#d50000"),
            FileTypeIcon(
                listOf("bmp", "iff", "png", "gif", "jpg", "jpeg", "psd", "svg", "webp", "heif", "heic", "tiff", "avif"),
                icon = "image",
                color = "#d32f2f",
            ),
            FileTypeIcon(
                listOf("3fr", "ari", "arw", "bay", "braw", "crw", "cr2", "cr3", "cap", "dcs", "dcr", "dng", "drf", "eip", "erf", "fff", "gpr", "iiq", "k25", "kdc", "mdc", "mef", "mos", "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx", "pxn", "r3d", "raf", "raw", "rwl", "rw2", "rwz", "sr2", "srf", "srw", "tif", "x3f"),
                icon = "raw",
                color = "#d32f2f",
            ),
            FileTypeIcon(listOf("pdf"), icon = "pdf", color = "#f44336"),
            FileTypeIcon(listOf("doc", "docx"), icon = "word", color = "#538ce5"),
            FileTypeIcon(listOf("ppt", "pptx"), icon = "ppt", color = "#EF633F"),
            FileTypeIcon(listOf("xls", "xlsx", "csv"), icon = "excel", color = "#4caf50"),
            FileTypeIcon(listOf("txt", "html"), icon = "text", color = "#607d8b"),
            FileTypeIcon(listOf("torrent"), icon = "torrent", color = "#5c6bc0"),
            FileTypeIcon(listOf("zip", "gz", "xz", "tar", "rar", "7z", "bz2", "z"), icon = "zip", color = "#f9a825"),
            FileTypeIcon(listOf("exe", "msi"), icon = "exe", color = "#1a237e"),
            FileTypeIcon(listOf("apk"), icon = "android", color = "#8bc34a"),
            FileTypeIcon(listOf("go"), icon = "go", color = "#16b3da"),
            FileTypeIcon(listOf("py"), icon = "python", color = "#3776ab"),
            FileTypeIcon(listOf("c"), icon = "c", color = "#a4c639"),
            FileTypeIcon(listOf("cpp"), icon = "cpp", color = "#f34b7d"),
            FileTypeIcon(listOf("js", "jsx"), icon = "js", color = "#f4d003"),
            FileTypeIcon(listOf("epub"), icon = "book", color = "#81b315"),
            FileTypeIcon(listOf("rs"), icon = "rust", color = "#000", colorDark = "#fff"),
            FileTypeIcon(listOf("drawio"), icon = "flowchart", color = "#F08705"),
            FileTypeIcon(listOf("dwb"), icon = "whiteboard", color = "#F08705"),
            FileTypeIcon(listOf("md"), icon = "markdown", color = "#383838", colorDark = "#cbcbcb"),
            FileTypeIcon(listOf("excalidraw"), img = "/static/img/viewers/excalidraw.svg"),
        )

        val Default = FileIconTheme(DefaultIcons, "")

        /** 前端 `builtInIcons` 名称 → Material 图标 */
        fun builtIn(name: String?): ImageVector? = when (name) {
            "audio" -> Icons.Filled.AudioFile
            "video" -> Icons.Filled.VideoFile
            "image" -> Icons.Filled.Image
            "raw" -> Icons.Filled.RawOn
            "pdf" -> Icons.Filled.PictureAsPdf
            "word" -> Icons.AutoMirrored.Filled.Article
            "ppt" -> Icons.Filled.Slideshow
            "excel" -> Icons.Filled.TableChart
            "text" -> Icons.AutoMirrored.Filled.TextSnippet
            "torrent" -> Icons.Filled.CloudDownload
            "zip" -> Icons.Filled.FolderZip
            "exe" -> Icons.Filled.Apps
            "android" -> Icons.Filled.Android
            "js" -> Icons.Filled.Javascript
            "go", "c", "cpp", "python", "rust" -> Icons.Filled.Code
            "book" -> Icons.AutoMirrored.Filled.MenuBook
            "flowchart" -> Icons.Filled.AccountTree
            "whiteboard" -> Icons.Filled.Draw
            "markdown" -> Icons.AutoMirrored.Filled.Article
            else -> null
        }
    }
}

val LocalFileIconTheme = staticCompositionLocalOf { FileIconTheme.Default }

/** 解析 `#rgb` / `#rrggbb` / `#aarrggbb`；无效返回 null */
internal fun parseHexColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return null
    val full = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("")
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return full.toLongOrNull(16)?.let { Color(it.toInt()) }
}

private fun Color.toHex(): String {
    val rgb = ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
    return rgb.toString(16).padStart(6, '0')
}

/** 文件/目录图标（不含缩略图） */
@Composable
fun FileTypeIconView(file: FileItem, size: Dp, modifier: Modifier = Modifier) {
    val emoji = file.metadata[MetadataKeys.EMOJI]?.takeIf { it.isNotBlank() }
    if (emoji != null) {
        val fontSize = with(LocalDensity.current) { (size * 0.8f).toSp() }
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = fontSize)
        }
        return
    }
    val dark = isSystemInDarkTheme()
    val custom = parseHexColor(file.metadata[MetadataKeys.ICON_COLOR])
    if (file.isFolder) {
        Icon(Icons.Filled.Folder, contentDescription = null, modifier = modifier.size(size), tint = custom ?: MaterialTheme.colorScheme.primary)
        return
    }
    val theme = LocalFileIconTheme.current
    val setting = theme.settingFor(file)
    val color = custom ?: parseHexColor(if (dark) setting?.colorDark ?: setting?.color else setting?.color)
    val builtIn = FileIconTheme.builtIn(setting?.icon)
    val imageUrl = if (builtIn == null && setting != null) theme.imageUrl(setting, color) else null
    when {
        builtIn != null -> Icon(builtIn, contentDescription = null, modifier = modifier.size(size), tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
        imageUrl != null -> AsyncImage(model = imageUrl, contentDescription = null, modifier = modifier.size(size))
        else -> Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
