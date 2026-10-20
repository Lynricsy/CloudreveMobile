package plus.ling.opencloudreve.core.presentation.viewer

import plus.ling.opencloudreve.core.api.ApiException
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.api.LockConflict
import plus.ling.opencloudreve.core.api.LockConflictException
import plus.ling.opencloudreve.core.files.FileRepository
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError

/** 文本编码（按 BOM 识别；无 BOM 一律按 UTF-8） */
enum class TextEncoding { Utf8, Utf8Bom, Utf16Le, Utf16Be }

/** 解码结果 */
internal data class DecodedText(val text: String, val encoding: TextEncoding)

/** 文本编解码：识别 UTF-8/UTF-16 BOM，保存时按原编码写回 */
internal object TextCodec {
    fun decode(bytes: ByteArray): DecodedText = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            DecodedText(bytes.decodeToString(3, bytes.size), TextEncoding.Utf8Bom)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            DecodedText(utf16(bytes, 2, littleEndian = true), TextEncoding.Utf16Le)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            DecodedText(utf16(bytes, 2, littleEndian = false), TextEncoding.Utf16Be)
        else -> DecodedText(bytes.decodeToString(), TextEncoding.Utf8)
    }

    fun encode(text: String, encoding: TextEncoding): ByteArray = when (encoding) {
        TextEncoding.Utf8 -> text.encodeToByteArray()
        TextEncoding.Utf8Bom -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.encodeToByteArray()
        TextEncoding.Utf16Le -> utf16Bytes(text, littleEndian = true)
        TextEncoding.Utf16Be -> utf16Bytes(text, littleEndian = false)
    }

    private fun utf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val sb = StringBuilder((bytes.size - offset) / 2)
        var i = offset
        while (i + 1 < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            sb.append((if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun utf16Bytes(text: String, littleEndian: Boolean): ByteArray {
        val out = ByteArray(2 + text.length * 2)
        out[0] = if (littleEndian) 0xFF.toByte() else 0xFE.toByte()
        out[1] = if (littleEndian) 0xFE.toByte() else 0xFF.toByte()
        text.forEachIndexed { idx, c ->
            val hi = (c.code shr 8).toByte()
            val lo = (c.code and 0xFF).toByte()
            out[2 + idx * 2] = if (littleEndian) lo else hi
            out[3 + idx * 2] = if (littleEndian) hi else lo
        }
        return out
    }
}

data class ViewerTextState(
    val file: FileItem,
    /** `monaco` 或 `markdown` */
    val viewerId: String,
    /** 打开（或上次保存）时的内容；未加载时为 null */
    val original: String? = null,
    /** 编辑中的内容 */
    val draft: String = "",
    val encoding: TextEncoding = TextEncoding.Utf8,
    /** 保存基线版本（打开时的 `primary_entity`，保存成功后更新）；软链接文件为 null */
    val baseVersion: String? = null,
    /** 只读：查看历史版本，或不是自己的文件（分享、他人文件、回收站） */
    val readOnly: Boolean = false,
    val loading: Boolean = true,
    /** 加载失败 */
    val error: UiError? = null,
    val saving: Boolean = false,
    /** 40076：文件已被他人修改。平台询问“覆盖”（[ViewerTextIntent.Overwrite]）或“另存为”（[ViewerTextIntent.SaveAs]） */
    val staleVersion: Boolean = false,
    /** 40073：锁冲突列表，可 [ViewerTextIntent.ForceUnlockAndSave] */
    val lockConflicts: List<LockConflict> = emptyList(),
) {
    val dirty: Boolean get() = original != null && draft != original
    val isMarkdown: Boolean get() = viewerId == BuiltinViewerId.MARKDOWN || file.extension == "md"
    val canSave: Boolean get() = !readOnly && original != null && dirty && !saving
}

sealed interface ViewerTextIntent {
    data object Reload : ViewerTextIntent
    data class Edit(val text: String) : ViewerTextIntent

    /** 放弃修改，恢复到 [ViewerTextState.original] */
    data object Discard : ViewerTextIntent

    /** `PUT /file/content?uri=&previous=<基线版本>` */
    data object Save : ViewerTextIntent

    /** 版本冲突后强制覆盖（不带 previous 重试） */
    data object Overwrite : ViewerTextIntent

    /** 版本冲突后另存为 [parentUri]/[name]（带原基线版本） */
    data class SaveAs(val parentUri: String, val name: String) : ViewerTextIntent

    /** 强制解锁冲突项后重放上一次保存 */
    data object ForceUnlockAndSave : ViewerTextIntent

    /** 关闭冲突提示（保留编辑内容） */
    data object DismissConflict : ViewerTextIntent
}

sealed interface ViewerTextEffect {
    data class Saved(val file: FileItem) : ViewerTextEffect
    data class SavedAs(val file: FileItem) : ViewerTextEffect

    /** 保存失败：40049 → [ErrorKind.FileTooLarge]，其余按通用映射（冲突走状态而非此事件） */
    data class Failed(val error: UiError) : ViewerTextEffect
}

/**
 * 文本/Markdown 预览与编辑（monaco、markdown 预览器）：取签名地址下载内容并按 BOM 解码；
 * 保存按前端 `saveFile` 规则处理 40076（覆盖/另存为）、40049（超限）、40073（强制解锁重放）。
 */
class ViewerTextViewModel(
    private val params: ViewerParams,
    private val env: ViewerEnvironment,
) : CoreViewModel<ViewerTextState, ViewerTextIntent, ViewerTextEffect>(
    ViewerTextState(
        file = params.file,
        viewerId = params.viewerId ?: BuiltinViewerId.MONACO,
        baseVersion = if (params.file.isSymbolic) null else params.version ?: params.file.primaryEntity,
        readOnly = isReadOnly(params),
    ),
) {
    /** 最近一次保存请求（锁冲突解除后重放） */
    private var pending: SaveRequest? = null

    private data class SaveRequest(val uri: String, val previous: String?, val saveAs: Boolean)

    init {
        load()
    }

    override fun on(intent: ViewerTextIntent) {
        when (intent) {
            ViewerTextIntent.Reload -> load()
            is ViewerTextIntent.Edit -> if (!currentState.readOnly) updateState { it.copy(draft = intent.text) }
            ViewerTextIntent.Discard -> updateState { it.copy(draft = it.original.orEmpty()) }
            ViewerTextIntent.Save -> if (currentState.canSave) save(saveTarget(), currentState.baseVersion, saveAs = false)
            ViewerTextIntent.Overwrite -> {
                updateState { it.copy(staleVersion = false) }
                save(saveTarget(), null, saveAs = false)
            }
            is ViewerTextIntent.SaveAs -> {
                val name = intent.name.trim()
                if (name.isEmpty()) {
                    sendEffect(ViewerTextEffect.Failed(UiError(ErrorKind.Validation)))
                } else {
                    updateState { it.copy(staleVersion = false) }
                    save(FileRepository.childUri(intent.parentUri, name), currentState.baseVersion, saveAs = true)
                }
            }
            ViewerTextIntent.ForceUnlockAndSave -> forceUnlock()
            ViewerTextIntent.DismissConflict -> {
                pending = null
                updateState { it.copy(staleVersion = false, lockConflicts = emptyList()) }
            }
        }
    }

    /** 软链接保存到链接目标（不带 previous） */
    private fun saveTarget(): String = currentState.file.linkedUri

    private fun load() {
        updateState { it.copy(loading = true, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val url = env.url(params.file, params.version, params.contextHint)
            val decoded = TextCodec.decode(env.download(params.accountId, url.url))
            updateState { it.copy(loading = false, original = decoded.text, draft = decoded.text, encoding = decoded.encoding) }
        }
    }

    private fun save(uri: String, previous: String?, saveAs: Boolean) {
        if (currentState.saving) return
        val request = SaveRequest(uri, previous.takeUnless { currentState.file.isSymbolic && !saveAs }, saveAs)
        pending = request
        val text = currentState.draft
        updateState { it.copy(saving = true) }
        launchCatching(onError = { e -> updateState { it.copy(saving = false) }; sendEffect(ViewerTextEffect.Failed(e)) }) {
            try {
                val saved = env.files.putContent(request.uri, TextCodec.encode(text, currentState.encoding), request.previous)
                pending = null
                updateState { s ->
                    if (request.saveAs) {
                        s.copy(saving = false, original = text)
                    } else {
                        s.copy(
                            saving = false,
                            original = text,
                            // 软链接文件保持原对象，基线仍为空
                            file = if (s.file.isSymbolic) s.file else saved,
                            baseVersion = if (s.file.isSymbolic) null else saved.primaryEntity,
                        )
                    }
                }
                sendEffect(if (request.saveAs) ViewerTextEffect.SavedAs(saved) else ViewerTextEffect.Saved(saved))
            } catch (e: LockConflictException) {
                updateState { it.copy(saving = false, lockConflicts = e.conflicts) }
            } catch (e: ApiException) {
                if (e.code == ErrorCode.STALE_VERSION) {
                    updateState { it.copy(saving = false, staleVersion = true) }
                } else {
                    throw e
                }
            }
        }
    }

    private fun forceUnlock() {
        val request = pending ?: return
        val tokens = currentState.lockConflicts.mapNotNull { it.token }
        updateState { it.copy(lockConflicts = emptyList()) }
        launchCatching(onError = { e -> sendEffect(ViewerTextEffect.Failed(e)) }) {
            if (tokens.isNotEmpty()) env.files.unlock(tokens)
            save(request.uri, request.previous, request.saveAs)
        }
    }

    companion object {
        /** 只读判定：查看历史版本、不是自己的文件、或位于分享/回收站/与我共享中 */
        fun isReadOnly(params: ViewerParams): Boolean {
            val file = params.file
            if (params.version != null && params.version != file.primaryEntity) return true
            if (!file.owned) return true
            val fs = file.uri()?.fs ?: return true
            return fs != CrUri.FS_MY
        }
    }
}
