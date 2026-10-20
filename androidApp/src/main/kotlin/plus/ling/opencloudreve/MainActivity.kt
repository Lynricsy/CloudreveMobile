package plus.ling.opencloudreve

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import plus.ling.opencloudreve.ui.main.MainRequest
import plus.ling.opencloudreve.ui.main.MainRequests
import plus.ling.opencloudreve.ui.navigation.AppNavHost
import plus.ling.opencloudreve.ui.theme.OpenCloudreveTheme
import plus.ling.opencloudreve.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 重建（旋转等）时不重复处理启动 Intent
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            // 配色偏好变化（设置页切换主题色）立即重组生效
            val themePrefs: ThemePreferences = koinInject()
            val theme by themePrefs.settings.collectAsStateWithLifecycle()
            OpenCloudreveTheme(settings = theme) {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 通知点击（打开传输列表）与系统分享菜单转来的分享链接 */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_TASKS -> MainRequests.post(MainRequest.OpenTasks)
            ACTION_OPEN_SHARE_LINK -> intent.getStringExtra(EXTRA_LINK)?.let { MainRequests.post(MainRequest.OpenShareLink(it)) }
        }
    }

    companion object {
        const val ACTION_OPEN_TASKS = "plus.ling.opencloudreve.action.OPEN_TASKS"
        const val ACTION_OPEN_SHARE_LINK = "plus.ling.opencloudreve.action.OPEN_SHARE_LINK"
        const val EXTRA_LINK = "link"
    }
}
