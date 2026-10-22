package plus.ling.opencloudreve.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import plus.ling.opencloudreve.core.model.CrUri
import java.net.URI

/**
 * 播放器（Media3 HlsPlaylistParser）按 RFC 3986 用播放列表地址解析其中的相对地址；
 * 这里用 JDK 的同类解析模拟“入口 → 子播放列表 → 分片”的逐级解析，断言最终签名的是哪个云盘文件。
 */
class HlsMaskTest {
    private val linked = CrUri.parse("cloudreve://my/videos/show/index.m3u8")

    private fun resolve(playlist: String, reference: String): String = URI(playlist).resolve(reference).toString()

    /** 播放列表地址 [playlist] 中的引用 [reference] 最终对应的云盘 URI（字符串） */
    private fun cloudOf(playlist: String, reference: String, file: CrUri = linked): String? =
        HlsMask.cloudUri(resolve(playlist, reference), file)?.toString()

    @Test
    fun relativeSegmentResolvesToSiblingCloudFile() {
        val entry = HlsMask.entry(linked)
        assertEquals("cloudreve://my/videos/show/seg001.ts", cloudOf(entry, "seg001.ts"))
    }

    @Test
    fun nestedPlaylistSegmentsResolveRelativeToTheNestedPlaylist() {
        val sub = resolve(HlsMask.entry(linked), "720p/stream.m3u8")
        assertEquals("cloudreve://my/videos/show/720p/stream.m3u8", HlsMask.cloudUri(sub, linked).toString())
        assertEquals("cloudreve://my/videos/show/720p/seg_0.ts", cloudOf(sub, "seg_0.ts"))
        assertEquals("cloudreve://my/videos/show/shared/key.bin", cloudOf(sub, "../shared/key.bin"))
    }

    @Test
    fun rootRelativeReferenceResolvesFromFileSystemRoot() {
        assertEquals("cloudreve://my/media/a.ts", cloudOf(HlsMask.entry(linked), "/media/a.ts"))
    }

    @Test
    fun absoluteExternalUrlIsLeftUntouched() {
        val resolved = resolve(HlsMask.entry(linked), "https://cdn.example.com/v/seg1.ts")
        assertEquals("https://cdn.example.com/v/seg1.ts", resolved)
        assertFalse(HlsMask.isMasked(resolved))
        assertNull(HlsMask.cloudUri(resolved, linked))
    }

    @Test
    fun namesWithSpacesAndUnicodeRoundTrip() {
        val file = CrUri.parse("cloudreve://my").join("我的 视频", "第 1 集.m3u8")
        assertEquals(
            CrUri.parse("cloudreve://my").join("我的 视频", "片段 01+a.ts").toString(),
            cloudOf(HlsMask.entry(file), "%E7%89%87%E6%AE%B5%2001%2Ba.ts", file),
        )
        assertEquals(file.toString(), HlsMask.cloudUri(HlsMask.entry(file), file).toString())
    }

    @Test
    fun shareFileSystemAndPasswordArePreserved() {
        val shared = CrUri.parse("cloudreve://abc:pw@share/dir/index.m3u8")
        assertEquals("cloudreve://abc:pw@share/dir/seg.ts", cloudOf(HlsMask.entry(shared), "seg.ts", shared))
    }

    @Test
    fun queryAndFragmentAreIgnored() {
        assertEquals("cloudreve://my/videos/show/seg2.ts", cloudOf(HlsMask.entry(linked), "seg2.ts?_t=1#x"))
    }

    @Test
    fun encodedDotSegmentsAndSlashesAreRejected() {
        val entry = HlsMask.entry(linked)
        assertNull(cloudOf(entry, "%2E%2E/secret.ts"))
        assertNull(cloudOf(entry, "..%2Fsecret.ts"))
    }
}
