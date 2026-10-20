package plus.ling.opencloudreve.ui.viewer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import plus.ling.opencloudreve.MainActivity
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.di.DataRepositories
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 音乐后台播放：持有 ExoPlayer 与 [MediaSession]，Media3 自动维护前台服务通知与锁屏控制。
 *
 * 播放列表项的地址是 `ocr-track://` 占位（见 [trackUri]），在真正加载时才通过 `POST /file/url` 换成签名地址
 * （缓存到过期前 60 秒）——这样页面关闭后继续播放、切到很久以后的曲目也不会用到过期地址。
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private val repositories: DataRepositories by inject()
    private val accounts: AccountRepository by inject()
    private var session: MediaSession? = null
    private val resolved = HashMap<String, Pair<Uri, Instant?>>()

    override fun onCreate() {
        super.onCreate()
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec -> resolve(spec) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // 控制器跨进程传递时会丢掉 localConfiguration：用 requestMetadata.mediaUri 还原地址
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                        if (uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList(),
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** 在加载线程上把 `ocr-track://` 换成签名地址 */
    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.scheme != TRACK_SCHEME) return spec
        val key = uri.toString()
        synchronized(resolved) {
            resolved[key]?.let { (cached, expires) ->
                if (expires == null || expires - Clock.System.now() > 60.seconds) return spec.withUri(cached)
            }
        }
        val accountId = uri.host ?: throw IOException("Missing account")
        val fileUri = uri.getQueryParameter("uri") ?: throw IOException("Missing file uri")
        val entity = uri.getQueryParameter("entity")
        val res = runBlocking { repositories.files(accountId).fileUrls(listOf(fileUri), entity = entity) }
        val raw = res.urls.firstOrNull()?.url?.takeIf { it.isNotEmpty() } ?: throw IOException("Empty file url")
        val absolute = if (raw.startsWith("/")) accounts.account(accountId)?.siteUrl.orEmpty().trimEnd('/') + raw else raw
        val target = absolute.toUri()
        synchronized(resolved) { resolved[key] = target to res.expires }
        return spec.withUri(target)
    }

    companion object {
        private const val TRACK_SCHEME = "ocr-track"

        /** 播放列表项的占位地址（账户 + 链接目标 URI + 版本） */
        fun trackUri(accountId: String, fileUri: String, entity: String?): Uri =
            Uri.Builder().scheme(TRACK_SCHEME).authority(accountId).appendQueryParameter("uri", fileUri).apply {
                if (entity != null) appendQueryParameter("entity", entity)
            }.build()
    }
}
