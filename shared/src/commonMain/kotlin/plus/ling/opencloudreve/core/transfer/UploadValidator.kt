package plus.ling.opencloudreve.core.transfer

import plus.ling.opencloudreve.core.model.StoragePolicy

/** 上传预校验不通过的原因（UI 按此本地化） */
enum class UploadRejection {
    /** 文件名为空、≥ 256 字节、含 `\ / : * ? " < > |` 或为 `.`/`..`（服务端 40052） */
    ILLEGAL_NAME,

    /** 扩展名不在允许列表 */
    SUFFIX_NOT_ALLOWED,

    /** 扩展名在禁止列表 */
    SUFFIX_DENIED,

    /** 文件名不匹配允许正则 */
    NAME_NOT_ALLOWED,

    /** 文件名匹配禁止正则 */
    NAME_DENIED,

    /** 超过策略 `max_size`（服务端 40049） */
    TOO_LARGE,
}

/**
 * 用当前目录列表返回的 `storage_policy` 做客户端预校验（`ocr-spec-upload.md §6`）。
 * 规则与服务端 `dbfs/validator.go` 一致（扩展名取最后一个 `.` 之后并转小写）；服务端仍会做权威校验。
 */
object UploadValidator {
    private const val MAX_NAME_BYTES = 256
    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"

    /** 通过返回 null；[policy] 为 null 时只校验文件名 */
    fun validate(name: String, size: Long, policy: StoragePolicy?): UploadRejection? {
        validateName(name)?.let { return it }
        policy ?: return null
        val children = policy.children
        if (policy.type == StoragePolicy.TYPE_LOAD_BALANCE && !children.isNullOrEmpty()) {
            // 负载均衡：服务端会挑选接受该文件的子策略，任一子策略通过即可
            val rejections = children.map { validatePolicy(name, size, it) }
            return if (rejections.any { it == null }) null else rejections.first()
        }
        return validatePolicy(name, size, policy)
    }

    /** 服务端文件名合法性规则 */
    fun validateName(name: String): UploadRejection? {
        val bytes = name.encodeToByteArray().size
        if (bytes == 0 || bytes >= MAX_NAME_BYTES) return UploadRejection.ILLEGAL_NAME
        if (name.any { it in ILLEGAL_CHARS }) return UploadRejection.ILLEGAL_NAME
        if (name == "." || name == "..") return UploadRejection.ILLEGAL_NAME
        return null
    }

    /** 服务端 `util.Ext`：最后一个 `.` 之后的部分，小写；没有扩展名为空串 */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot + 1).lowercase()
    }

    private fun validatePolicy(name: String, size: Long, policy: StoragePolicy): UploadRejection? {
        val ext = extension(name)
        val allowed = policy.allowedSuffix.orEmpty()
        if (allowed.isNotEmpty() && (ext.isEmpty() || ext !in allowed)) return UploadRejection.SUFFIX_NOT_ALLOWED
        val denied = policy.deniedSuffix.orEmpty()
        if (denied.isNotEmpty() && ext.isNotEmpty() && ext in denied) return UploadRejection.SUFFIX_DENIED

        policy.allowedNameRegexp?.takeIf { it.isNotEmpty() }?.let { pattern ->
            val regex = regexOrNull(pattern)
            if (regex != null && !regex.containsMatchIn(name)) return UploadRejection.NAME_NOT_ALLOWED
        }
        policy.deniedNameRegexp?.takeIf { it.isNotEmpty() }?.let { pattern ->
            val regex = regexOrNull(pattern)
            if (regex != null && regex.containsMatchIn(name)) return UploadRejection.NAME_DENIED
        }

        if (policy.maxSize > 0 && size > policy.maxSize) return UploadRejection.TOO_LARGE
        return null
    }

    /** 正则方言与 Go RE2 不完全一致；本地无法编译时交给服务端判断 */
    private fun regexOrNull(pattern: String): Regex? = runCatching { Regex(pattern) }.getOrNull()
}
