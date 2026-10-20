package plus.ling.opencloudreve.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import plus.ling.opencloudreve.BuildConfig
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.ui.common.openCustomTab

/** 源代码仓库 */
private const val SOURCE_URL = "https://github.com/Lynricsy/CloudreveMobile"

/**
 * 关于：应用版本、服务端版本（`/site/ping`）与站点、源代码、开源许可。
 */
@Composable
fun AboutScreen(
    accountId: String,
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    handoff: WebHandoff = koinInject(),
    accounts: AccountRepository = koinInject(),
) {
    val context = LocalContext.current
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var versionFailed by remember { mutableStateOf(false) }
    LaunchedEffect(accountId) {
        try {
            serverVersion = handoff.serverVersion(accountId, forceRefresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            versionFailed = true
        }
    }
    val siteUrl = remember(accountId) { accounts.account(accountId)?.siteUrl.orEmpty() }

    Scaffold(topBar = { SmallTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 自适应图标不能直接作为 painter，使用其前景矢量图
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).background(colorResource(R.color.ic_launcher_background), CircleShape),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingRow(
                title = stringResource(R.string.about_app_version),
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                icon = Icons.Outlined.PhoneAndroid,
            )
            SettingRow(
                title = stringResource(R.string.about_server_version),
                subtitle = when {
                    serverVersion != null -> "$serverVersion · $siteUrl"
                    versionFailed -> stringResource(R.string.about_server_unknown)
                    else -> stringResource(R.string.about_loading)
                },
                icon = Icons.Outlined.Dns,
            )
            SettingRow(
                title = stringResource(R.string.about_source),
                subtitle = SOURCE_URL,
                icon = Icons.Outlined.Code,
                onClick = { openCustomTab(context, SOURCE_URL) },
            )
            SettingRow(
                title = stringResource(R.string.about_licenses),
                icon = Icons.Outlined.Description,
                onClick = onLicenses,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
    }
}

/** 第三方开源组件 */
private data class OssLibrary(val name: String, val license: String, val url: String)

private val APACHE = "Apache License 2.0"

private val LIBRARIES = listOf(
    OssLibrary("Kotlin / kotlinx.coroutines / kotlinx.serialization", APACHE, "https://github.com/JetBrains/kotlin"),
    OssLibrary("Jetpack Compose / Material 3", APACHE, "https://developer.android.com/jetpack/androidx"),
    OssLibrary("AndroidX (Navigation, Lifecycle, Credentials, WorkManager, Browser, WebKit)", APACHE, "https://github.com/androidx/androidx"),
    OssLibrary("Media3 ExoPlayer", APACHE, "https://github.com/androidx/media"),
    OssLibrary("Ktor", APACHE, "https://github.com/ktorio/ktor"),
    OssLibrary("OkHttp / Okio", APACHE, "https://github.com/square/okhttp"),
    OssLibrary("SQLDelight", APACHE, "https://github.com/sqldelight/sqldelight"),
    OssLibrary("Koin", APACHE, "https://github.com/InsertKoinIO/koin"),
    OssLibrary("Coil", APACHE, "https://github.com/coil-kt/coil"),
    OssLibrary("ZXing", APACHE, "https://github.com/zxing/zxing"),
    OssLibrary("Multiplatform Markdown Renderer", APACHE, "https://github.com/mikepenz/multiplatform-markdown-renderer"),
    OssLibrary("MaterialKolor material-color-utilities", APACHE, "https://github.com/jordond/MaterialKolor"),
    OssLibrary("KotlinCrypto hash", APACHE, "https://github.com/KotlinCrypto/hash"),
    OssLibrary("SKIE", APACHE, "https://github.com/touchlab/SKIE"),
    OssLibrary("Cloudreve", "GPL-3.0", "https://github.com/cloudreve/cloudreve"),
)

/** 开源许可列表（点按打开项目主页） */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { SmallTopBar(stringResource(R.string.about_licenses), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)) {
            items(LIBRARIES, key = { it.name }) { lib ->
                SettingRow(title = lib.name, subtitle = lib.license, onClick = { openCustomTab(context, lib.url) })
            }
        }
    }
}
