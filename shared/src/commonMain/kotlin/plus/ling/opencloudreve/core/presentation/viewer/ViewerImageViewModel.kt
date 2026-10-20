package plus.ling.opencloudreve.core.presentation.viewer

import kotlinx.coroutines.Job
import plus.ling.opencloudreve.core.model.EntityType
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.MetadataKeys
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError

/** 图片浏览中的一张 */
data class ViewerImage(
    val file: FileItem,
    /** 原图签名地址；尚未取到时为 null */
    val url: ResolvedUrl? = null,
    /** 实况照片的视频地址（`customize:live_photo`，按需加载） */
    val livePhotoUrl: ResolvedUrl? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
) {
    /** 带 `customize:live_photo` 元数据 */
    val isLivePhoto: Boolean get() = MetadataKeys.LIVE_PHOTO in file.metadata

    /** EXIF 信息（`exif:*`） */
    val exif: List<MetadataEntry> get() = metadataWithPrefix(file, MetadataKeys.EXIF_PREFIX)

    /** 地理编码（`geocoding:*`） */
    val geocoding: List<MetadataEntry> get() = metadataWithPrefix(file, MetadataKeys.GEOCODING_PREFIX)
}

data class ViewerImageState(
    /** 同目录图片（按图片预览器扩展名过滤，保持列表顺序）；指定版本时只有当前文件 */
    val images: List<ViewerImage> = emptyList(),
    val index: Int = 0,
    /** 加载同目录列表中 */
    val loading: Boolean = true,
) {
    val current: ViewerImage? get() = images.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < images.lastIndex
}

sealed interface ViewerImageIntent {
    data class Select(val index: Int) : ViewerImageIntent
    data object Next : ViewerImageIntent
    data object Previous : ViewerImageIntent

    /** 重新获取当前图片地址（加载失败时重试） */
    data object Retry : ViewerImageIntent

    /** 取当前图片的实况视频地址（长按播放前调用） */
    data object LoadLivePhoto : ViewerImageIntent
}

/**
 * 图片预览：左右滑动浏览同目录图片，按需获取签名地址（当前与相邻各一张预取），过期前刷新；
 * 实况照片从 `GET /file/info?extended=true` 的 `type==2` 实体取视频地址。
 */
class ViewerImageViewModel(
    private val params: ViewerParams,
    private val env: ViewerEnvironment,
) : CoreViewModel<ViewerImageState, ViewerImageIntent, Nothing>(
    ViewerImageState(images = listOf(ViewerImage(params.file)), loading = true),
) {
    private val refresh = UrlRefreshScheduler(scope, env.clock)
    private val loading = HashMap<String, Job>()

    init {
        // 先取当前文件地址，同目录列表随后补齐
        prefetchAround(0)
        launchCatching(onError = { updateState { it.copy(loading = false) } }) {
            val images = if (params.version != null) {
                listOf(params.file)
            } else {
                val exts = env.extsOf(params.accountId, BuiltinViewerId.IMAGE, DEFAULT_EXTS)
                env.siblings(params).filter { !it.isFolder && (it.extension in exts || it.path == params.file.path) }
            }
            val start = images.indexOfFirst { it.path == params.file.path }.coerceAtLeast(0)
            updateState { s ->
                // 保留已取到地址的项
                ViewerImageState(images.map { f -> s.images.firstOrNull { it.file.path == f.path } ?: ViewerImage(f) }, start, loading = false)
            }
            prefetchAround(start)
        }
    }

    override fun on(intent: ViewerImageIntent) {
        when (intent) {
            is ViewerImageIntent.Select -> select(intent.index)
            ViewerImageIntent.Next -> select(currentState.index + 1)
            ViewerImageIntent.Previous -> select(currentState.index - 1)
            ViewerImageIntent.Retry -> load(currentState.index, force = true)
            ViewerImageIntent.LoadLivePhoto -> loadLivePhoto(currentState.index)
        }
    }

    private fun select(index: Int) {
        if (index !in currentState.images.indices) return
        updateState { it.copy(index = index) }
        currentState.current?.url?.let { u -> refresh.schedule(u.expiresAt) { load(currentState.index, force = true) } }
        prefetchAround(index)
    }

    private fun prefetchAround(index: Int) {
        load(index, force = false)
        load(index + 1, force = false)
        load(index - 1, force = false)
    }

    private fun load(index: Int, force: Boolean) {
        val image = currentState.images.getOrNull(index) ?: return
        val path = image.file.path
        if (!force && (image.url != null || loading[path]?.isActive == true)) return
        update(path) { it.copy(loading = true, error = null) }
        loading[path] = launchCatching(onError = { e -> update(path) { it.copy(loading = false, error = e) } }) {
            val url = env.url(image.file, params.version, params.contextHint)
            update(path) { it.copy(url = url, loading = false) }
            // 只为当前图片安排过期刷新
            if (currentState.current?.file?.path == path) refresh.schedule(url.expiresAt) { load(currentState.index, force = true) }
        }
    }

    private fun loadLivePhoto(index: Int) {
        val image = currentState.images.getOrNull(index) ?: return
        if (!image.isLivePhoto || image.livePhotoUrl != null) return
        val path = image.file.path
        launchCatching(onError = { e -> update(path) { it.copy(error = e) } }) {
            val info = env.files.info(image.file.linkedUri, extended = true)
            val entity = info.extendedInfo?.entities?.firstOrNull { it.type == EntityType.LIVE_PHOTO } ?: return@launchCatching
            val url = env.url(image.file, entity.id, params.contextHint)
            update(path) { it.copy(livePhotoUrl = url) }
        }
    }

    private fun update(path: String, transform: (ViewerImage) -> ViewerImage) = updateState { s ->
        s.copy(images = s.images.map { if (it.file.path == path) transform(it) else it })
    }

    companion object {
        /** 前端默认图片预览器扩展名 */
        val DEFAULT_EXTS = setOf("bmp", "png", "gif", "jpg", "jpeg", "svg", "webp", "heic", "heif")
    }
}
