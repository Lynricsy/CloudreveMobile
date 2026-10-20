package plus.ling.opencloudreve.core.presentation.handoff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import plus.ling.opencloudreve.core.api.CloudreveException
import plus.ling.opencloudreve.core.api.SessionExpiredException
import plus.ling.opencloudreve.core.model.UriCodec
import plus.ling.opencloudreve.core.presentation.ErrorKind
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.toUiError
import plus.ling.opencloudreve.core.presentation.auth.ServerVersion

/** 网页会话交接是否可用（服务端 ≥ [ServerVersion.ENHANCED_MIN]） */
enum class HandoffAvailability {
    /** 尚未确认服务端版本 */
    Checking,
    Available,

    /** 服务端版本过低：隐藏“在网页中打开”或提示“需要服务端 ≥ 4.19.11” */
    Unsupported,
}

/** 服务端版本低于 [ServerVersion.ENHANCED_MIN]，不支持会话交接 */
class HandoffUnsupportedException(val serverVersion: String) :
    CloudreveException("Web handoff requires server >= ${ServerVersion.ENHANCED_MIN}, got $serverVersion")

/** 管理后台网页路径（`/admin/<section>`） */
enum class AdminWebSection(val path: String) {
    Home("home"),
    Users("user"),
    Shares("share"),
    Files("file"),
    Tasks("task"),
    Nodes("node"),
}

/**
 * App → 网页免登录交接（`POST /session/handoff` 取 60 秒一次性 code，再打开
 * `<站点>/session/handoff?code=&redirect=`，网页端兑换 token 后跳到 `redirect`）。
 *
 * 交接码一次性且很快过期：每次打开网页都要重新调用 [url]，不要缓存结果。
 * 服务端版本按站点缓存在内存中（`GET /site/ping`）。
 */
class WebHandoff internal constructor(
    private val siteUrlOf: (accountId: String) -> String?,
    private val ping: suspend (siteUrl: String) -> String,
    private val createCode: suspend (accountId: String) -> String,
) {
    private val mutex = Mutex()
    private val versions = HashMap<String, String>()

    /** 账户所在站点的服务端版本（按站点缓存） */
    @Throws(Exception::class)
    suspend fun serverVersion(accountId: String, forceRefresh: Boolean = false): String {
        val site = siteUrl(accountId)
        if (!forceRefresh) mutex.withLock { versions[site] }?.let { return it }
        val v = ping(site)
        mutex.withLock { versions[site] = v }
        return v
    }

    /** 交接是否可用 */
    @Throws(Exception::class)
    suspend fun availability(accountId: String): HandoffAvailability =
        if (ServerVersion.isAtLeast(serverVersion(accountId), ServerVersion.ENHANCED_MIN)) {
            HandoffAvailability.Available
        } else {
            HandoffAvailability.Unsupported
        }

    /**
     * 生成一次性交接地址，网页端登录后跳到 [redirect]（站内路径，以 `/` 开头）。
     * 服务端版本过低抛 [HandoffUnsupportedException]。
     */
    @Throws(Exception::class)
    suspend fun url(accountId: String, redirect: String): String {
        val site = siteUrl(accountId)
        val version = serverVersion(accountId)
        if (!ServerVersion.isAtLeast(version, ServerVersion.ENHANCED_MIN)) throw HandoffUnsupportedException(version)
        return buildUrl(site, createCode(accountId), redirect)
    }

    private fun siteUrl(accountId: String): String = siteUrlOf(accountId) ?: throw SessionExpiredException(accountId)

    companion object {
        /** 网页端对 `redirect` 的校验：必须以 `/` 开头且不以 `//` 开头，否则回到 `/home` */
        fun safeRedirect(redirect: String): String =
            if (redirect.startsWith("/") && !redirect.startsWith("//")) redirect else "/home"

        /** `<站点>/session/handoff?code=<code>&redirect=<encodeURIComponent(redirect)>` */
        fun buildUrl(siteUrl: String, code: String, redirect: String): String =
            siteUrl.trimEnd('/') + "/session/handoff?code=" + UriCodec.encodeComponent(code) +
                "&redirect=" + UriCodec.encodeComponent(safeRedirect(redirect))

        /** 文件管理器打开某目录：`/home?path=<uri>` */
        fun homeRedirect(directoryUri: String): String = "/home?path=" + UriCodec.encodeComponent(directoryUri)

        /**
         * 在网页文件管理器中用指定预览器打开文件（前端 `checkOpenViewerQuery`）：
         * `/home?path=<目录 uri>&open=<文件 id>&viewer=<viewer id>[&version=<实体 id>]`。
         * [fileId] 也可以是文件完整 path（前端按 id 或 path 匹配当前目录列表）。
         */
        fun viewerRedirect(directoryUri: String, fileId: String, viewerId: String, version: String? = null): String =
            homeRedirect(directoryUri) + "&open=" + UriCodec.encodeComponent(fileId) +
                "&viewer=" + UriCodec.encodeComponent(viewerId) +
                (version?.takeIf { it.isNotEmpty() }?.let { "&version=" + UriCodec.encodeComponent(it) } ?: "")

        /** 管理后台页面：`/admin/<section>[/<id>]`（只有节点有独立详情页） */
        fun adminRedirect(section: AdminWebSection, id: Int? = null): String =
            "/admin/" + section.path + (if (id != null && section == AdminWebSection.Nodes) "/$id" else "")
    }
}

/**
 * 一次交接尝试的结果（给 VM 用）：成功时 [url] 非空；[availability] 为确认到的可用性（未知时 null）；
 * 失败时 [error] 非空，版本过低映射为 [ErrorKind.FeatureDisabled]（`serverMsg` 为服务端版本）。
 */
internal data class HandoffAttempt(val url: String?, val availability: HandoffAvailability?, val error: UiError?)

internal suspend fun WebHandoff.attempt(accountId: String, redirect: String): HandoffAttempt = try {
    HandoffAttempt(url(accountId, redirect), HandoffAvailability.Available, null)
} catch (e: CancellationException) {
    throw e
} catch (e: HandoffUnsupportedException) {
    HandoffAttempt(null, HandoffAvailability.Unsupported, UiError(ErrorKind.FeatureDisabled, serverMsg = e.serverVersion))
} catch (e: Exception) {
    HandoffAttempt(null, null, e.toUiError())
}

/** 查询可用性；网络等失败返回 null（调用方保持原值，打开网页时再判定） */
internal suspend fun WebHandoff.availabilityOrNull(accountId: String): HandoffAvailability? = try {
    availability(accountId)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
