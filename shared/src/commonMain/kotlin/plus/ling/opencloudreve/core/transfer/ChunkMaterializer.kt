package plus.ling.opencloudreve.core.transfer

import plus.ling.opencloudreve.core.api.CloudreveException
import plus.ling.opencloudreve.core.crypto.AesCtr

/** 已写入临时文件的请求体；[path] 由执行器在请求结束后通过 [ChunkMaterializer.discard] 删除 */
data class MaterializedChunk(val path: String, val request: ChunkRequest)

/**
 * 生成分片请求体字节（读取源文件区间，必要时按绝对偏移做 AES-256-CTR，表单上传拼装 multipart）。
 *
 * - iOS 后台 URLSession 只能从文件上传：用 [materialize] 写到临时文件，再 `uploadTask(with:fromFile:)`。
 * - Android 可直接用 [stream] 边读边写入请求体，避免整片进内存。
 */
class ChunkMaterializer(
    private val planner: UploadPlanner,
    private val fileSystem: FileSystemBridge,
    private val repository: TransferRepository,
    /** 每次读取/加密的块大小（16 的倍数以免补齐开销） */
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
) {
    init {
        require(blockSize > 0 && blockSize % AesCtr.BLOCK_SIZE == 0) { "blockSize must be a positive multiple of 16" }
    }

    /** 重新生成 [taskId] 第 [index] 片的请求（含最新 token）并把请求体写入临时文件 */
    @Throws(Exception::class)
    suspend fun materialize(taskId: String, index: Int): MaterializedChunk =
        materialize(planner.requestFor(taskId, index))

    /** 把 [request] 的请求体写入临时文件 `<tempDir>/chunks/<taskId>_<index>.part` */
    @Throws(Exception::class)
    suspend fun materialize(request: ChunkRequest): MaterializedChunk {
        val path = pathFor(request.taskId, request.index)
        fileSystem.delete(path)
        // 先建立空文件，0 字节分片也得到一个可上传的文件
        fileSystem.writeFile(path, ByteArray(0))
        stream(request) { fileSystem.appendFile(path, it) }
        return MaterializedChunk(path, request)
    }

    /** 按顺序把 [request] 的请求体分块交给 [sink]（总长度 = `request.body.contentLength`） */
    @Throws(Exception::class)
    suspend fun stream(request: ChunkRequest, sink: suspend (ByteArray) -> Unit) {
        val sourceRef = repository.row(request.taskId)?.source_ref
            ?: throw CloudreveException("Upload source missing for ${request.taskId}")
        val cipher = planner.encryptionFor(request.taskId)
        when (val body = request.body) {
            is ChunkBody.FileRange -> streamRange(sourceRef, body, cipher, sink)
            is ChunkBody.Multipart -> {
                sink(body.head())
                streamRange(sourceRef, body.file, cipher, sink)
                sink(body.tail())
            }
        }
    }

    /** 整个请求体读入内存（仅适合小分片或测试） */
    @Throws(Exception::class)
    suspend fun readBody(request: ChunkRequest): ByteArray {
        val out = ArrayList<ByteArray>()
        stream(request) { out += it }
        val result = ByteArray(out.sumOf { it.size })
        var pos = 0
        out.forEach { it.copyInto(result, pos); pos += it.size }
        return result
    }

    /** 删除 [materialize] 写出的临时文件 */
    @Throws(Exception::class)
    suspend fun discard(taskId: String, index: Int) = fileSystem.delete(pathFor(taskId, index))

    fun pathFor(taskId: String, index: Int): String = fileSystem.tempDir().trimEnd('/') + "/chunks/${taskId}_$index.part"

    private suspend fun streamRange(
        sourceRef: String,
        range: ChunkBody.FileRange,
        cipher: Pair<ByteArray, ByteArray>?,
        sink: suspend (ByteArray) -> Unit,
    ) {
        var offset = range.offset
        val end = range.offset + range.length
        while (offset < end) {
            val want = minOf(blockSize.toLong(), end - offset).toInt()
            val plain = fileSystem.readRange(sourceRef, offset, want)
            if (plain.isEmpty()) throw CloudreveException("Upload source ended at $offset, expected $end bytes")
            val bytes = if (range.encrypted) {
                // 会话已被清理时绝不能退化为发送明文
                val (key, iv) = cipher ?: throw CloudreveException("Encryption key unavailable for upload")
                AesCtr.encryptAt(key, iv, offset, plain)
            } else {
                plain
            }
            sink(bytes)
            offset += plain.size
        }
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 1 shl 20
    }
}
