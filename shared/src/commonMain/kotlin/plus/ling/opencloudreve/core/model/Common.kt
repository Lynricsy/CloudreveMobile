package plus.ling.opencloudreve.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 列表分页（`inventory/common.go:PaginationResults`）。
 *
 * - 游标模式：[isCursor] 为 true，下一页把 [nextToken] 作为请求参数 `next_page_token` 回传；为 null 表示没有下一页。
 * - 页码模式：有 [totalItems]，请求参数 `page` 从 0 起（管理端列表请求从 1 起、响应从 0 起）。
 */
@Serializable
data class Pagination(
    val page: Int = 0,
    @SerialName("page_size") val pageSize: Int = 0,
    @SerialName("total_items") val totalItems: Int? = null,
    @SerialName("next_token") val nextToken: String? = null,
    @SerialName("is_cursor") val isCursor: Boolean = false,
) {
    /** 是否还有下一页（游标模式看 [nextToken]；页码模式按总数计算） */
    val hasMore: Boolean
        get() = when {
            !nextToken.isNullOrEmpty() -> true
            isCursor -> false
            totalItems != null && pageSize > 0 -> (page + 1) * pageSize < totalItems
            else -> false
        }
}

/** 仅含 `uris` 的请求体（`PUT /file/source`、`POST /file/restore` 等） */
@Serializable
data class UrisRequest(val uris: List<String>)

/** 仅含整数 `ids` 的请求体（管理端批量删除） */
@Serializable
data class IdsRequest(val ids: List<Int>)

/** 仅含字符串 `ids` 的请求体（批量删除分享） */
@Serializable
data class StringIdsRequest(val ids: List<String>)

/** 排序方向 */
object OrderDirection {
    const val ASC = "asc"
    const val DESC = "desc"
}
