package plus.ling.opencloudreve.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.SubcomposeAsyncImage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.viewer.MetadataEntry
import kotlin.time.Instant
import plus.ling.opencloudreve.core.presentation.viewer.ViewerImage
import plus.ling.opencloudreve.core.presentation.viewer.ViewerImageIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerImageViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.formatBytes
import plus.ling.opencloudreve.ui.files.formatDateTime

/** 图片预览：左右滑动浏览同目录图片、双指缩放/拖动、双击放大、EXIF/地理信息面板、实况照片长按播放 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageViewerScreen(
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
    vm: ViewerImageViewModel = koinViewModel(key = "image:${params.file.path}:${params.version}") { parametersOf(params) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var chromeVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 同目录列表加载完成后重建 Pager，从当前文件所在位置开始
        key(state.loading, state.images.size) {
            val pager = rememberPagerState(initialPage = state.index) { state.images.size }
            LaunchedEffect(pager.currentPage) { vm.on(ViewerImageIntent.Select(pager.currentPage)) }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val image = state.images.getOrNull(page) ?: return@HorizontalPager
                ImagePage(
                    image = image,
                    onTap = { chromeVisible = !chromeVisible },
                    onRetry = { vm.on(ViewerImageIntent.Retry) },
                    onLoadLive = { vm.on(ViewerImageIntent.LoadLivePhoto) },
                )
            }
        }
        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            val current = state.current
            ViewerTopBar(
                title = current?.file?.name ?: params.file.name,
                subtitle = if (state.images.size > 1) "${state.index + 1} / ${state.images.size}" else null,
                menu = menu,
                onClose = onClose,
                colors = immersiveBarColors(),
                actions = {
                    if (current?.isLivePhoto == true) {
                        Icon(Icons.Default.MotionPhotosOn, contentDescription = stringResource(R.string.viewer_live_photo), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.viewer_image_info))
                    }
                },
            )
        }
    }

    val current = state.current
    if (showInfo && current != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            ImageInfoSheet(current)
        }
    }
}

@Composable
private fun ImagePage(image: ViewerImage, onTap: () -> Unit, onRetry: () -> Unit, onLoadLive: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pressingLive by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 双指缩放；放大后单指拖动（拖到边缘时不消费事件，交给 Pager 翻页）
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = size.width * (newScale - 1f) / 2f
                            val maxY = size.height * (newScale - 1f) / 2f
                            val atEdge = !multi && ((pan.x > 0 && offset.x >= maxX) || (pan.x < 0 && offset.x <= -maxX))
                            scale = newScale
                            offset = if (newScale == 1f) {
                                Offset.Zero
                            } else {
                                Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            }
                            if (!atEdge) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(image.isLivePhoto) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                    onLongPress = {
                        if (image.isLivePhoto) {
                            onLoadLive()
                            pressingLive = true
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        pressingLive = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val url = image.url?.url
        when {
            url != null -> SubcomposeAsyncImage(
                model = url,
                contentDescription = image.file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.viewer_image_failed), color = Color.White)
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                },
            )
            image.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(image.error!!.message(), color = Color.White)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            else -> CircularProgressIndicator()
        }
        val live = image.livePhotoUrl?.url
        if (pressingLive && live != null) LivePhotoPlayer(live)
    }
}

/** 实况照片视频（长按期间循环静音播放） */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LivePhotoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ImageInfoSheet(image: ViewerImage) {
    val context = LocalContext.current
    val file = image.file
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        item { Text(file.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        item { InfoRow(stringResource(R.string.viewer_info_size), formatBytes(context, file.size)) }
        file.updatedAt?.let { item { InfoRow(stringResource(R.string.viewer_info_modified), formatDateTime(it)) } }
        if (image.geocoding.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.viewer_info_location)) }
            items(image.geocoding, key = { "geo:" + it.key }) { InfoRow(geoLabel(it), it.value) }
        }
        if (image.exif.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.viewer_info_exif)) }
            items(image.exif, key = { "exif:" + it.key }) { InfoRow(exifLabel(it.key), exifValue(it)) }
        } else {
            item {
                Text(
                    stringResource(R.string.viewer_info_no_exif),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** 地理编码键（服务端 mediameta/geocoding.go） */
@Composable
private fun geoLabel(entry: MetadataEntry): String = when (entry.key) {
    "country" -> stringResource(R.string.viewer_geo_country)
    "region" -> stringResource(R.string.viewer_geo_region)
    "place", "locality" -> stringResource(R.string.viewer_geo_city)
    "district" -> stringResource(R.string.viewer_geo_district)
    "street" -> stringResource(R.string.viewer_geo_street)
    else -> entry.key
}

/** EXIF 键（服务端 mediameta/exif.go）的显示名；未知键原样显示 */
@Composable
private fun exifLabel(key: String): String {
    val res = when (key) {
        "camera_make" -> R.string.viewer_exif_camera_make
        "camera_model" -> R.string.viewer_exif_camera_model
        "lens_make" -> R.string.viewer_exif_lens_make
        "lens_model" -> R.string.viewer_exif_lens_model
        "taken_at" -> R.string.viewer_exif_taken_at
        "exposure_time" -> R.string.viewer_exif_exposure_time
        "f", "aperture" -> R.string.viewer_exif_aperture
        "focal_length" -> R.string.viewer_exif_focal_length
        "iso" -> R.string.viewer_exif_iso
        "exposure_bias" -> R.string.viewer_exif_exposure_bias
        "flash" -> R.string.viewer_exif_flash
        "latitude" -> R.string.viewer_exif_latitude
        "longitude" -> R.string.viewer_exif_longitude
        "altitude" -> R.string.viewer_exif_altitude
        "orientation" -> R.string.viewer_exif_orientation
        "software" -> R.string.viewer_exif_software
        "artist" -> R.string.viewer_exif_artist
        "copyright" -> R.string.viewer_exif_copyright
        "x" -> R.string.viewer_exif_width
        "y" -> R.string.viewer_exif_height
        else -> return key
    }
    return stringResource(res)
}

/** 拍摄时间（RFC3339）按本地格式显示，其余原样 */
private fun exifValue(entry: MetadataEntry): String =
    if (entry.key == "taken_at") runCatching { formatDateTime(Instant.parse(entry.value)) }.getOrDefault(entry.value) else entry.value

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
