package plus.ling.opencloudreve

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import plus.ling.opencloudreve.core.presentation.share.ShareLinkParser
import plus.ling.opencloudreve.ui.theme.OpenCloudreveTheme
import plus.ling.opencloudreve.ui.theme.ThemePreferences
import plus.ling.opencloudreve.ui.transfer.ShareReceiverScreen

/**
 * 系统分享菜单“上传到 OpenCloudreve”（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`，任意类型）。
 * - 带文件流：先复制到应用私有收件箱（分享来的临时授权随本 Activity 结束失效），选账户与目标目录后入队上传；
 * - 纯文本且是分享链接（`https://…/s/<id>`、`cloudreve://…@share`）：交给主界面的“打开分享链接”。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val streams = streamsOf(intent)
        if (streams.isEmpty()) {
            val link = sharedText(intent)?.let(::findShareLink)
            if (link != null) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_SHARE_LINK)
                        .putExtra(MainActivity.EXTRA_LINK, link)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            } else {
                Toast.makeText(this, R.string.share_receiver_unsupported, Toast.LENGTH_SHORT).show()
            }
            finish()
            return
        }
        setContent {
            val themePrefs: ThemePreferences = koinInject()
            val theme by themePrefs.settings.collectAsStateWithLifecycle()
            OpenCloudreveTheme(settings = theme) {
                ShareReceiverScreen(
                    uris = streams,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onFinished = ::finish,
                )
            }
        }
    }

    private fun streamsOf(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
        }
        // 部分应用只放在 ClipData 里
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add)
        }
        return out.toList()
    }

    private fun sharedText(intent: Intent): String? =
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    /** 在分享文本中找第一个分享链接（只认 URL 形式，避免把普通单词当成分享 id） */
    private fun findShareLink(text: String): String? =
        LINK_PATTERN.findAll(text).map { it.value.trimEnd('.', ',', ')', '。', '，') }
            .firstOrNull { ShareLinkParser.parse(it) != null }

    private companion object {
        val LINK_PATTERN = Regex("""(?i)(https?://|cloudreve://|opencloudreve://)\S+""")
    }
}
