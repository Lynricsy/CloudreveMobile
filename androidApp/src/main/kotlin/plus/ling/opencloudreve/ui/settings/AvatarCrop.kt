package plus.ling.opencloudreve.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.ling.opencloudreve.R
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 上传头像的边长（服务端会再缩放为各尺寸） */
private const val AVATAR_SIZE = 512

/** 解码时的最长边上限，避免超大照片占满内存 */
private const val MAX_DECODE_SIDE = 2048

/**
 * 头像裁剪：拖动/双指缩放把照片放进正方形取景框（圆形遮罩预览），确认后裁成正方形、
 * 缩放到 [AVATAR_SIZE] 并压缩为 JPEG，交给 [onCropped]（字节、MIME）。
 */
@Composable
fun AvatarCropDialog(uri: Uri, onCropped: (ByteArray, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val decoded = withContext(Dispatchers.IO) { runCatching { decode(context, uri) }.getOrNull() }
        if (decoded == null) failed = true else bitmap = decoded
    }
    // 用户缩放（≥1，1 = 刚好铺满取景框）与相对取景框中心的偏移（像素）
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableFloatStateOf(0f) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.me_avatar_crop_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(24.dp),
                )
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    val bmp = bitmap
                    when {
                        failed -> Text(stringResource(R.string.me_avatar_decode_failed), color = Color.White)
                        bmp == null -> CircularProgressIndicator()
                        else -> {
                            val image = remember(bmp) { bmp.asImageBitmap() }
                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(bmp) {
                                        detectTransformGestures { _, pan, gestureZoom, _ ->
                                            val side = size.width.toFloat()
                                            zoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                                            offset = clampOffset(offset + pan, bmp, side, zoom)
                                        }
                                    },
                            ) {
                                val side = size.width
                                viewport = side
                                val scale = baseScale(bmp, side) * zoom
                                val w = bmp.width * scale
                                val h = bmp.height * scale
                                val o = clampOffset(offset, bmp, side, zoom)
                                drawImage(
                                    image,
                                    dstOffset = IntOffset((side / 2 + o.x - w / 2).roundToInt(), (side / 2 + o.y - h / 2).roundToInt()),
                                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                                )
                                // 圆形取景框之外压暗（头像在各处以圆形显示）
                                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
                                clipPath(circle, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
                                drawCircle(Color.White.copy(alpha = 0.8f), radius = side / 2, style = Stroke(width = 2.dp.toPx()))
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Color.White) }
                    Box(Modifier.padding(start = 8.dp)) {
                        Button(
                            enabled = bitmap != null && !working && viewport > 0f,
                            onClick = {
                                val bmp = bitmap ?: return@Button
                                working = true
                                val z = zoom
                                val o = clampOffset(offset, bmp, viewport, z)
                                val side = viewport
                                scope.launch {
                                    val bytes = withContext(Dispatchers.Default) { crop(bmp, side, z, o) }
                                    working = false
                                    onCropped(bytes, "image/jpeg")
                                }
                            },
                        ) { Text(stringResource(R.string.me_avatar_crop_confirm)) }
                    }
                }
            }
        }
    }
}

/** 使图片短边刚好铺满取景框的缩放 */
private fun baseScale(bmp: Bitmap, side: Float): Float = side / min(bmp.width, bmp.height)

/** 限制偏移，使图片始终覆盖整个取景框 */
private fun clampOffset(offset: Offset, bmp: Bitmap, side: Float, zoom: Float): Offset {
    val scale = baseScale(bmp, side) * zoom
    val maxX = max(0f, (bmp.width * scale - side) / 2)
    val maxY = max(0f, (bmp.height * scale - side) / 2)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** 按取景框换算原图区域，裁成正方形、缩放并压缩 */
private fun crop(bmp: Bitmap, side: Float, zoom: Float, offset: Offset): ByteArray {
    val scale = baseScale(bmp, side) * zoom
    val left = side / 2 + offset.x - bmp.width * scale / 2
    val top = side / 2 + offset.y - bmp.height * scale / 2
    val srcSide = (side / scale).roundToInt().coerceIn(1, min(bmp.width, bmp.height))
    val x = (-left / scale).roundToInt().coerceIn(0, bmp.width - srcSide)
    val y = (-top / scale).roundToInt().coerceIn(0, bmp.height - srcSide)
    val square = Bitmap.createBitmap(bmp, x, y, srcSide, srcSide)
    val target = min(AVATAR_SIZE, srcSide)
    val scaled = if (srcSide == target) square else Bitmap.createScaledBitmap(square, target, target, true)
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }
}

/** 解码（按 EXIF 方向旋正，过大时降采样，软件位图以便裁剪） */
private fun decode(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_DECODE_SIDE) {
            val ratio = MAX_DECODE_SIDE.toFloat() / longest
            decoder.setTargetSize((info.size.width * ratio).roundToInt(), (info.size.height * ratio).roundToInt())
        }
    }
}
