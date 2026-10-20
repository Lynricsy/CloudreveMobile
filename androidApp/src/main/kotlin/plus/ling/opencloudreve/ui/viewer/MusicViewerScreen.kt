package plus.ling.opencloudreve.ui.viewer

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.viewer.AudioTrack
import plus.ling.opencloudreve.core.presentation.viewer.ViewerAudioIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerAudioViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams

/** 播放列表项：mediaId = 文件 URI，地址为 [MusicPlaybackService.trackUri] 占位 */
private fun AudioTrack.toMediaItem(accountId: String, version: String?): MediaItem {
    val uri = MusicPlaybackService.trackUri(accountId, file.linkedUri, version)
    return MediaItem.Builder()
        .setMediaId(file.path)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUrl?.toUri())
                .build(),
        )
        .build()
}

/**
 * 音乐播放：界面通过 [MediaController] 控制 [MusicPlaybackService]（离开页面后继续播放，通知/锁屏可控），
 * 播放列表 = 同目录音频；曲目切换回传 VM 以预取封面。
 */
@Composable
internal fun MusicViewerScreen(
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
    vm: ViewerAudioViewModel = koinViewModel(key = "music:${params.file.path}:${params.version}") { parametersOf(params) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
        // 只断开控制器；播放在服务里继续
        onDispose { MediaController.releaseFuture(future) }
    }

    val c = controller
    DisposableEffect(c) {
        if (c == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                duration = player.duration.coerceAtLeast(0)
                position = player.currentPosition
                val index = player.currentMediaItemIndex
                if (index != currentIndex) {
                    currentIndex = index
                    // 当前曲目在本页播放列表内时同步给 VM（预取封面与下一首）
                    val id = player.currentMediaItem?.mediaId
                    val vmIndex = vm.state.value.tracks.indexOfFirst { it.file.path == id }
                    if (vmIndex >= 0) vm.on(ViewerAudioIntent.Play(vmIndex))
                }
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    // 播放列表就绪后装载（同一列表已在播放则不打断）
    LaunchedEffect(c, state.loading) {
        if (c == null || state.loading) return@LaunchedEffect
        val ids = state.tracks.map { it.file.path }
        val playing = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        if (playing == ids) {
            if (c.currentMediaItem?.mediaId != params.file.path) c.seekTo(state.index, 0)
        } else {
            c.setMediaItems(state.tracks.map { it.toMediaItem(params.accountId, params.version) }, state.index, 0)
            c.prepare()
        }
        c.play()
    }

    // 封面取到后更新通知/锁屏的元数据
    LaunchedEffect(c, state.tracks) {
        if (c == null || state.loading) return@LaunchedEffect
        state.tracks.forEachIndexed { i, track ->
            val cover = track.coverUrl ?: return@forEachIndexed
            if (i >= c.mediaItemCount) return@forEachIndexed
            val item = c.getMediaItemAt(i)
            if (item.mediaId == track.file.path && item.mediaMetadata.artworkUri == null) {
                c.replaceMediaItem(i, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(cover.toUri()).build()).build())
            }
        }
    }

    // 进度刷新
    LaunchedEffect(c, isPlaying) {
        while (c != null && isPlaying) {
            position = c.currentPosition
            delay(500)
        }
    }

    val current = state.tracks.firstOrNull { it.file.path == c?.currentMediaItem?.mediaId } ?: state.current
    ViewerScaffold(title = params.file.name, menu = menu, onClose = onClose) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth(0.7f).aspectRatio(1f).clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (current?.coverUrl != null) {
                        AsyncImage(model = current.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(current?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(current?.artist, current?.album).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }
                Slider(
                    value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        dragging = false
                        if (duration > 0) c?.seekTo((dragValue * duration).toLong())
                    },
                    enabled = c != null && duration > 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatSeconds(position / 1000.0), style = MaterialTheme.typography.labelMedium)
                    Text(formatSeconds(duration / 1000.0), style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconButton(onClick = { c?.seekToPreviousMediaItem() }, enabled = c?.hasPreviousMediaItem() == true) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.viewer_previous), modifier = Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            c?.let {
                                when {
                                    it.isPlaying -> it.pause()
                                    // 播完整个列表后再按播放：从当前曲目开头重播
                                    it.playbackState == Player.STATE_ENDED -> {
                                        it.seekToDefaultPosition(it.currentMediaItemIndex)
                                        it.play()
                                    }
                                    else -> it.play()
                                }
                            }
                        },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (isPlaying) R.string.viewer_pause else R.string.viewer_play),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(onClick = { c?.seekToNextMediaItem() }, enabled = c?.hasNextMediaItem() == true) {
                        Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.viewer_next), modifier = Modifier.size(36.dp))
                    }
                }
            }
            Text(
                stringResource(R.string.viewer_queue, state.tracks.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(state.tracks, key = { _, t -> t.file.path }) { i, track ->
                    val active = track.file.path == c?.currentMediaItem?.mediaId
                    ListItem(
                        modifier = Modifier.clickable(enabled = c != null && !state.loading) {
                            c?.seekTo(i, 0)
                            c?.play()
                        },
                        leadingContent = {
                            Icon(
                                if (active) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = track.artist?.let { a -> { Text(a, maxLines = 1) } },
                    )
                }
            }
        }
    }
}
