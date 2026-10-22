package plus.ling.opencloudreve.ui.viewer

import android.content.res.Configuration
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.di.DataRepositories
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.viewer.SubtitleOption
import plus.ling.opencloudreve.core.presentation.viewer.VideoChapter
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.viewer.ViewerVideoIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerVideoState
import plus.ling.opencloudreve.core.presentation.viewer.ViewerVideoViewModel
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.common.SystemBarsHidden

/** Media3 支持的外挂字幕格式 → MIME（vrr 不受支持，不加载） */
private fun subtitleMime(format: String): String? = when (format) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    else -> null
}

/** 视频预览：ExoPlayer（含 HLS）、同目录外挂字幕、章节跳转；签名地址刷新、旋转重建时保持进度与播放/暂停 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoViewerScreen(
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
    vm: ViewerVideoViewModel = koinViewModel(key = "video:${params.file.path}:${params.version}") { parametersOf(params) },
    repositories: DataRepositories = koinInject(),
    accounts: AccountRepository = koinInject(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // m3u8：入口与其中的相对分片/子播放列表都走掩码地址，加载时逐个签名（见 HlsMask）
    val linked = remember(params.file) { CrUri.parseOrNull(params.file.linkedUri) }
    val hlsEntry = remember(linked) { linked?.takeIf { state.isHls }?.let(HlsMask::entry) }
    // 旋转等配置变更重建页面时恢复的进度与播放/暂停（按文件 + 版本区分）
    val resume = rememberSaveable(params.file.path, params.version, saver = PlaybackResume.Saver) { PlaybackResume() }
    val player = remember {
        val dataSource = if (linked != null) {
            HlsDataSourceFactory(context, HlsSigner(params.accountId, linked, params.version, repositories, accounts))
        } else {
            DefaultDataSource.Factory(context)
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply { playWhenReady = resume.playWhenReady }
            .also { resume.player = it }
    }
    val onError by rememberUpdatedState { vm.on(ViewerVideoIntent.Retry) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 地址过期（403/404 等）时重新取地址；新地址到达后按原进度继续
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) onError()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            resume.capture()
            resume.player = null
            player.release()
        }
    }

    val videoUrl = state.url?.url
    // HLS 的签名由数据源逐个完成，签名地址刷新不必重新装载
    val sourceUri = videoUrl?.let { hlsEntry ?: it }
    val subtitle = state.selectedSubtitle
    val subtitleUrl = state.subtitleUrl?.url
    LaunchedEffect(sourceUri, subtitle?.file?.path, subtitleUrl) {
        if (sourceUri == null) return@LaunchedEffect
        // 选了字幕但地址未到：等字幕就绪再一次性设置，避免重复缓冲
        if (subtitle != null && subtitleUrl == null && state.subtitleLoading) return@LaunchedEffect
        // 首次装载用恢复的进度；之后（换地址/字幕）保持当前进度
        val position = if (player.currentMediaItem == null) resume.positionMs else player.currentPosition
        val builder = MediaItem.Builder().setUri(sourceUri)
        if (state.isHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val mime = subtitle?.let { subtitleMime(it.format) }
        if (subtitleUrl != null && mime != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitleUrl.toUri())
                        .setMimeType(mime)
                        .setLabel(subtitle.file.name)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                        .build(),
                ),
            )
        }
        player.setMediaItem(builder.build(), position)
        player.prepare()
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 横屏全屏：播放控件隐藏时同时隐藏顶栏与系统栏，点按画面恢复
    var controlsVisible by remember { mutableStateOf(true) }
    val fullscreen = landscape && !controlsVisible
    SystemBarsHidden(fullscreen)
    ViewerScaffold(
        title = state.file.name,
        menu = menu,
        onClose = onClose,
        showTopBar = !fullscreen,
        extraMenu = { dismiss -> SubtitleMenuItems(state, dismiss) { vm.on(ViewerVideoIntent.SelectSubtitle(it)) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxWidth().let { if (landscape) it.weight(1f) else it.aspectRatio(16f / 9f) }.background(Color.Black),
            ) {
                when {
                    state.error != null && videoUrl == null ->
                        CenteredError(state.error!!.message(), onRetry = { vm.on(ViewerVideoIntent.Retry) })
                    state.loading -> CenteredProgress()
                    else -> AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                setShowSubtitleButton(true)
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!landscape) {
                VideoDetails(
                    state,
                    onSeek = { player.seekTo((it.startSeconds * 1000).toLong()) },
                    onSelectSubtitle = { vm.on(ViewerVideoIntent.SelectSubtitle(it)) },
                )
            }
        }
    }
}

/**
 * 页面重建（旋转等配置变更）后要恢复的播放进度与播放/暂停。保存时从仍存活的 [player] 读取当前值；
 * 页面销毁前 [capture] 一次，供导航返回栈恢复时使用。
 */
