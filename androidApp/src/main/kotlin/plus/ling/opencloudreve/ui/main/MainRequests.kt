package plus.ling.opencloudreve.ui.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外部入口（通知、系统分享菜单、上传结果提示）请求主界面执行的导航 */
sealed interface MainRequest {
    /** 切到“任务”Tab 的传输列表 */
    data object OpenTasks : MainRequest

    /** 在“文件”Tab 打开分享链接 */
    data class OpenShareLink(val link: String) : MainRequest
}

/** 待处理的主界面请求（单槽：新请求覆盖未处理的旧请求）；[MainScreen] 处理后 [consume] */
object MainRequests {
    private val _pending = MutableStateFlow<MainRequest?>(null)
    val pending: StateFlow<MainRequest?> = _pending.asStateFlow()

    fun post(request: MainRequest) {
        _pending.value = request
    }

    fun consume(request: MainRequest) {
        _pending.compareAndSet(request, null)
    }
}
