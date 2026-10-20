package plus.ling.opencloudreve

import android.app.Application
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.di.startAndroid

class OpenCloudreveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 共享层依赖图（数据库、会话、仓库、ViewModel 工厂）
        CoreGraph.startAndroid(this, BuildConfig.VERSION_NAME)
    }
}
