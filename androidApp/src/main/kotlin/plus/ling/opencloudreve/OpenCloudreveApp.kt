package plus.ling.opencloudreve

import android.app.Application
import org.koin.dsl.module
import plus.ling.opencloudreve.core.di.CoreGraph
import plus.ling.opencloudreve.core.di.startAndroid
import plus.ling.opencloudreve.transfer.AndroidTransferExecutor
import plus.ling.opencloudreve.transfer.TransferNotifications
import plus.ling.opencloudreve.ui.theme.ThemePreferences

class OpenCloudreveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 共享层依赖图（数据库、会话、仓库、ViewModel 工厂）+ 应用层单例
        CoreGraph.startAndroid(this, BuildConfig.VERSION_NAME, extraModules = listOf(appModule()))
        // 传输执行器（WorkManager）：安装后接管上次未完成的上传/下载
        TransferNotifications.ensureChannels(this)
        CoreGraph.installExecutor(AndroidTransferExecutor(this, CoreGraph.transferRepository()))
        CoreGraph.transferManager().reattach()
    }

    private fun appModule() = module {
        single { ThemePreferences(this@OpenCloudreveApp) }
    }
}
