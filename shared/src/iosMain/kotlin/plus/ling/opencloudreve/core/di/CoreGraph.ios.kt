package plus.ling.opencloudreve.core.di

import plus.ling.opencloudreve.core.platform.IosPlatformContext

/**
 * iOS 启动入口（主 App 与扩展各自在启动时调用）：
 * `CoreGraph.shared.startIos(appGroupId: "group.plus.ling.opencloudreve", keychainAccessGroup: "<TeamID>.plus.ling.opencloudreve.shared", appVersion: ...)`
 */
fun CoreGraph.startIos(appGroupId: String, keychainAccessGroup: String?, appVersion: String) {
    start(IosPlatformContext(appGroupId, keychainAccessGroup, appVersion))
}
