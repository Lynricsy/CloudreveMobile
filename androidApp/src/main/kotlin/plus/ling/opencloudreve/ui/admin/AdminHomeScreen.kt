package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.admin.AdminSummaryEffect
import plus.ling.opencloudreve.core.presentation.admin.AdminSummaryIntent
import plus.ling.opencloudreve.core.presentation.admin.AdminSummaryState
import plus.ling.opencloudreve.core.presentation.admin.AdminSummaryViewModel
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.openCustomTab
import java.text.NumberFormat

/** 管理概览：总数、版本、站点地址、近 12 天新增图表与各管理页入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminHomeScreen(accountId: String, onBack: () -> Unit, onNavigate: (Any) -> Unit) {
    val vm: AdminSummaryViewModel = koinViewModel { parametersOf(accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is AdminSummaryEffect.OpenUrl -> openCustomTab(context, effect.url)
            is AdminSummaryEffect.Failed -> scope.launch { snackbar.showSnackbar(adminErrorText(context, effect.error)) }
        }
    }
    // 已有数据时刷新失败用 Snackbar 提示
    val error = state.error
    val hasSummary = state.summary != null
    LaunchedEffect(error, hasSummary) {
        if (error != null && hasSummary) {
            vm.on(AdminSummaryIntent.DismissError)
            scope.launch { snackbar.showSnackbar(adminErrorText(context, error)) }
        }
    }

    Scaffold(
        contentWindowInsets = adminContentInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.admin_home_title)) },
                actions = {
                    OpenInWebAction(
                        handoff = state.handoff,
                        opening = state.openingWeb,
                        serverVersion = state.version,
                        snackbar = snackbar,
                        onOpen = { vm.on(AdminSummaryIntent.OpenInWeb) },
                    )
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.on(AdminSummaryIntent.Refresh) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.loading && !hasSummary -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && !hasSummary -> FullError(error) { vm.on(AdminSummaryIntent.Refresh) }
                else -> SummaryContent(state, onNavigate)
            }
        }
    }
}

@Composable
private fun SummaryContent(state: AdminSummaryState, onNavigate: (Any) -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalCard(stringResource(R.string.admin_files), numbers.format(state.fileTotal), Modifier.weight(1f))
                TotalCard(stringResource(R.string.admin_users), numbers.format(state.userTotal), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalCard(stringResource(R.string.admin_shares), numbers.format(state.shareTotal), Modifier.weight(1f))
                TotalCard(stringResource(R.string.admin_entities), numbers.format(state.entitiesTotal), Modifier.weight(1f))
            }
        }

        Column {
            SectionTitle(stringResource(R.string.admin_server_info))
            val version = state.version.ifEmpty { EMPTY_VALUE }
            InfoRow(
                stringResource(R.string.admin_version),
                if (state.commit.isNotEmpty()) stringResource(R.string.admin_version_value, version, state.commit) else version,
            )
            InfoRow(stringResource(R.string.admin_generated_at), formatTime(state.generatedAt))
            if (state.siteUrls.isNotEmpty()) {
                SectionTitle(stringResource(R.string.admin_site_urls))
                state.siteUrls.forEach { url ->
                    SelectionContainer {
                        Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
        }

        DailyChartCard(state.daily, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        Column {
            SectionTitle(stringResource(R.string.admin_section_manage))
            EntryItem(Icons.Outlined.People, stringResource(R.string.admin_users)) { onNavigate(AdminUsersRoute) }
            EntryItem(Icons.Outlined.Link, stringResource(R.string.admin_shares)) { onNavigate(AdminSharesRoute) }
            EntryItem(Icons.Outlined.Folder, stringResource(R.string.admin_files)) { onNavigate(AdminFilesRoute) }
            EntryItem(Icons.AutoMirrored.Outlined.ListAlt, stringResource(R.string.admin_queue)) { onNavigate(AdminQueueRoute) }
            EntryItem(Icons.Outlined.Dns, stringResource(R.string.admin_nodes)) { onNavigate(AdminNodesRoute) }
        }
    }
}

@Composable
private fun TotalCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun EntryItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
    )
}
