package plus.ling.opencloudreve.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** 全局 JSON 配置：忽略未知字段、空值不输出、默认值照常输出 */
val CloudreveJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/** Go `time.Time` 的 RFC3339（可带小数秒与时区偏移）<-> [Instant] */
object Rfc3339InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("plus.ling.opencloudreve.Rfc3339Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** 在模型中使用：`val createdAt: CrInstant` */
typealias CrInstant = @kotlinx.serialization.Serializable(with = Rfc3339InstantSerializer::class) Instant
