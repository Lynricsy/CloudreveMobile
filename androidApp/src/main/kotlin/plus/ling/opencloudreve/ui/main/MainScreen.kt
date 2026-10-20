package plus.ling.opencloudreve.ui.main

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVerticalCircle
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.presentation.auth.AccountSwitcherViewModel
import plus.ling.opencloudreve.ui.account.AccountSwitcherSheet
import plus.ling.opencloudreve.ui.files.FilesTab

/** 主界面顶层 Tab */
enum class MainTab(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Files(R.string.tab_files, Icons.Outlined.Folder, Icons.Filled.Folder),
    Shares(R.string.tab_shares, Icons.Outlined.Share, Icons.Filled.Share),
    Tasks(R.string.tab_tasks, Icons.Outlined.SwapVerticalCircle, Icons.Filled.SwapVerticalCircle),
    Me(R.string.tab_me, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
}

/**
 * 主界面：`NavigationSuiteScaffold`（手机底栏、平板导航栏 rail）。长按“我的”打开账户切换器。
 * 各 Tab 的可保存状态（含 Tab 内导航栈）用 [rememberSaveableStateHolder] 保留，切换 Tab 后返回原位置。
 */
@Composable
fun MainScreen(
    onAddAccount: () -> Unit,
    switcher: AccountSwitcherViewModel = koinViewModel(),
    accounts: AccountRepository = koinInject(),
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Files) }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    val switcherState by switcher.state.collectAsStateWithLifecycle()
    val active by accounts.activeAccount.collectAsStateWithLifecycle()
    val tabStates = rememberSaveableStateHolder()
    val haptics = LocalHapticFeedback.current
    val switchLabel = stringResource(R.string.tab_me_long_press)
    val openSwitcher = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        showSwitcher = true
    }
    // 其它 Tab 请求在“文件”Tab 打开的目录（如管理页“打开此用户的文件”）
    var filesOpenRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val openFilesAt: (String) -> Unit = { uri ->
        filesOpenRequest = uri
        tab = MainTab.Files
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            MainTab.entries.forEach { item ->
                val selected = item == tab
                item(
                    selected = selected,
                    onClick = { tab = item },
                    icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label)) },
                    modifier = if (item == MainTab.Me) {
                        Modifier
                            .detectLongPress(openSwitcher)
                            .semantics {
                                onLongClick(label = switchLabel) {
                                    openSwitcher()
                                    true
                                }
                            }
                    } else {
                        Modifier
                    },
                )
            }
        },
    ) {
        tabStates.SaveableStateProvider(tab.name) {
            when (tab) {
                MainTab.Files -> active?.let { account ->
                    FilesTab(
                        account = account,
                        openRequest = filesOpenRequest,
                        onOpenRequestConsumed = { filesOpenRequest = null },
                    )
                }
                MainTab.Shares -> PlaceholderTab(MainTab.Shares, R.string.placeholder_shares)
                MainTab.Tasks -> PlaceholderTab(MainTab.Tasks, R.string.placeholder_tasks)
                MainTab.Me -> MeTab(
                    state = switcherState,
                    onIntent = switcher::on,
                    onOpenSwitcher = { showSwitcher = true },
                    onAddAccount = onAddAccount,
                )
            }
        }
    }

    if (showSwitcher) {
        AccountSwitcherSheet(
            state = switcherState,
            onIntent = switcher::on,
            onAddAccount = onAddAccount,
            onDismiss = { showSwitcher = false },
        )
    }
}

/**
 * 在不干扰子节点点击的前提下识别长按：在 Initial 阶段观察指针，超过长按时长后消费后续事件，
 * 使导航项自身的点击被取消。
 */
private fun Modifier.detectLongPress(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Initial)
        }
        if (up == null) {
            onLongPress()
            // 吞掉直到抬起的全部事件，阻止随后触发点击
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** 尚未实现的 Tab 的占位页（后续阶段替换为真实内容） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderTab(tab: MainTab, @StringRes message: Int) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(tab.label)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}
