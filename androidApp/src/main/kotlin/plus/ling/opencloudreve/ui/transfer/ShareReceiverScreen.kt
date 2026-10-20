package plus.ling.opencloudreve.ui.transfer

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.auth.Account
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.di.UploadPickerParams
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.presentation.transfer.RejectedUpload
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerEffect
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerIntent
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerViewModel
import plus.ling.opencloudreve.core.transfer.UploadSource
import plus.ling.opencloudreve.transfer.UploadSources
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.FolderPickerScreen
import plus.ling.opencloudreve.ui.files.displayPath
import plus.ling.opencloudreve.ui.files.formatBytes

/**
 * 分享进来的文件：进入页面即复制到收件箱（分享方授予的读权限只在接收 Activity 存活期间有效），
 * 复制结果在配置变更间保留。
 */
class ShareReceiverModel(application: Application) : AndroidViewModel(application) {
    private val _sources = MutableStateFlow<List<UploadSource>?>(null)

    /** null = 复制中 */
    val sources: StateFlow<List<UploadSource>?> = _sources.asStateFlow()
    private var started = false

    /** 是否已入队（入队后不再在销毁时清理副本） */
    var enqueued = false

    fun start(uris: List<Uri>) {
        if (started) return
        started = true
        viewModelScope.launch { _sources.value = UploadSources.fromUris(getApplication(), uris, forceCopy = true) }
    }

    override fun onCleared() {
        // 用户放弃上传：删除已复制的副本
        if (!enqueued) _sources.value?.forEach { UploadSources.discard(getApplication(), it.sourceRef) }
    }
}

/** 系统分享菜单的上传页：选择账户与目标目录后入队 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReceiverScreen(
    uris: List<Uri>,
    onOpenApp: () -> Unit,
    onFinished: () -> Unit,
    accounts: AccountRepository = koinInject(),
) {
    val model: ShareReceiverModel = viewModel()
    model.start(uris)
    val sources by model.sources.collectAsStateWithLifecycle()
    val allAccounts by accounts.accounts.collectAsStateWithLifecycle()
    val active by accounts.activeAccount.collectAsStateWithLifecycle()
    val usable = allAccounts.filterNot(Account::sessionExpired)
    var accountId by rememberSaveable { mutableStateOf(active?.takeUnless(Account::sessionExpired)?.id ?: usable.firstOrNull()?.id) }
    var targetUri by rememberSaveable { mutableStateOf(CrUri.my().toString()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val account = usable.firstOrNull { it.id == accountId }

    if (picking && account != null) {
        BackHandler { picking = false }
        FolderPickerScreen(
            accountId = account.id,
            initialUri = targetUri,
            excluded = emptyList(),
            confirmLabel = stringResource(R.string.share_receiver_upload_here),
            onPicked = {
                targetUri = it
                picking = false
            },
            onCancel = { picking = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_receiver_title)) },
                navigationIcon = {
                    IconButton(onClick = onFinished) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (usable.isEmpty()) {
                NoAccount(onOpenApp)
            } else {
                val selected = account ?: usable.first()
                key(selected.id) {
                    ReceiverContent(
                        model = model,
                        sources = sources,
                        accounts = usable,
                        account = selected,
                        targetUri = targetUri,
                        onSelectAccount = {
                            accountId = it
                            targetUri = CrUri.my().toString()
                        },
                        onPickTarget = { picking = true },
                        onFinished = onFinished,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiverContent(
    model: ShareReceiverModel,
    sources: List<UploadSource>?,
    accounts: List<Account>,
    account: Account,
    targetUri: String,
    onSelectAccount: (String) -> Unit,
    onPickTarget: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val vm: UploadPickerViewModel = koinViewModel(key = "share-upload:${account.id}") {
        parametersOf(UploadPickerParams(account.id, targetUri, null))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var rejected by remember { mutableStateOf<List<RejectedUpload>?>(null) }
    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is UploadPickerEffect.Enqueued -> {
                model.enqueued = true
                val accepted = effect.result.accepted.size
                if (accepted > 0) {
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(R.plurals.upload_added, accepted, accepted),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                if (effect.result.rejected.isEmpty()) onFinished() else rejected = effect.result.rejected
            }
            is UploadPickerEffect.Failed -> Toast.makeText(context, effect.error.message(context), Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                SectionTitle(
                    if (sources == null) {
                        stringResource(R.string.share_receiver_copying)
                    } else {
                        pluralStringResource(
                            R.plurals.share_receiver_items,
                            sources.size,
                            sources.size,
                            formatBytes(context, sources.sumOf { it.size }),
                        )
                    },
                )
            }
            if (sources == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                items(sources, key = UploadSource::sourceRef) { source ->
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.InsertDriveFile, contentDescription = null) },
                        headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                        supportingContent = { Text(formatBytes(context, source.size)) },
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            if (accounts.size > 1) {
                item { SectionTitle(stringResource(R.string.share_receiver_account)) }
                items(accounts, key = Account::id) { item ->
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = item.id == account.id,
                            onClick = { onSelectAccount(item.id) },
                            role = Role.RadioButton,
                        ),
                        leadingContent = { RadioButton(selected = item.id == account.id, onClick = null) },
                        headlineContent = { Text(item.nickname.ifBlank { item.email }) },
                        supportingContent = { Text("${item.email} @ ${item.host}") },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.share_receiver_target)) }
            item {
                val path = CrUri.parseOrNull(targetUri)?.let { displayPath(context, it) } ?: targetUri
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    headlineContent = { Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        TextButton(onClick = onPickTarget) { Text(stringResource(R.string.share_receiver_change)) }
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    sources?.let { vm.on(UploadPickerIntent.Enqueue(it, targetUri = targetUri)) }
                },
                enabled = !sources.isNullOrEmpty() && !state.enqueuing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.share_receiver_upload)) }
        }
    }

    rejected?.let { items ->
        AlertDialog(
            onDismissRequest = onFinished,
            title = { Text(stringResource(R.string.upload_rejected_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.upload_rejected_message))
                    items.forEach { Text("${it.name}：${rejectionText(it.reason)}", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = onFinished) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun NoAccount(onOpenApp: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.share_receiver_no_account), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onOpenApp, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.share_receiver_open_app))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
