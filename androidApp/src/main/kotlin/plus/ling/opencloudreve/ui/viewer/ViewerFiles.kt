package plus.ling.opencloudreve.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.di.DataRepositories
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.ui.common.message
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** 预览器的本地缓存：下载签名地址到 `cacheDir/viewer/`，并通过 FileProvider 交给其它应用 */
internal object ViewerFiles {
    private val http by lazy { OkHttpClient() }
    private val unsafe = Regex("[^A-Za-z0-9._-]")

    private fun root(context: Context): File = File(context.cacheDir, "viewer")

    /** 某文件某版本的缓存位置：`viewer/<id>_<实体>/<文件名>`（实体变化即文件被修改，自动换新） */
    fun cacheFile(context: Context, file: FileItem, version: String?): File {
        val entity = version ?: file.primaryEntity ?: "current"
        val dir = File(root(context), "${file.id.ifEmpty { "file" }}_$entity".replace(unsafe, "_"))
        dir.mkdirs()
        return File(dir, file.name.replace('/', '_').ifEmpty { "file" })
    }

    /** 下载到 [dest]（已存在则直接复用）；先写 `.part` 再改名，避免中断留下残缺文件 */
    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            if (dest.isFile && dest.length() > 0) return@withContext dest
            val part = File(dest.parentFile, dest.name + ".part")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val total = resp.body.contentLength()
                resp.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (!part.renameTo(dest)) throw IOException("Cannot move ${part.name}")
            dest
        }

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** 用其它应用打开；没有能处理的应用时弹出分享面板 */
    fun openExternally(context: Context, file: File) {
        val uri = contentUri(context, file)
        val mime = mimeOf(file.name)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            // 直接启动（不包 chooser）：没有任何应用能处理时才会抛出，进而改用分享面板
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            share(context, uri, mime)
        }
    }

    private fun share(context: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.viewer_no_app_share)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.viewer_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 文件（链接目标）的下载地址：站内相对地址补全为账户站点地址 */
internal suspend fun downloadUrl(
    repositories: DataRepositories,
    accounts: AccountRepository,
    accountId: String,
    file: FileItem,
    version: String?,
): String {
    val res = repositories.files(accountId).fileUrl(file, download = true, entity = version)
    val url = res.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() } ?: throw IOException("Empty file url")
    return if (url.startsWith("/")) accounts.account(accountId)?.siteUrl.orEmpty().trimEnd('/') + url else url
}

/** 无可用预览器 / “用其它应用打开”：下载到缓存（显示进度）后交给系统，然后关闭本页 */
@Composable
internal fun ExternalOpenPage(
    accountId: String,
    file: FileItem,
    version: String?,
    onClose: () -> Unit,
    repositories: DataRepositories = koinInject(),
    accounts: AccountRepository = koinInject(),
) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(attempt) {
        error = null
        try {
            val dest = ViewerFiles.cacheFile(context, file, version)
            if (!dest.isFile) {
                val url = downloadUrl(repositories, accounts, accountId, file, version)
                ViewerFiles.download(url, dest) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else -1f
                }
            }
            ViewerFiles.openExternally(context, dest)
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.toUiError().message(context)
        }
    }
    Scaffold(topBar = { ViewerTopBar(file.name, null, onClose) }) { padding ->
        val err = error
        if (err != null) {
            CenteredError(err, onRetry = { attempt++ }, modifier = Modifier.padding(padding))
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.viewer_preparing_external), style = MaterialTheme.typography.bodyLarge)
                if (progress >= 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}
