package plus.ling.opencloudreve.ui.viewer

/**
 * 播放队列项的 mediaId：账户 + 链接目标 URI + 版本实体。
 * 播放服务在页面关闭后仍持有队列；只按路径比较会把另一账户（或同文件另一版本）的同路径队列当成同一队列，
 * 继续用旧账户/旧版本的地址播放。
 */
internal fun musicMediaId(accountId: String, linkedUri: String, version: String?): String =
    "$accountId\n$linkedUri\n${version.orEmpty()}"
