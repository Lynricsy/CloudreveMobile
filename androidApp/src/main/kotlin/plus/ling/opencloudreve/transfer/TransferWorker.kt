package plus.ling.opencloudreve.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import plus.ling.opencloudreve.core.api.ErrorCode
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.transfer.ChunkMaterializer
import plus.ling.opencloudreve.core.transfer.ChunkRequest
import plus.ling.opencloudreve.core.transfer.ChunkResponse
import plus.ling.opencloudreve.core.transfer.TransferErrorCode
import plus.ling.opencloudreve.core.transfer.TransferFailedException
import plus.ling.opencloudreve.core.transfer.TransferItem
import plus.ling.opencloudreve.core.transfer.TransferKind
import plus.ling.opencloudreve.core.transfer.TransferOutcome
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.TransferState
import plus.ling.opencloudreve.core.transfer.UploadPlanner
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 上传 Work：按 `UploadPlanner` 的循环执行一个上传任务（prepare → nextRequests → 发送 → onChunkDone → finalize）。
 * 任务失败（含 40004 冲突）也以 success 结束——状态已写入 DB，由传输列表提供重试；
 * 断网时任务退回 queued 并以 retry 结束，等网络约束满足后继续（会话可续传时复用已完成的分片）。
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repository: TransferRepository = CoreGraph.transferRepository()
    private val notifications = TransferNotifications(applicationContext)
    private var lastNotify = 0L

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(AndroidTransferExecutor.KEY_TASK_ID) ?: return Result.failure()
        val item = repository.task(taskId) ?: return Result.success()
        if (item.kind != TransferKind.UPLOAD || !item.state.isActive) return Result.success()
        promote(notifications.foreground(item, item.bytesDone, waiting = true))

        val driver = UploadDriver(
            context = applicationContext,
            taskId = taskId,
            repository = repository,
            planner = CoreGraph.uploadPlanner(),
            materializer = CoreGraph.chunkMaterializer(),
            http = TransferRuntime.http,
        ) { bytes -> notifyProgress(item, bytes) }
        val result = try {
            driver.run()
            Result.success()
        } catch (e: CancellationException) {
            requeueIfNetworkStop(this, repository, taskId)
            throw e
        } catch (_: NetworkLostException) {
            return waitForNetwork(taskId)
        } catch (e: TransferFailedException) {
            if (e.code == ErrorCode.TRANSPORT && TransferRuntime.isOffline(applicationContext)) return waitForNetwork(taskId)
            Result.success()
        } catch (e: Exception) {
            if (repository.task(taskId)?.state?.isActive == true) {
                repository.setState(taskId, TransferState.FAILED, ErrorCode.TRANSPORT, e.message ?: e.javaClass.simpleName)
            }
            Result.success()
        }
        repository.task(taskId)?.let { final ->
            notifications.finished(final)
            if (final.state == TransferState.COMPLETED) final.sourceRef?.let { UploadSources.discard(applicationContext, it) }
        }
        return result
    }

    /** 断网：退回排队，等待 WorkManager 在网络恢复后重跑 */
    private suspend fun waitForNetwork(taskId: String): Result {
        if (repository.task(taskId)?.state?.let { it.isActive || it == TransferState.FAILED } == true) {
            repository.setState(taskId, TransferState.QUEUED)
        }
        return Result.retry()
    }

    private suspend fun notifyProgress(item: TransferItem, bytes: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < NOTIFY_INTERVAL_MS) return
        lastNotify = now
        promote(notifications.foreground(item, bytes))
    }

    /** 提升为前台服务/更新进度通知；Android 12+ 后台启动前台服务受限时忽略（Work 仍继续执行） */
    private suspend fun promote(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val NOTIFY_INTERVAL_MS = 1000L
    }
}

