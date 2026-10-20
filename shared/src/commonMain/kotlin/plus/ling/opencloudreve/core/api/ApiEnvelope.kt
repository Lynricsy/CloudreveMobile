package plus.ling.opencloudreve.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 服务端统一响应信封（HTTP 恒为 200，靠 [code] 区分结果）。
 * `aggregated_error` 的每个值也是完整信封（40081 批量部分失败）。
 */
@Serializable
data class ApiEnvelope(
    val code: Int,
    val msg: String = "",
    val data: JsonElement? = null,
    @SerialName("aggregated_error") val aggregatedError: Map<String, ApiEnvelope>? = null,
    val error: String? = null,
    @SerialName("correlation_id") val correlationId: String? = null,
)
