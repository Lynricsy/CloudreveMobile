package plus.ling.opencloudreve.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import plus.ling.opencloudreve.core.transfer.TransferRepository
import plus.ling.opencloudreve.core.transfer.TransferState
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 传输执行器的进程级依赖：独立 OkHttp 客户端与后台协程作用域 */
internal object TransferRuntime {
    /** 不随界面销毁的后台作用域（调度 Work、清理临时文件、入队下载） */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 字节传输专用客户端：分片上传后服务端可能要合并/转存较久，读超时放宽；
     * 第三方直链的重定向由 OkHttp 默认跟随。
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 当前是否没有可用网络（网络错误发生时据此区分“断网等待”与“请求失败”） */
    fun isOffline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** 请求体读取本地源失败（非网络原因）：包装成 IOException 以免在 OkHttp 线程上抛出未捕获异常 */
internal class SourceReadException(cause: Throwable) : IOException(cause.message ?: cause.javaClass.simpleName, cause)

/** 断网：Work 以 retry 结束，等网络约束满足后由 WorkManager 重新执行 */
internal class NetworkLostException : Exception("network unavailable")

/**
 * Work 因网络约束不满足被系统停止时，把仍标记为进行中的任务退回排队（界面显示“排队中”而非停住的进度），
 * 网络恢复后 WorkManager 自动重跑并从已完成处继续。暂停/取消导致的停止不处理（状态已由 TransferManager 写入）。
 */
internal suspend fun requeueIfNetworkStop(worker: ListenableWorker, repository: TransferRepository, taskId: String) {
    // API 31 以下没有停止原因：以当前是否断网判断
    val networkStop = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        worker.stopReason == WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY
    } else {
        TransferRuntime.isOffline(worker.applicationContext)
    }
    if (!networkStop) return
    withContext(NonCancellable) {
        val state = repository.task(taskId)?.state
        if (state == TransferState.PREPARING || state == TransferState.RUNNING || state == TransferState.FINISHING) {
            repository.setState(taskId, TransferState.QUEUED)
        }
    }
}

/** 可取消地执行 OkHttp 请求：协程取消时同时取消请求 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
