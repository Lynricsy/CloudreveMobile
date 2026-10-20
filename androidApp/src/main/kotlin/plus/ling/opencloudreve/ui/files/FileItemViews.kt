package plus.ling.opencloudreve.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.CancellationException
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.model.FileItem

/** 缩略图地址提供者（列表 VM 的 `thumbnailUrl`）；返回 null 表示不显示缩略图 */
fun interface ThumbnailLoader {
    suspend fun url(file: FileItem): String?
}

/**
 * 缩略图（签名 URL，无需鉴权头）；没有缩略图或加载失败时显示类型图标。
 * 目录与 `thumb:disabled` 的文件不请求（由仓库判断）。
 */
@Composable
fun FileThumbnail(
    file: FileItem,
    loader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    iconSize: Dp,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Crop,
) {
    val url by produceState<String?>(null, file.path, file.primaryEntity, file.thumbDisabled, loader) {
        value = if (loader == null || file.isFolder) {
            null
        } else {
            try {
                loader.url(file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        val icon: @Composable () -> Unit = { FileTypeIconView(file, iconSize) }
        val u = url
        if (u == null) {
            icon()
        } else {
            SubcomposeAsyncImage(
                model = u,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { icon() },
                error = { icon() },
            )
        }
    }
}

/** 名称后的角标：软链接、已分享 */
@Composable
private fun FileBadges(file: FileItem) {
    if (file.isSymbolic) {
        Icon(
            Icons.Filled.Link,
            contentDescription = stringResource(R.string.file_badge_link),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
    }
    if (file.shared) {
        Icon(
            Icons.Filled.Share,
            contentDescription = stringResource(R.string.file_badge_shared),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 副标题：回收站显示原位置与清除倒计时；软链接隐藏大小 */
@Composable
private fun fileSubtitle(file: FileItem, inTrash: Boolean): String {
    val context = LocalContext.current
    return remember(file, inTrash, context) {
        if (inTrash) {
            trashSubtitle(context, file)
        } else {
            val time = (file.updatedAt ?: file.createdAt)?.let { formatRelative(it) }
            val size = if (file.isFolder || file.isSymbolic) null else formatBytes(context, file.size)
            listOfNotNull(size, time).joinToString(" · ")
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = stringResource(if (selected) R.string.selection_selected else R.string.selection_not_selected),
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

/** 列表行 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: FileItem,
    loader: ThumbnailLoader?,
    inTrash: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val moreLabel = stringResource(R.string.file_more_actions)
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.selection_enter)),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        leadingContent = {
            FileThumbnail(file, loader, Modifier.size(40.dp), iconSize = 32.dp)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                FileBadges(file)
            }
        },
        supportingContent = {
            val text = supporting ?: fileSubtitle(file, inTrash)
            if (text.isNotEmpty()) Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = when {
            selecting -> ({ SelectionMark(selected) })
            onMore != null -> ({
                IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = moreLabel) }
            })
            else -> null
        },
    )
}

/** 网格单元：上方缩略图/图标，下方名称 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    file: FileItem,
    loader: ThumbnailLoader?,
    inTrash: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        modifier = modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.selection_enter))
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box {
            FileThumbnail(
                file,
                loader,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                iconSize = 48.dp,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            )
            if (selecting) {
                SelectionMark(
                    selected,
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FileBadges(file)
                }
                val sub = fileSubtitle(file, inTrash)
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onMore != null && !selecting) {
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.file_more_actions))
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

/** 首次加载的骨架行 */
@Composable
fun FileRowSkeleton(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    val loading = stringResource(R.string.files_loading)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = loading },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(block, RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(180.dp).height(14.dp).background(block, RoundedCornerShape(4.dp)))
            Box(Modifier.width(110.dp).height(10.dp).background(block, RoundedCornerShape(4.dp)))
        }
    }
}
