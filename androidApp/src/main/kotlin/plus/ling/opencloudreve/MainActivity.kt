package plus.ling.opencloudreve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import plus.ling.opencloudreve.ui.navigation.AppNavHost
import plus.ling.opencloudreve.ui.theme.OpenCloudreveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenCloudreveTheme {
                AppNavHost()
            }
        }
    }
}
