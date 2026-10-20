package plus.ling.opencloudreve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import plus.ling.opencloudreve.ui.navigation.AppNavHost
import plus.ling.opencloudreve.ui.theme.OpenCloudreveTheme
import plus.ling.opencloudreve.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // 配色偏好变化（设置页切换主题色）立即重组生效
            val themePrefs: ThemePreferences = koinInject()
            val theme by themePrefs.settings.collectAsStateWithLifecycle()
            OpenCloudreveTheme(settings = theme) {
                AppNavHost()
            }
        }
    }
}