/** 一个上传任务的执行循环（见 `local://p2-transfer-surface.md` “执行器如何驱动循环”） */
internal class UploadDriver(
    private val context: Context,
    private val taskId: String,
    private val repository: TransferRepository,
    private val planner: UploadPlanner,
    private val materializer: ChunkMaterializer,
    private val http: OkHttpClient,
    private val onProgress: suspend (bytesDone: Long) -> Unit,
) {
    /** 一个分片请求的发送结果（交回主循环串行处理） */
    private sealed interface Sent {
        val index: Int

        data class Response(override val index: Int, val response: ChunkResponse) : Sent
        data class NetworkError(override val index: Int, val message: String) : Sent
        data class SourceError(override val index: Int, val message: String) : Sent
        data class Crash(override val index: Int, val error: Throwable) : Sent
    }

    /** 保护 [committed]/[inflight] 与进度写库，避免节流写入覆盖规划器写入的准确值 */
    private val progressLock = Mutex()

    /** 在途分片已写出的字节 */
    private val inflight = ConcurrentHashMap<Int, Long>()

    /** 规划器记录的已确认字节 */
    private var committed = 0L

    suspend fun run() {
        while (true) {
            planner.prepare(taskId)
            if (!pump()) return
        }
    }

    /** 驱动一个会话直到完成/失败/暂停；返回 true 表示规划器要求重建会话（Restart） */
    private suspend fun pump(): Boolean = coroutineScope {
        progressLock.withLock {
            inflight.clear()
            committed = repository.task(taskId)?.bytesDone ?: 0
        }
        val results = Channel<Sent>(Channel.UNLIMITED)
        // 在途发送协程（send 内部已捕获全部非取消异常）；结束/重建会话时一并取消
        val senders = ArrayList<Job>()
        val ticker = launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                reportProgress()
            }
        }
        try {
            var running = 0
            while (true) {
                senders.removeAll { it.isCompleted }
                for (request in planner.nextRequests(taskId)) {
                    running++
                    inflight[request.index] = 0L
                    senders += launch { results.send(send(request)) }
                }
                if (running == 0) {
                    // 没有可发的请求也没有在途请求：全部完成则收尾，否则已被暂停/取消/判定失败
                    if (planner.allChunksDone(taskId)) {
                        ticker.cancelAndJoin()
                        planner.finalize(taskId)
                    }
                    return@coroutineScope false
                }
                val sent = results.receive()
                running--
                val outcome = progressLock.withLock {
                    inflight.remove(sent.index)
                    val outcome = when (sent) {
                        is Sent.Response -> planner.onChunkDone(taskId, sent.index, sent.response)
                        is Sent.NetworkError -> {
                            if (TransferRuntime.isOffline(context)) throw NetworkLostException()
                            planner.onChunkFailed(taskId, sent.index, sent.message)
                        }
                        is Sent.SourceError -> {
                            repository.setState(taskId, TransferState.FAILED, TransferErrorCode.SOURCE_UNAVAILABLE, sent.message)
                            TransferOutcome.Failed(TransferErrorCode.SOURCE_UNAVAILABLE, sent.message)
                        }
                        is Sent.Crash -> throw sent.error
                    }
                    committed = repository.task(taskId)?.bytesDone ?: committed
                    outcome
                }
                when (outcome) {
                    is TransferOutcome.Continue -> if (outcome.allChunksDone) {
                        ticker.cancelAndJoin()
                        planner.finalize(taskId)
                        return@coroutineScope false
                    }
                    is TransferOutcome.RetryAfter -> delay(outcome.delayMillis)
                    TransferOutcome.Restart -> return@coroutineScope true
                    is TransferOutcome.Failed -> return@coroutineScope false
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } finally {
            ticker.cancel()
            senders.forEach { it.cancel() }
        }
    }

    private suspend fun reportProgress() {
        val total = progressLock.withLock {
            val total = committed + inflight.values.sum()
            if (inflight.isNotEmpty()) repository.setBytesDone(taskId, total)
            total
        }
        onProgress(total)
    }

    private suspend fun send(request: ChunkRequest): Sent = try {
        val builder = Request.Builder().url(request.url).method(request.method, ChunkBody(request))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        http.newCall(builder.build()).await().use { response ->
            Sent.Response(request.index, ChunkResponse(response.code, response.header("ETag"), response.body.string()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SourceReadException) {
        Sent.SourceError(request.index, e.message ?: "source unreadable")
    } catch (e: IOException) {
        Sent.NetworkError(request.index, e.message ?: e.javaClass.simpleName)
    } catch (e: Exception) {
        Sent.Crash(request.index, e)
    }

    /**
     * 流式请求体：边读源文件区间（必要时加密）边写入连接，Content-Length = 请求描述的长度。
     * 写连接的 IOException 原样抛出（网络错误）；读源失败包装成 [SourceReadException]。
     * OkHttp 重试时会再次调用 [writeTo]，每次从头重新读取。
     */
    private inner class ChunkBody(private val request: ChunkRequest) : RequestBody() {
        // Content-Type 由请求描述的头给出（null 时 OkHttp 不覆盖该头）
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = request.body.contentLength

        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            var sinkError: IOException? = null
            try {
                runBlocking {
                    materializer.stream(request) { bytes ->
                        try {
                            sink.write(bytes)
                        } catch (e: IOException) {
                            sinkError = e
                            throw e
                        }
                        written += bytes.size
                        inflight[request.index] = written
                    }
                }
            } catch (e: Exception) {
                sinkError?.let { throw it }
                throw SourceReadException(e)
            }
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
