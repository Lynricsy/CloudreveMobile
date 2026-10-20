package plus.ling.opencloudreve.ui.viewer

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.di.DataRepositories
import plus.ling.opencloudreve.core.files.ViewerResolution
import plus.ling.opencloudreve.core.files.ViewerResolver
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.Viewer
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.presentation.viewer.BuiltinViewerId
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.FileHook
import plus.ling.opencloudreve.ui.files.FileNavigator
import plus.ling.opencloudreve.ui.files.siteText

private val viewerJson = Json { ignoreUnknownKeys = true }

/**
 * 预览页（“文件”Tab 嵌套导航目的地）。
 * @param file 文件条目 JSON（[FileItem]）
 * @param viewerId 指定预览器（版本预览、“打开方式”）；null 按 [ViewerResolver] 解析
 * @param version 预览指定版本实体
 */
@Serializable
data class ViewerRoute(val file: String, val viewerId: String? = null, val version: String? = null) {
    constructor(file: FileItem, viewerId: String? = null, version: String? = null) :
        this(viewerJson.encodeToString(FileItem.serializer(), file), viewerId, version)

    fun fileItem(): FileItem = viewerJson.decodeFromString(FileItem.serializer(), file)
}

/** P6 预览钩子：点按文件 / 菜单“打开”进入预览页 */
internal val PreviewHook = FileHook { context, files ->
    val file = files.singleOrNull() ?: return@FileHook
    context.navController.navigate(ViewerRoute(file))
}

/** 预览器解析状态 */
private sealed interface HostState {
    data object Loading : HostState
    data class Ready(val resolver: ViewerResolver) : HostState
    data class Failed(val error: UiError) : HostState
}

/** “始终使用此方式打开”的偏好（按扩展名，应用级） */
private object ViewerPreferences {
    private const val NAME = "viewer_preferences"

    fun get(context: Context, ext: String): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(ext, null)

    fun set(context: Context, ext: String, viewerId: String) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putString(ext, viewerId) }
}

/**
 * 预览入口：解析可用预览器（偏好 → 唯一候选 → 站点默认映射 → 让用户选择），分派到原生/网页预览器；
 * 无可用预览器时下载到缓存后交给其它应用打开。
 */
@Composable
fun ViewerHostScreen(
    accountId: String,
    route: ViewerRoute,
    navigator: FileNavigator,
    onClose: () -> Unit,
    repositories: DataRepositories = koinInject(),
) {
    val context = LocalContext.current
    val file = remember(route.file) { route.fileItem() }
    val host by produceState<HostState>(HostState.Loading, accountId) {
        value = try {
            val explorer = repositories.site.explorer(accountId)
            val user = repositories.userSettings(accountId).me()
            HostState.Ready(ViewerResolver(explorer, user.group?.permission))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HostState.Failed(e.toUiError())
        }
    }
    // 用户在本页选择的预览器（“打开方式”）；"" 表示用其它应用打开
    var chosen by rememberSaveable(route) { mutableStateOf(route.viewerId) }

    when (val s = host) {
        HostState.Loading -> ViewerLoadingPage(file.name, onClose)
        is HostState.Failed -> ViewerErrorPage(file.name, s.error.message(), onClose, onRetry = null)
        is HostState.Ready -> {
            val resolver = s.resolver
            val candidates = remember(resolver, file) { resolver.candidatesFor(file).sortedBy { if (it.isNative) 0 else 1 } }
            val viewer: Viewer? = when (val id = chosen) {
                null -> when (val r = resolver.resolve(file, ViewerPreferences.get(context, file.extension))) {
                    is ViewerResolution.Open -> r.viewer
                    is ViewerResolution.Choose -> {
                        ViewerChooserPage(file, candidates, onClose) { picked, remember ->
                            if (remember && picked != null) ViewerPreferences.set(context, file.extension, picked.id)
                            chosen = picked?.id ?: EXTERNAL
                        }
                        return
                    }
                    ViewerResolution.NoViewer -> null
                }
                EXTERNAL -> null
                else -> resolver.viewer(id)
            }
            if (viewer == null) {
                ExternalOpenPage(accountId, file, route.version, onClose)
                return
            }
            val params = remember(file, viewer.id, route.version) {
                ViewerParams(accountId, file, version = route.version, viewerId = viewer.id)
            }
            var showChooser by remember { mutableStateOf(false) }
            val menu = ViewerMenu(
                canSwitch = candidates.size > 1,
                onSwitch = { showChooser = true },
                onOpenExternal = { chosen = EXTERNAL },
                onInfo = { navigator.showInfo(file) },
            )
            ViewerContent(accountId, viewer, params, menu, onClose)
            if (showChooser) {
                ViewerChooserDialog(file, candidates, current = viewer.id, onDismiss = { showChooser = false }) { picked, remember ->
                    showChooser = false
                    if (remember && picked != null) ViewerPreferences.set(context, file.extension, picked.id)
                    chosen = picked?.id ?: EXTERNAL
                }
            }
        }
    }
}

