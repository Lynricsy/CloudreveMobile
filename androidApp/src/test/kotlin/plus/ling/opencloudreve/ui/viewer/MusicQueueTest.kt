package plus.ling.opencloudreve.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 页面据 mediaId 列表判断服务里已有的队列能否复用（相等则不重新 setMediaItems） */
class MusicQueueTest {
    private val paths = listOf("cloudreve://my/music/a.mp3", "cloudreve://my/music/song.mp3")

    private fun queue(accountId: String, version: String?, uris: List<String> = paths) =
        uris.map { musicMediaId(accountId, it, version) }

    @Test
    fun samePathQueueOfAnotherAccountIsNotReused() {
        assertNotEquals(queue("1@a.example", null), queue("2@b.example", null))
    }

    @Test
    fun historicVersionOfSingleTrackIsNotTheCurrentVersion() {
        val single = listOf("cloudreve://my/music/song.mp3")
        assertNotEquals(queue("1@a.example", null, single), queue("1@a.example", "entity-old", single))
    }

    @Test
    fun identicalQueueOfSameAccountAndVersionIsReused() {
        assertEquals(queue("1@a.example", null), queue("1@a.example", null))
    }
}
