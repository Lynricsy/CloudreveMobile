package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CrInstant

/** 登录令牌对（`/session/token`、`/session/token/refresh` 返回） */
@Serializable
data class Token(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("access_expires") val accessExpires: CrInstant,
    @SerialName("refresh_expires") val refreshExpires: CrInstant,
)
