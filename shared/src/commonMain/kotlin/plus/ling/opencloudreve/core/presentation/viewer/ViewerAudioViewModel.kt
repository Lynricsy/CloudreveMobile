package plus.ling.opencloudreve.core.presentation.viewer

import kotlinx.coroutines.Job
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError

/** 播放列表中的一首 */
data class AudioTrack(
    val file: FileItem,
    /** 签名地址；尚未取到时为 null */
    val url: ResolvedUrl? = null,
    /** 封面（`/file/thumb`）；无缩略图时为 null */
    val coverUrl: String? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
) {
    /** `music:title`，缺失用文件名（去扩展名） */
    val title: String get() = file.metadata[MetadataKeys.MUSIC_PREFIX + "title"]?.takeIf { it.isNotBlank() } ?: nameWithoutExtension(file.name)
    val artist: String? get() = file.metadata[MetadataKeys.MUSIC_PREFIX + "artist"]?.takeIf { it.isNotBlank() }
    val album: String? get() = file.metadata[MetadataKeys.MUSIC_PREFIX + "album"]?.takeIf { it.isNotBlank() }
}

data class ViewerAudioState(
    /** 播放列表：同目录中音乐预览器支持的文件；指定版本时只有当前文件 */
    val tracks: List<AudioTrack> = emptyList(),
    val index: Int = 0,
    /** 加载播放列表中 */
    val loading: Boolean = true,
) {
    val current: AudioTrack? get() = tracks.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < tracks.lastIndex
}

sealed interface ViewerAudioIntent {
    /** 切换到第 [index] 首（平台在曲目变化时投递，VM 取地址并预取下一首） */
    data class Play(val index: Int) : ViewerAudioIntent
    data object Next : ViewerAudioIntent
    data object Previous : ViewerAudioIntent

    /** 重新获取当前曲目地址（播放器报错或地址即将过期） */
    data object Retry : ViewerAudioIntent
}

/**
 * 音乐播放：播放列表 = 同目录内音乐预览器扩展名的文件；当前与下一首预取签名地址，当前曲目过期前 60 秒刷新。
 * 锁屏信息用 [AudioTrack.title]/[AudioTrack.artist]/[AudioTrack.album]/[AudioTrack.coverUrl]。
 */
class ViewerAudioViewModel(
    private val params: ViewerParams,
    private val env: ViewerEnvironment,
) : CoreViewModel<ViewerAudioState, ViewerAudioIntent, Nothing>(
    ViewerAudioState(tracks = listOf(AudioTrack(params.file)), loading = true),
) {
    private val refresh = UrlRefreshScheduler(scope, env.clock)
    private val inFlight = HashMap<String, Job>()

    init {
        prefetch(0)
        launchCatching(onError = { updateState { it.copy(loading = false) } }) {
            val files = if (params.version != null) {
                listOf(params.file)
            } else {
                val exts = env.extsOf(params.accountId, BuiltinViewerId.MUSIC, DEFAULT_EXTS)
                env.siblings(params).filter { !it.isFolder && (it.extension in exts || it.path == params.file.path) }
            }
            val start = files.indexOfFirst { it.path == params.file.path }.coerceAtLeast(0)
            updateState { s ->
                ViewerAudioState(files.map { f -> s.tracks.firstOrNull { it.file.path == f.path } ?: AudioTrack(f) }, start, loading = false)
            }
            prefetch(start)
        }
    }

    override fun on(intent: ViewerAudioIntent) {
        when (intent) {
            is ViewerAudioIntent.Play -> play(intent.index)
            ViewerAudioIntent.Next -> play(currentState.index + 1)
            ViewerAudioIntent.Previous -> play(currentState.index - 1)
            ViewerAudioIntent.Retry -> load(currentState.index, force = true)
        }
    }

    private fun play(index: Int) {
        if (index !in currentState.tracks.indices) return
        updateState { it.copy(index = index) }
        currentState.current?.url?.let { u -> refresh.schedule(u.expiresAt) { load(currentState.index, force = true) } }
        prefetch(index)
    }

    private fun prefetch(index: Int) {
        load(index, force = false)
        load(index + 1, force = false)
    }

    private fun load(index: Int, force: Boolean) {
        val track = currentState.tracks.getOrNull(index) ?: return
        val path = track.file.path
        if (!force && (track.url != null || inFlight[path]?.isActive == true)) return
        update(path) { it.copy(loading = true, error = null) }
        inFlight[path] = launchCatching(onError = { e -> update(path) { it.copy(loading = false, error = e) } }) {
            val url = env.url(track.file, params.version, params.contextHint)
            val cover = if (track.coverUrl == null) runCatching { env.files.thumbUrl(track.file, params.contextHint) }.getOrNull() else track.coverUrl
            update(path) { it.copy(url = url, coverUrl = cover, loading = false) }
            if (currentState.current?.file?.path == path) refresh.schedule(url.expiresAt) { load(currentState.index, force = true) }
        }
    }

    private fun update(path: String, transform: (AudioTrack) -> AudioTrack) = updateState { s ->
        s.copy(tracks = s.tracks.map { if (it.file.path == path) transform(it) else it })
    }

    companion object {
        /** 前端默认音乐预览器扩展名 */
        val DEFAULT_EXTS = setOf("mp3", "ogg", "wav", "flac", "m4a")
    }
}
