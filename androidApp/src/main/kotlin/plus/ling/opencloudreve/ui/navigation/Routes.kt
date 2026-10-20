package plus.ling.opencloudreve.ui.navigation

import kotlinx.serialization.Serializable

/** 服务器地址页（首次启动或“添加账户”） */
@Serializable
data object ServerSetupRoute

/** 登录页；[email] 用于会话失效后的重新登录预填 */
@Serializable
data class LoginRoute(val siteUrl: String, val email: String? = null)

/** 主界面（文件/分享/任务/我的） */
@Serializable
data object MainRoute
