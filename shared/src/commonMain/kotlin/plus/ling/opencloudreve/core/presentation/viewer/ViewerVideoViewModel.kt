package plus.ling.opencloudreve.core.presentation.viewer

import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError

/** 外挂字幕候选 */
data class SubtitleOption(
    val file: FileItem,
    /** 小写扩展名：srt / ass / ssa / vtt / vrr */
    val format: String,
    /** 文件名以 `<视频名>.` 开头（同名字幕） */
    val matchesVideo: Boolean,
)

/** 视频章节（`stream:chapter_<id>_{start_time,end_time,name}`，单位秒） */
data class VideoChapter(
    val id: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val title: String,
)

data class ViewerVideoState(
    val file: FileItem,
    /** 视频签名地址（过期前 60 秒自动刷新，平台替换播放源时保持进度） */
    val url: ResolvedUrl? = null,
    val loading: Boolean = true,
    val error: UiError? = null,
    /** 同目录字幕，同名优先 */
    val subtitles: List<SubtitleOption> = emptyList(),
    /** 选中的字幕（初始为第一个同名字幕）；null 表示关闭 */
    val selectedSubtitle: SubtitleOption? = null,
    /** 选中字幕的签名地址 */
    val subtitleUrl: ResolvedUrl? = null,
    /** 选中字幕的文本内容（UTF-8；平台自行解析 SRT/ASS，或直接用 [subtitleUrl]） */
    val subtitleText: String? = null,
    val subtitleLoading: Boolean = false,
    val chapters: List<VideoChapter> = emptyList(),
) {
    /** 流信息（`stream:*`，不含章节） */
    val streamInfo: List<MetadataEntry>
        get() = metadataWithPrefix(file, MetadataKeys.STREAM_PREFIX).filterNot { it.key.startsWith("chapter_") }

    /** HLS 播放列表（m3u8 内相对地址需逐个签名，平台按需处理） */
    val isHls: Boolean get() = file.extension == "m3u8"
}

sealed interface ViewerVideoIntent {
    /** 重新获取播放地址（加载失败或播放器报地址过期时） */
    data object Retry : ViewerVideoIntent

    /** 选择字幕；null 关闭字幕 */
    data class SelectSubtitle(val path: String?) : ViewerVideoIntent
}

/** 视频预览：播放地址（过期前刷新）、同目录外挂字幕、章节 */
class ViewerVideoViewModel(
    private val params: ViewerParams,
    private val env: ViewerEnvironment,
) : CoreViewModel<ViewerVideoState, ViewerVideoIntent, Nothing>(
    ViewerVideoState(params.file, chapters = chapters(params.file)),
) {
    private val refresh = UrlRefreshScheduler(scope, env.clock)

    init {
        loadUrl()
        launchCatching(onError = {}) {
            val subs = subtitlesFor(params.file, env.siblings(params))
            updateState { it.copy(subtitles = subs) }
            subs.firstOrNull { it.matchesVideo }?.let { selectSubtitle(it.file.path) }
        }
    }

    override fun on(intent: ViewerVideoIntent) {
        when (intent) {
            ViewerVideoIntent.Retry -> loadUrl()
            is ViewerVideoIntent.SelectSubtitle -> selectSubtitle(intent.path)
        }
    }

    private fun loadUrl() {
        updateState { it.copy(loading = it.url == null, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val url = env.url(params.file, params.version, params.contextHint)
            updateState { it.copy(url = url, loading = false) }
            refresh.schedule(url.expiresAt, onError = {}) { loadUrl() }
        }
    }

    private fun selectSubtitle(path: String?) {
        val option = currentState.subtitles.firstOrNull { it.file.path == path }
        updateState { it.copy(selectedSubtitle = option, subtitleUrl = null, subtitleText = null, subtitleLoading = option != null) }
        if (option == null) return
        launchCatching(onError = { updateState { s -> if (s.selectedSubtitle == option) s.copy(subtitleLoading = false) else s } }) {
            val url = env.url(option.file, null, params.contextHint)
            val text = env.download(params.accountId, url.url).let(TextCodec::decode).text
            updateState { s -> if (s.selectedSubtitle == option) s.copy(subtitleUrl = url, subtitleText = text, subtitleLoading = false) else s }
        }
    }

    companion object {
        /** 字幕扩展名（前端为 ass/srt/vrr，另支持 ssa 与 vtt） */
        val SUBTITLE_EXTS = listOf("srt", "ass", "ssa", "vtt", "vrr")

        /**
         * 同目录字幕：扩展名在 [SUBTITLE_EXTS] 内；以 `<视频名去扩展名>.` 开头（或同名）的排在前面，其余保持原顺序。
         */
        fun subtitlesFor(video: FileItem, siblings: List<FileItem>): List<SubtitleOption> {
            val prefix = nameWithoutExtension(video.name) + "."
            return siblings
                .filter { !it.isFolder && it.extension in SUBTITLE_EXTS }
                .map { SubtitleOption(it, it.extension, it.name.startsWith(prefix)) }
                .sortedBy { if (it.matchesVideo) 0 else 1 }
        }

        /** 解析章节元数据，按开始时间排序 */
        fun chapters(file: FileItem): List<VideoChapter> {
            val map = LinkedHashMap<String, VideoChapter>()
            val prefix = MetadataKeys.STREAM_PREFIX + "chapter_"
            for ((k, v) in file.metadata) {
                if (!k.startsWith(prefix)) continue
                val rest = k.removePrefix(prefix)
                val id = rest.substringBefore('_')
                val field = rest.substringAfter('_', "")
                val cur = map[id] ?: VideoChapter(id, 0.0, 0.0, "")
                map[id] = when (field) {
                    "start_time" -> cur.copy(startSeconds = v.toDoubleOrNull() ?: 0.0)
                    "end_time" -> cur.copy(endSeconds = v.toDoubleOrNull() ?: 0.0)
                    "name" -> cur.copy(title = v)
                    else -> cur
                }
            }
            return map.values.sortedBy { it.startSeconds }
        }
    }
}
