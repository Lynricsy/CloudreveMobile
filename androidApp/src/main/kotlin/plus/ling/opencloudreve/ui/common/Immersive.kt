package plus.ling.opencloudreve.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式页面（预览器）请求隐藏应用级导航（底栏/导航栏 rail、平板的详情窗格）。
 * 按引用计数：任一页面持有时生效，全部释放后恢复。
 */
@Stable
class ImmersiveState {
    private var holders by mutableIntStateOf(0)

    val active: Boolean get() = holders > 0

    internal fun acquire() {
        holders++
    }

    internal fun release() {
        holders = (holders - 1).coerceAtLeast(0)
    }
}

/** 由主界面提供；默认实例只用于预览/测试等没有主界面的场景 */
val LocalImmersiveState = staticCompositionLocalOf { ImmersiveState() }

/** 在组合期间隐藏应用级导航（离开页面后自动恢复） */
@Composable
fun HideAppNavigation() {
    val state = LocalImmersiveState.current
    DisposableEffect(state) {
        state.acquire()
        onDispose { state.release() }
    }
}

/**
 * [hidden] 为 true 时隐藏系统状态栏/导航栏（从边缘滑动临时显示）；离开页面后恢复。
 * [onDarkBackground] 为 true 时系统栏图标用浅色（黑底预览页），离开后恢复原外观。
 */
@Composable
fun SystemBarsHidden(hidden: Boolean, onDarkBackground: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view, hidden, onDarkBackground) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
            if (onDarkBackground) {
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (controller != null && onDarkBackground) {
                lightStatus?.let { controller.isAppearanceLightStatusBars = it }
                lightNavigation?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
