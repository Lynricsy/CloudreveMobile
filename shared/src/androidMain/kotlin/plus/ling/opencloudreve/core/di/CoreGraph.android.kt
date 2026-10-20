package plus.ling.opencloudreve.core.di

import android.content.Context
import org.koin.core.module.Module
import plus.ling.opencloudreve.core.platform.AndroidPlatformContext

/** Android 启动入口（在 `Application.onCreate` 调用）：`CoreGraph.startAndroid(this, BuildConfig.VERSION_NAME)` */
fun CoreGraph.startAndroid(context: Context, appVersion: String, extraModules: List<Module> = emptyList()) {
    start(AndroidPlatformContext(context, appVersion), extraModules)
}
