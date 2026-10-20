package plus.ling.opencloudreve.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrUriTest {
    @Test
    fun rootsAndFactories() {
        assertEquals("cloudreve://my", CrUri.my().toString())
        assertEquals("cloudreve://abc@my", CrUri.my("abc").toString())
        assertEquals("cloudreve://trash", CrUri.trash().toString())
        assertEquals("cloudreve://shared_with_me", CrUri.sharedWithMe().toString())
        assertEquals("cloudreve://Xy9@share", CrUri.share("Xy9").toString())
        assertEquals("cloudreve://Xy9:pw12@share", CrUri.share("Xy9", "pw12").toString())
        assertTrue(CrUri.my().isRoot())
        assertEquals("/", CrUri.my().path)
        assertEquals("", CrUri.my().name())
        assertNull(CrUri.my().parent())
    }

    @Test
    fun pathSegmentsUseEncodeUriComponentRules() {
        val u = CrUri.my().join("我的 文档", "a+b&c#d?e", "x/y", "-_.!~*'()")
        assertEquals(
            "cloudreve://my/%E6%88%91%E7%9A%84%20%E6%96%87%E6%A1%A3/a%2Bb%26c%23d%3Fe/x%2Fy/-_.!~*'()",
            u.toString(),
        )
        assertEquals(listOf("我的 文档", "a+b&c#d?e", "x/y", "-_.!~*'()"), u.segments)
        assertEquals("/我的 文档/a+b&c#d?e/x/y/-_.!~*'()", u.path)
        assertEquals("-_.!~*'()", u.name())
        // 往返
        assertEquals(u, CrUri.parse(u.toString()))
        assertEquals(u.toString(), CrUri.parse(u.toString()).toString())
    }

    @Test
    fun emojiRoundTrip() {
        val u = CrUri.my().join("😀.png")
        assertEquals("cloudreve://my/%F0%9F%98%80.png", u.toString())
        assertEquals("😀.png", CrUri.parse(u.toString()).name())
    }

    @Test
    fun parseNormalizes() {
        val u = CrUri.parse("cloudreve://MY/a//b/./c/../d/")
        assertEquals("my", u.fs)
        assertEquals(listOf("a", "b", "d"), u.segments)
        assertEquals("cloudreve://my/a/b/d", u.toString())
        assertEquals(CrUri.my().join("a", "b"), u.parent())
        assertEquals(CrUri.my(), u.root())
        assertFailsWith<IllegalArgumentException> { CrUri.parse("http://my/a") }
        assertNull(CrUri.parseOrNull("cloudreve://"))
    }

    @Test
    fun sharePasswordAndUserInfo() {
        val u = CrUri.parse("cloudreve://AbC:p%40ss@share/dir/file.txt")
        assertEquals("share", u.fs)
        assertEquals("AbC", u.id)
        assertEquals("p@ss", u.password)
        assertEquals(listOf("dir", "file.txt"), u.segments)
        assertEquals("cloudreve://AbC:p%40ss@share/dir/file.txt", u.toString())
        assertEquals("cloudreve://AbC@share/dir/file.txt", u.withPassword(null).toString())
        assertEquals(CrUri.share("AbC", "p@ss").join("dir"), u.parent())

        val noPw = CrUri.parse("cloudreve://AbC@share")
        assertEquals("AbC", noPw.id)
        assertNull(noPw.password)
    }

    @Test
    fun otherUserMy() {
        val u = CrUri.parse("cloudreve://lxEu@my/docs")
        assertEquals("my", u.fs)
        assertEquals("lxEu", u.id)
        assertNull(u.password)
        assertEquals(CrUri.my("lxEu").join("docs"), u)
    }

    @Test
    fun searchParamsRoundTrip() {
        val params = SearchParams(
            names = listOf("foo bar", "*.pdf"),
            nameOpOr = true,
            caseFolding = true,
            type = SearchFileType.FILE,
            metadata = mapOf("tag:工作" to ""),
            exactMetadata = mapOf("music:artist" to "A&B"),
            sizeGte = 1024,
            sizeLte = 2048,
            createdGte = 1700000000,
            updatedLte = 1800000000,
        )
        val u = CrUri.my().join("docs").withSearch(params)
        assertEquals(
            "cloudreve://my/docs?name=foo+bar&name=*.pdf&name_op_or=&case_folding=&type=file" +
                "&meta_tag%3A%E5%B7%A5%E4%BD%9C=&exact_meta_music%3Aartist=A%26B" +
                "&size_gte=1024&size_lte=2048&created_gte=1700000000&updated_lte=1800000000",
            u.toString(),
        )
        assertTrue(u.isSearch())
        val parsed = CrUri.parse(u.toString())
        assertEquals(params, parsed.searchParams())
        assertEquals(u, parsed)
        // join/parent 去掉搜索参数
        assertFalse(u.join("x").isSearch())
        assertFalse(u.parent()!!.isSearch())
        assertFalse(u.withoutSearch().isSearch())
    }

    @Test
    fun isSearchFollowsBackendRules() {
        // 仅修饰符不构成搜索
        assertFalse(CrUri.parse("cloudreve://my?case_folding&name_op_or").isSearch())
        assertNull(CrUri.parse("cloudreve://my?case_folding").searchParams())
        // name 出现即可（即使为空）
        assertTrue(CrUri.parse("cloudreve://my?name=").isSearch())
        // category 为空不算
        assertFalse(CrUri.parse("cloudreve://my?category=").isSearch())
        assertTrue(CrUri.parse("cloudreve://my?category=image").isSearch())
        // 非法数字不算
        assertFalse(CrUri.parse("cloudreve://my?size_gte=abc").isSearch())
        assertTrue(CrUri.parse("cloudreve://my?size_gte=0").isSearch())
        assertTrue(CrUri.parse("cloudreve://my?type=folder").isSearch())
        assertTrue(CrUri.parse("cloudreve://my?exact_meta_tag%3Aa=").isSearch())
        // 后端别名 use_or
        assertTrue(CrUri.parse("cloudreve://my?name=a&use_or").searchParams()!!.nameOpOr)
    }

    @Test
    fun trashSearchUsesRestoreUri() {
        val u = CrUri.trash().withSearch(SearchParams(names = listOf("报告", "v2"), caseFolding = true))
        // encodeURI("报告 v2") 再经表单编码
        assertEquals(
            "cloudreve://trash?meta_sys%3Arestore_uri=%25E6%258A%25A5%25E5%2591%258A%2520v2&case_folding=",
            u.toString(),
        )
        val p = CrUri.parse(u.toString()).searchParams()!!
        assertEquals(mapOf("sys:restore_uri" to "%E6%8A%A5%E5%91%8A%20v2"), p.metadata)
        assertTrue(p.names.isEmpty())
        assertTrue(p.caseFolding)
    }

    @Test
    fun queryAccessors() {
        val u = CrUri.parse("cloudreve://my?name=a&name=b&category=video")
        assertEquals(listOf("a", "b"), u.query("name"))
        assertEquals(listOf("name", "category"), u.queryKeys())
        assertEquals("video", u.searchParams()!!.category)
    }
}
