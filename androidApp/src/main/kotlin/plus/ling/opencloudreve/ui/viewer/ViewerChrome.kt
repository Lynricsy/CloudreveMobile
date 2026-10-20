package plus.ling.opencloudreve.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import plus.ling.opencloudreve.R

/**
 * 各预览器顶栏溢出菜单的公共项。
 * @param canSwitch 该文件有多个预览器候选（显示“打开方式”）
 */
class ViewerMenu(
    val canSwitch: Boolean,
    val onSwitch: () -> Unit,
    val onOpenExternal: () -> Unit,
    val onInfo: () -> Unit,
)

/** 预览页顶栏：返回、标题、页面自有动作、公共溢出菜单 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerTopBar(
    title: String,
    menu: ViewerMenu?,
    onClose: () -> Unit,
    subtitle: String? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    extraMenu: @Composable (dismiss: () -> Unit) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        colors = colors,
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (subtitle != null) {
                    Text(subtitle, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            actions()
            if (menu != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.viewer_more))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        val dismiss = { open = false }
                        extraMenu(dismiss)
                        if (menu.canSwitch) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_open_with)) },
                                leadingIcon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                onClick = { dismiss(); menu.onSwitch() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_open_external)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                            onClick = { dismiss(); menu.onOpenExternal() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_file_info)) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { dismiss(); menu.onInfo() },
                        )
                    }
                }
            }
        },
    )
}

/** 深色沉浸式顶栏配色（图片/视频） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun immersiveBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Black.copy(alpha = 0.55f),
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)

/** 只有顶栏与加载指示的页面 */
@Composable
internal fun ViewerLoadingPage(title: String, onClose: () -> Unit, showProgress: Boolean = true) {
    Scaffold(topBar = { ViewerTopBar(title, null, onClose) }) { padding ->
        if (showProgress) CenteredProgress(Modifier.padding(padding))
    }
}

/** 只有顶栏与错误提示的页面 */
@Composable
internal fun ViewerErrorPage(title: String, message: String, onClose: () -> Unit, onRetry: (() -> Unit)?) {
    Scaffold(topBar = { ViewerTopBar(title, null, onClose) }) { padding ->
        CenteredError(message, onRetry, Modifier.padding(padding))
    }
}

/** 标准预览页骨架 */
@Composable
internal fun ViewerScaffold(
    title: String,
    menu: ViewerMenu?,
    onClose: () -> Unit,
    subtitle: String? = null,
    snackbar: SnackbarHostState? = null,
    extraMenu: @Composable (dismiss: () -> Unit) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { ViewerTopBar(title, menu, onClose, subtitle, extraMenu = extraMenu, actions = actions) },
        bottomBar = bottomBar,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}
