package plus.ling.opencloudreve.core.api

/**
 * 为 [ApiClient] 提供 access token。由 `auth.SessionStore` 按账户实现。
 * 实现必须保证刷新是 single-flight 的，并在每次刷新后保存新的 refresh token。
 */
interface AuthProvider {
    /** 当前可用的 access token；剩余有效期 < 10 分钟时先刷新。未登录或刷新失败返回 null */
    suspend fun validAccessToken(): String?

    /**
     * 服务端以 401 拒绝了 [rejectedToken] 后调用：若已有更新的 token 直接返回，否则强制刷新一次。
     * 刷新失败（40020/再次 401）时负责登出并返回 null。
     */
    suspend fun refreshAfterRejection(rejectedToken: String): String?
}
