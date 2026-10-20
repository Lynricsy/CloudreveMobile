package plus.ling.opencloudreve.core.api

/** 按账户取得已鉴权的 [ApiClient]（由 auth.ApiClientRegistry 实现） */
interface ApiClientProvider {
    /** 账户不存在或已登出时抛 [SessionExpiredException] */
    @Throws(SessionExpiredException::class)
    fun client(accountId: String): ApiClient
}
