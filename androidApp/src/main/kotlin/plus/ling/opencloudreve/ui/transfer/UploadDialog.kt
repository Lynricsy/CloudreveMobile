package plus.ling.opencloudreve.ui.transfer

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.di.UploadPickerParams
import plus.ling.opencloudreve.core.presentation.transfer.RejectedUpload
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerEffect
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerIntent
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerViewModel
import plus.ling.opencloudreve.core.transfer.UploadSource
import plus.ling.opencloudreve.transfer.DownloadTargets
import plus.ling.opencloudreve.transfer.UploadSources
import plus.ling.opencloudreve.ui.common.CollectEffects
import plus.ling.opencloudreve.ui.common.message
import plus.ling.opencloudreve.ui.files.folderTitle
import plus.ling.opencloudreve.ui.main.MainRequest
import plus.ling.opencloudreve.ui.main.MainRequests
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** “文件”Tab 内的上传入口对话框（目录 [folderUri]；[media] 为照片/视频与拍照，否则为系统文件选择器） */
@Serializable
data class UploadRoute(val folderUri: String, val media: Boolean)

private enum class UploadStage { Choose, Launch, Picking, Preparing, Result }

/**
 * 上传入口：打开选择器 → 解析源（持久授权或复制到收件箱）→ `UploadPickerViewModel.Enqueue`（按目标目录策略预校验）。
 * 全部接受时提示后关闭；有被预校验拒绝的项时列出原因。
 */
@Composable
fun UploadDialog(accountId: String, folderUri: String, media: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vm: UploadPickerViewModel = koinViewModel(key = "upload:$accountId:$folderUri") {
        parametersOf(UploadPickerParams(accountId, folderUri, null))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var stage by rememberSaveable { mutableStateOf(if (media) UploadStage.Choose else UploadStage.Launch) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(sources: List<UploadSource>) {
        if (sources.isEmpty()) {
            Toast.makeText(context, R.string.upload_nothing_readable, Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            stage = UploadStage.Preparing
            vm.on(UploadPickerIntent.Enqueue(sources))
        }
    }

    fun accept(uris: List<Uri>) {
        if (uris.isEmpty()) {
            onDismiss()
            return
        }
        stage = UploadStage.Preparing
        scope.launch { submit(UploadSources.fromUris(context, uris)) }
    }

    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { accept(it) }
    val visualMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { accept(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (saved && file != null && file.length() > 0) {
            submit(listOf(UploadSources.fromFile(file, "image/jpeg")))
        } else {
            file?.let { UploadSources.discard(context, it.absolutePath) }
            onDismiss()
        }
    }

    LaunchedEffect(stage) {
        if (stage == UploadStage.Launch) {
            stage = UploadStage.Picking
            documents.launch(arrayOf("*/*"))
        }
    }

    CollectEffects(vm.effects) { effect ->
        when (effect) {
            is UploadPickerEffect.Enqueued -> {
                val accepted = effect.result.accepted.size
                if (accepted > 0) {
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(R.plurals.upload_added, accepted, accepted),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                if (effect.result.rejected.isEmpty()) onDismiss() else stage = UploadStage.Result
            }
            is UploadPickerEffect.Failed -> {
                Toast.makeText(context, effect.error.message(context), Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }
    }

    when (stage) {
        // 选择器在前台：对话框不绘制内容
        UploadStage.Launch, UploadStage.Picking -> Unit
        UploadStage.Choose -> DialogCard {
            Text(stringResource(R.string.upload_media), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.upload_to, folderTitle(folderUri)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.upload_pick_media)) },
                leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable {
                    stage = UploadStage.Picking
                    visualMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.upload_take_photo)) },
                leadingContent = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = UploadSources.newCameraFile(context, "IMG_$stamp.jpg")
                    val uri = FileProvider.getUriForFile(context, DownloadTargets.fileProviderAuthority(context), file)
                    cameraPath = file.absolutePath
                    stage = UploadStage.Picking
                    try {
                        camera.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        cameraPath = null
                        UploadSources.discard(context, file.absolutePath)
                        Toast.makeText(context, R.string.upload_no_camera, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        UploadStage.Preparing -> DialogCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(
                    stringResource(if (state.loadingPolicy) R.string.upload_checking_policy else R.string.upload_preparing),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        UploadStage.Result -> DialogCard {
            val rejected = state.lastResult?.rejected.orEmpty()
            Text(stringResource(R.string.upload_rejected_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.upload_rejected_message), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(rejected, key = RejectedUpload::taskId) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = { Text(rejectionText(item.reason)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    MainRequests.post(MainRequest.OpenTasks)
                    onDismiss()
                }) { Text(stringResource(R.string.transfer_view_list)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

/** 对话框目的地里的卡片外观（与 AlertDialog 一致的形状与色调） */
@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}
