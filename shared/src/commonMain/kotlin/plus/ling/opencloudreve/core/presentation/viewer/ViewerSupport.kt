package plus.ling.opencloudreve.core.presentation.viewer

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.TransportException
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.files.ViewerResolver
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.settings.UserSettingsRepository
import plus.ling.opencloudreve.core.site.SiteConfigRepository
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 打开预览器的参数。
 * @property file 要打开的文件（软链接文件也传原对象，VM 自行使用链接目标）
 * @property version 指定版本实体 id（预览历史版本）；null 为当前版本
 * @property siblings 同目录文件（列表页已加载的数据，用于图片/音乐列表与字幕查找）；null 时 VM 加载父目录
 * @property contextHint 列表的 `context_hint`（加速 `POST /file/url`）
 * @property viewerId 选中的预览器 id（文本/网页预览器使用；见 [ViewerResolver]）
 */
data class ViewerParams(
    val accountId: String,
    val file: FileItem,
    val version: String? = null,
    val siblings: List<FileItem>? = null,
    val contextHint: String? = null,
    val viewerId: String? = null,
)

/** 带过期时间的签名地址；[expiresAt] 为 null 表示不过期 */
data class ResolvedUrl(val url: String, val expiresAt: Instant?)

/** 元数据条目（去掉前缀后的键，如 `exif:Model` → `Model`） */
data class MetadataEntry(val key: String, val value: String)

/** 内置预览器 id（前端 `builtInViewers`） */
object BuiltinViewerId {
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val MUSIC = "music"
    const val PDF = "pdf"
    const val MONACO = "monaco"
    const val MARKDOWN = "markdown"
    const val ARCHIVE = "archive"
    const val EPUB = "epub"
    const val DRAWIO = "drawio"
    const val EXCALIDRAW = "excalidraw"
    const val PHOTOPEA = "photopea"

    /** 移动端原生实现的内置预览器；其余内置预览器（epub、drawio、excalidraw、photopea）走网页交接 */
    val NATIVE: Set<String> = setOf(IMAGE, VIDEO, MUSIC, PDF, MONACO, MARKDOWN, ARCHIVE)
}

/** 预览器共用依赖与工具 */
class ViewerEnvironment internal constructor(
    internal val files: FileRepository,
    internal val site: SiteConfigRepository,
    internal val userSettings: UserSettingsRepository,
    private val clients: ApiClientProvider,
    internal val clock: () -> Instant = { Clock.System.now() },
) {
    /** 当前账户可用的预览器（站点配置或用户信息取不到时为 null） */
    internal suspend fun resolver(accountId: String): ViewerResolver? = try {
        ViewerResolver(site.explorer(accountId), userSettings.me().group?.permission)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 某预览器支持的扩展名（站点未配置该预览器时用前端默认列表） */
    internal suspend fun extsOf(accountId: String, viewerId: String, defaults: Set<String>): Set<String> =
        resolver(accountId)?.viewer(viewerId)?.exts?.map { it.lowercase() }?.toSet() ?: defaults

    /** 文件（链接目标）的签名地址；空地址按 404 处理 */
    internal suspend fun url(file: FileItem, version: String?, contextHint: String?, download: Boolean = false): ResolvedUrl {
        val res = files.fileUrl(file, download = download, entity = version, contextHint = contextHint)
        val url = res.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Empty file url")
        return ResolvedUrl(absolute(files.accountId, url), res.expires)
    }

    /** 同目录文件：优先使用参数给出的列表，否则加载父目录（最多 [MAX_SIBLING_PAGES] 页） */
    internal suspend fun siblings(params: ViewerParams): List<FileItem> {
        params.siblings?.let { return it }
        val parent = params.file.uri()?.parent()?.toString() ?: return listOf(params.file)
        return try {
            files.pager(parent).loadAll(MAX_SIBLING_PAGES).files.ifEmpty { listOf(params.file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(params.file)
        }
    }

    /** 下载文本/字幕等小文件内容（签名地址无需鉴权；站内相对地址补全为绝对地址） */
    internal suspend fun download(accountId: String, url: String): ByteArray {
        val client = clients.client(accountId)
        val response = try {
            client.http.get(absolute(accountId, url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException(e.message ?: "Download failed", cause = e)
        }
        if (response.status.value !in 200..299) throw TransportException("HTTP ${response.status.value}", response.status.value)
        return response.bodyAsBytes()
    }

    /** 站内相对地址（以 `/` 开头）补全为账户站点的绝对地址 */
    internal fun absolute(accountId: String, url: String): String =
        if (url.startsWith("/")) clients.client(accountId).siteUrl.trimEnd('/') + url else url

    companion object {
        /** 签名地址在过期前多久刷新 */
        val URL_REFRESH_MARGIN: Duration = 60.seconds
        const val MAX_SIBLING_PAGES = 10
    }
}

/** 文件元数据中某前缀的条目（如 `exif:`、`music:`、`stream:`），按键排序 */
fun metadataWithPrefix(file: FileItem, prefix: String): List<MetadataEntry> =
    file.metadata.filterKeys { it.startsWith(prefix) }
        .map { (k, v) -> MetadataEntry(k.removePrefix(prefix), v) }
        .sortedBy { it.key }

/** 文件名去掉扩展名 */
internal fun nameWithoutExtension(name: String): String = name.substringBeforeLast('.', name)

/** 在签名地址过期前 [ViewerEnvironment.URL_REFRESH_MARGIN] 执行刷新；重复调度会取消上一次 */
internal class UrlRefreshScheduler(
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
) {
    private var job: Job? = null

    /** [action] 内的异常交给 [onError]，不会向上抛出 */
    fun schedule(expiresAt: Instant?, onError: (Throwable) -> Unit = {}, action: suspend () -> Unit) {
        job?.cancel()
        if (expiresAt == null) return
        job = scope.launch {
            val wait = expiresAt - clock() - ViewerEnvironment.URL_REFRESH_MARGIN
            if (wait.isPositive()) delay(wait)
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
