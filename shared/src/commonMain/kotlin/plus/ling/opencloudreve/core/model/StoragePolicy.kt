package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 存储策略（`BuildStoragePolicy` 输出）。出现在目录列表、上传会话、文件信息、`/user/setting/policies` 中。
 * 上传会话里的是服务端最终解析出的真实策略（永远不是负载均衡策略）。
 */
@Serializable
data class StoragePolicy(
    val id: String,
    val name: String = "",
    /** local/remote/oss/qiniu/upyun/onedrive/cos/s3/ks3/obs/load_balance 等 */
    val type: String = "",
    /** 0 表示不限 */
    @SerialName("max_size") val maxSize: Long = 0,
    @SerialName("allowed_suffix") val allowedSuffix: List<String>? = null,
    @SerialName("denied_suffix") val deniedSuffix: List<String>? = null,
    @SerialName("allowed_name_regexp") val allowedNameRegexp: String? = null,
    @SerialName("denied_name_regexp") val deniedNameRegexp: String? = null,
    /** 为 true 时客户端一律按本机中转上传 */
    val relay: Boolean = false,
    @SerialName("chunk_concurrency") val chunkConcurrency: Int? = null,
    val encryption: Boolean = false,
    /** 仅负载均衡父策略（目录列表、策略列表）带子策略 */
    val children: List<StoragePolicy>? = null,
    val weight: Int? = null,
) {
    companion object {
        const val TYPE_LOCAL = "local"
        const val TYPE_REMOTE = "remote"
        const val TYPE_ONEDRIVE = "onedrive"
        const val TYPE_S3 = "s3"
        const val TYPE_COS = "cos"
        const val TYPE_KS3 = "ks3"
        const val TYPE_OBS = "obs"
        const val TYPE_OSS = "oss"
        const val TYPE_QINIU = "qiniu"
        const val TYPE_UPYUN = "upyun"
        const val TYPE_LOAD_BALANCE = "load_balance"
    }
}
