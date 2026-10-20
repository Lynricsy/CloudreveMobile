package plus.ling.opencloudreve.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.viewer.ViewerPdfIntent
import plus.ling.opencloudreve.core.presentation.viewer.ViewerPdfViewModel
import plus.ling.opencloudreve.ui.common.message
import java.io.File

/** 打开的 PDF：页面渲染串行（PdfRenderer 非线程安全），位图按页 LRU 缓存 */
private class PdfDocument(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private val cache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int = renderer.pageCount

    /** 各页宽高比（宽/高） */
    val ratios: List<Float> = (0 until pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }

    suspend fun render(index: Int, widthPx: Int): Bitmap = cache.get(index)?.takeIf { it.width >= widthPx } ?: withContext(Dispatchers.IO) {
        lock.withLock {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(index, bitmap)
                bitmap
            }
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}

private sealed interface PdfLoad {
    data class Downloading(val progress: Float) : PdfLoad
    data class Ready(val document: PdfDocument) : PdfLoad
    data class Failed(val message: String) : PdfLoad
}

/** PDF 预览：下载到缓存后用 PdfRenderer 逐页渲染；双指缩放、双击放大 */
@Composable
internal fun PdfViewerScreen(
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
    vm: ViewerPdfViewModel = koinViewModel(key = "pdf:${params.file.path}:${params.version}") { parametersOf(params) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val url = state.url?.url
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<PdfLoad>(PdfLoad.Downloading(-1f), url, attempt) {
        if (url == null) return@produceState
        try {
            val dest = ViewerFiles.cacheFile(context, params.file, params.version)
            ViewerFiles.download(url, dest) { done, total -> value = PdfLoad.Downloading(if (total > 0) done.toFloat() / total else -1f) }
            value = PdfLoad.Ready(withContext(Dispatchers.IO) { PdfDocument(dest) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ViewerFiles.cacheFile(context, params.file, params.version).delete()
            value = PdfLoad.Failed(e.toUiError().message(context))
        }
    }
    val document = (load as? PdfLoad.Ready)?.document
    DisposableEffect(document) { onDispose { document?.close() } }

    val listState = rememberLazyListState()
    ViewerScaffold(
        title = state.file.name,
        subtitle = document?.let { stringResource(R.string.viewer_pdf_page, listState.firstVisibleItemIndex + 1, it.pageCount) },
        menu = menu,
        onClose = onClose,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            val err = state.error
            when {
                err != null -> CenteredError(err.message(), onRetry = { vm.on(ViewerPdfIntent.Retry) })
                load is PdfLoad.Failed -> CenteredError((load as PdfLoad.Failed).message, onRetry = { attempt++ })
                document != null -> PdfPages(document, listState)
                else -> {
                    val progress = (load as? PdfLoad.Downloading)?.progress ?: -1f
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.viewer_downloading))
                        if (progress >= 0f) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPages(document: PdfDocument, listState: androidx.compose.foundation.lazy.LazyListState) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 双指缩放与平移；单指滚动交给列表
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val maxX = size.width * (newScale - 1f) / 2f
                            val pan = event.calculatePan()
                            scale = newScale
                            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), 0f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2f
                    }
                })
            },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // 以 2 倍列表宽度渲染，放大后仍清晰
        val renderWidth = with(density) { (maxWidth.toPx() * 2).toInt() }.coerceAtMost(2400)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
            },
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(document.pageCount, key = { it }) { index ->
                Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 2.dp, color = Color.White) {
                    val bitmap by produceState<Bitmap?>(null, index, renderWidth) { value = document.render(index, renderWidth) }
                    Box(Modifier.fillMaxWidth().aspectRatio(document.ratios[index])) {
                        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}
