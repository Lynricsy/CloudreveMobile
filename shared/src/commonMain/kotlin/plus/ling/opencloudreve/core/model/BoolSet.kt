package plus.ling.opencloudreve.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * 服务端 `boolset.BooleanSet` 的客户端镜像（`capability`、`group.permission` 等）。
 *
 * JSON 中是标准 base64（带填充）字符串；第 `i` 位位于 `bytes[i / 8]` 的 `1 shl (i % 8)`（低位优先），
 * 越界视为 false（`pkg/boolset/boolset.go:Enabled`）。
 */
@Serializable(with = BoolSetSerializer::class)
class BoolSet(bytes: ByteArray = ByteArray(0)) {
    private val data: ByteArray = bytes.copyOf()

    /** 原始字节（副本） */
    val bytes: ByteArray get() = data.copyOf()

    /** 第 [bit] 位是否为 1；负数或越界返回 false */
    fun has(bit: Int): Boolean {
        if (bit < 0 || bit >= data.size * 8) return false
        return (data[bit / 8].toInt() and (1 shl (bit % 8))) != 0
    }

    /** 返回设置/清除第 [bit] 位后的新集合（不修改自身；仅用于测试与本地构造） */
    fun with(bit: Int, enabled: Boolean = true): BoolSet {
        require(bit >= 0) { "bit must be >= 0" }
        val size = maxOf(data.size, bit / 8 + 1)
        val next = data.copyOf(size)
        val mask = (1 shl (bit % 8))
        next[bit / 8] = if (enabled) {
            (next[bit / 8].toInt() or mask).toByte()
        } else {
            (next[bit / 8].toInt() and mask.inv()).toByte()
        }
        return BoolSet(next)
    }

    /** 标准 base64（带填充），与 Go `base64.StdEncoding` 一致 */
    fun toBase64(): String = Base64.encode(data)

    override fun equals(other: Any?): Boolean {
        if (other !is BoolSet) return false
        // 末尾的 0 字节不影响语义
        val n = maxOf(data.size, other.data.size)
        for (i in 0 until n) {
            val a = if (i < data.size) data[i] else 0
            val b = if (i < other.data.size) other.data[i] else 0
            if (a != b) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        var h = 1
        for (i in 0 until end) h = 31 * h + data[i]
        return h
    }

    override fun toString(): String = "BoolSet(${toBase64()})"

    companion object {
        val EMPTY: BoolSet = BoolSet()

        /** 解析 base64；空串得到空集合。非法输入抛 [IllegalArgumentException] */
        fun fromBase64(s: String): BoolSet {
            if (s.isEmpty()) return EMPTY
            return BoolSet(Base64.decode(s))
        }

        /** 由位索引构造（测试/本地使用） */
        fun of(vararg bits: Int): BoolSet = bits.fold(EMPTY) { acc, b -> acc.with(b) }
    }
}

/** `BoolSet` <-> base64 字符串；解析失败按空集合处理（宽容的旧/异常数据） */
object BoolSetSerializer : KSerializer<BoolSet> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("plus.ling.opencloudreve.BoolSet", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BoolSet) = encoder.encodeString(value.toBase64())

    override fun deserialize(decoder: Decoder): BoolSet =
        runCatching { BoolSet.fromBase64(decoder.decodeString()) }.getOrDefault(BoolSet.EMPTY)
}

/**
 * 文件系统导航能力位（`pkg/filemanager/fs/dbfs/navigator.go:80-105`）。
 * 目录列表的 `props.capability` 与 `FileResponse.capability` 使用。
 */
object NavigatorCapability {
    const val CREATE_FILE = 0
    const val RENAME_FILE = 1
    const val UPLOAD_FILE = 6
    const val DOWNLOAD_FILE = 7
    const val UPDATE_METADATA = 8
    const val LIST_CHILDREN = 9
    const val GENERATE_THUMB = 10
    const val DELETE_FILE = 14
    const val LOCK_FILE = 15
    const val SOFT_DELETE = 16
    const val RESTORE = 17
    const val SHARE = 18
    const val INFO = 19
    const val VERSION_CONTROL = 20
    const val ENTER_FOLDER = 23
    const val MODIFY_PROPS = 24
}

/** 用户组权限位（`inventory/types/types.go` 的 `GroupPermission*`） */
object GroupPermission {
    const val IS_ADMIN = 0
    const val IS_ANONYMOUS = 1
    const val SHARE = 2
    const val WEBDAV = 3
    const val ARCHIVE_DOWNLOAD = 4
    const val ARCHIVE_TASK = 5
    const val WEBDAV_PROXY = 6
    const val SHARE_DOWNLOAD = 7
    const val RELOCATE = 8
    const val REMOTE_DOWNLOAD = 9
    const val SELECT_NODE = 10
    const val REDIRECTED_SOURCE = 11
    const val ADVANCE_DELETE = 12
    const val FOLDER_DIRECT_LINK = 14
    const val IGNORE_FILE_OWNERSHIP = 16
    const val UNIQUE_DIRECT_LINK = 17
}