private class PlaybackResume(var positionMs: Long = 0L, var playWhenReady: Boolean = true) {
    var player: Player? = null

    fun capture() {
        val p = player ?: return
        positionMs = p.currentPosition
        playWhenReady = p.playWhenReady
    }

    companion object {
        val Saver = Saver<PlaybackResume, List<Any>>(
            save = { it.capture(); listOf(it.positionMs, it.playWhenReady) },
            restore = { PlaybackResume(it[0] as Long, it[1] as Boolean) },
        )
    }
}

/** 溢出菜单中的字幕选择 */
@Composable
private fun SubtitleMenuItems(state: ViewerVideoState, dismiss: () -> Unit, onSelect: (String?) -> Unit) {
    if (state.subtitles.isEmpty()) return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.viewer_subtitle_off)) },
        leadingIcon = { Icon(Icons.Default.ClosedCaption, contentDescription = null) },
        trailingIcon = { if (state.selectedSubtitle == null) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { dismiss(); onSelect(null) },
    )
    state.subtitles.forEach { option ->
        DropdownMenuItem(
            text = { Text(option.file.name, maxLines = 1) },
            enabled = subtitleMime(option.format) != null,
            trailingIcon = { if (state.selectedSubtitle?.file?.path == option.file.path) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = { dismiss(); onSelect(option.file.path) },
        )
    }
    HorizontalDivider()
}

/** 竖屏时播放器下方：字幕、章节、流信息 */
@Composable
private fun VideoDetails(state: ViewerVideoState, onSeek: (VideoChapter) -> Unit, onSelectSubtitle: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val sub: SubtitleOption? = state.selectedSubtitle
            Box {
                ListItem(
                    modifier = Modifier.clickable(enabled = state.subtitles.isNotEmpty()) { subtitleMenu = true },
                    leadingContent = { Icon(Icons.Default.ClosedCaption, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.viewer_subtitle)) },
                    supportingContent = {
                        Text(
                            when {
                                state.subtitleLoading -> stringResource(R.string.viewer_subtitle_loading)
                                sub != null -> sub.file.name
                                state.subtitles.isEmpty() -> stringResource(R.string.viewer_subtitle_none)
                                else -> stringResource(R.string.viewer_subtitle_off)
                            },
                        )
                    },
                )
                DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    SubtitleMenuItems(state, dismiss = { subtitleMenu = false }, onSelect = onSelectSubtitle)
                }
            }
        }
        if (state.chapters.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.viewer_chapters),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.chapters, key = { it.id }) { chapter ->
                ListItem(
                    modifier = Modifier.clickable { onSeek(chapter) },
                    headlineContent = { Text(chapter.title.ifBlank { "#${chapter.id}" }) },
                    supportingContent = { Text(formatSeconds(chapter.startSeconds)) },
                )
            }
        }
        if (state.streamInfo.isNotEmpty()) {
            item {
                ListItem(
                    modifier = Modifier.clickable { expanded = !expanded },
                    headlineContent = { Text(stringResource(R.string.viewer_stream_info)) },
                )
            }
            if (expanded) {
                items(state.streamInfo, key = { "s:" + it.key }) { Box(Modifier.padding(horizontal = 16.dp)) { InfoRow(it.key, it.value) } }
            }
        }
    }
}

/** 秒 → `h:mm:ss` / `m:ss` */
internal fun formatSeconds(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
