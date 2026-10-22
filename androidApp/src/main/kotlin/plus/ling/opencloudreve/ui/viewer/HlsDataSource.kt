package plus.ling.opencloudreve.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.runBlocking
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.di.DataRepositories
import plus.ling.opencloudreve.core.model.CrUri
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 把 [HlsMask] 掩码地址换成签名地址（`POST /file/url`），按地址缓存到过期前 60 秒。
 * @param entryVersion 只作用于入口播放列表本身（预览历史版本）；分片与子播放列表取当前版本
 */
internal class HlsSigner(
    private val accountId: String,
    private val linked: CrUri,
    private val entryVersion: String?,
    private val repositories: DataRepositories,
    private val accounts: AccountRepository,
) {
    private val entry = HlsMask.entry(linked)
    private val cache = HashMap<String, Pair<Uri, Instant?>>()

    /** 在加载线程上调用（阻塞） */
    fun sign(masked: String): Uri {
        synchronized(cache) {
            cache[masked]?.let { (uri, expires) ->
                if (expires == null || expires - Clock.System.now() > 60.seconds) return uri
            }
        }
        val cloud = HlsMask.cloudUri(masked, linked) ?: throw IOException("Invalid HLS resource path")
        val entity = if (masked == entry) entryVersion else null
        val res = try {
            runBlocking { repositories.files(accountId).fileUrls(listOf(cloud.toString()), entity = entity) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // 业务错误（不存在、无权限等）也按加载失败交给播放器
            throw IOException(e)
        }
        val raw = res.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() } ?: throw IOException("Empty file url")
        val absolute = if (raw.startsWith("/")) accounts.account(accountId)?.siteUrl.orEmpty().trimEnd('/') + raw else raw
        val target = absolute.toUri()
        synchronized(cache) { cache[masked] = target to res.expires }
        return target
    }
}

/**
 * 视频数据源：掩码地址经 [HlsSigner] 换成签名地址后加载，其余地址原样交给默认数据源。
 * 每个数据源实例把打开的掩码地址（而不是签名地址或其重定向）回报给播放器，
 * 播放器据此解析播放列表里的相对地址，分片与子播放列表因而仍在掩码空间内。
 */
@OptIn(UnstableApi::class)
internal class HlsDataSourceFactory(context: Context, private val signer: HlsSigner) : DataSource.Factory {
    private val upstream = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = ResolvingDataSource(
        upstream.createDataSource(),
        object : ResolvingDataSource.Resolver {
            private var opened: Uri? = null

            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val uri = dataSpec.uri
                if (!HlsMask.isMasked(uri.toString())) {
                    opened = null
                    return dataSpec
                }
                opened = uri
                return dataSpec.withUri(signer.sign(uri.toString()))
            }

            override fun resolveReportedUri(uri: Uri): Uri = opened ?: uri
        },
    )
}
