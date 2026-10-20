package plus.ling.opencloudreve.core.transfer

/**
 * 平台文件系统桥（Android 用 ContentResolver/文件、iOS 用安全作用域书签/App Group 容器实现）。
 *
 * [sourceRef] 是平台不透明字符串：Android 为 content:// 或 file:// URI，iOS 为书签（base64）或文件路径。
 * 普通路径参数（[tempDir] 下的文件、下载目标）均为本地绝对路径。
 */
interface FileSystemBridge {
    /** 源文件字节数；源不可访问（权限丢失、已删除）时返回 null */
    @Throws(Exception::class)
    suspend fun size(sourceRef: String): Long?

    /** 从 [offset] 起读取最多 [length] 字节；到达末尾时可返回更短的数组 */
    @Throws(Exception::class)
    suspend fun readRange(sourceRef: String, offset: Long, length: Int): ByteArray

    /** 可写临时目录的绝对路径（iOS 应位于 App Group 容器内，供扩展与主 App 共用） */
    fun tempDir(): String

    /** 以 [bytes] 覆盖写入 [path]（父目录不存在时创建） */
    @Throws(Exception::class)
    suspend fun writeFile(path: String, bytes: ByteArray)

    /** 追加写入 [path]（文件不存在时创建），用于分块写出大的分片临时文件 */
    @Throws(Exception::class)
    suspend fun appendFile(path: String, bytes: ByteArray)

    /** 删除文件；不存在时静默成功 */
    @Throws(Exception::class)
    suspend fun delete(path: String)
}