private const val EXTERNAL = ""

private val Viewer.isNative: Boolean get() = type == Viewer.TYPE_BUILTIN && id in BuiltinViewerId.NATIVE

/** 按预览器类型分派到对应页面（同一预览器 id 切换文件时重建） */
@Composable
private fun ViewerContent(
    accountId: String,
    viewer: Viewer,
    params: ViewerParams,
    menu: ViewerMenu,
    onClose: () -> Unit,
) {
    if (!viewer.isNative) {
        WebViewerScreen(params, menu, onClose)
        return
    }
    when (viewer.id) {
        BuiltinViewerId.IMAGE -> ImageViewerScreen(params, menu, onClose)
        BuiltinViewerId.VIDEO -> VideoViewerScreen(params, menu, onClose)
        BuiltinViewerId.MUSIC -> MusicViewerScreen(params, menu, onClose)
        BuiltinViewerId.PDF -> PdfViewerScreen(params, menu, onClose)
        BuiltinViewerId.MONACO, BuiltinViewerId.MARKDOWN -> TextViewerScreen(params, menu, onClose)
        BuiltinViewerId.ARCHIVE -> ArchiveViewerScreen(accountId, params, menu, onClose)
        else -> WebViewerScreen(params, menu, onClose)
    }
}

/** 预览器显示名（站点配置可能是 Web 端 i18n 键） */
@Composable
internal fun viewerLabel(viewer: Viewer): String = siteText(LocalContext.current, viewer.displayName).ifBlank { viewer.id }

/** 多个候选时的“打开方式”整页（首次打开） */
@Composable
private fun ViewerChooserPage(
    file: FileItem,
    candidates: List<Viewer>,
    onClose: () -> Unit,
    onPicked: (Viewer?, Boolean) -> Unit,
) {
    ViewerLoadingPage(file.name, onClose, showProgress = false)
    ViewerChooserDialog(file, candidates, current = null, onDismiss = onClose, onPicked = onPicked)
}

/**
 * “打开方式”选择。
 * @param onPicked 选中的预览器（null = 用其它应用打开）与是否“始终使用”
 */
@Composable
private fun ViewerChooserDialog(
    file: FileItem,
    candidates: List<Viewer>,
    current: String?,
    onDismiss: () -> Unit,
    onPicked: (Viewer?, Boolean) -> Unit,
) {
    var always by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_open_with)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                candidates.forEach { v ->
                    ChooserRow(viewerLabel(v), selected = v.id == current) { onPicked(v, always) }
                }
                ChooserRow(stringResource(R.string.viewer_open_external), selected = false) { onPicked(null, false) }
                Row(
                    Modifier.fillMaxWidth().clickable { always = !always }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = always, onCheckedChange = { always = it })
                    Text(stringResource(R.string.viewer_always_use, file.extension.uppercase()), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChooserRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 全屏加载占位 */
@Composable
internal fun CenteredProgress(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** 全屏错误占位 */
@Composable
internal fun CenteredError(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
