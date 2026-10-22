package plus.ling.opencloudreve.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import plus.ling.opencloudreve.core.model.FileItem
import java.io.FileNotFoundException

class DocumentIdTest {
    private val account = "1@cloud.example.com"

    @Test
    fun documentIdSurvivesParentRenameAndMove() {
        val before = FileItem(id = "lpUx", name = "report.txt", path = "cloudreve://my/A/report.txt")
        val afterParentRename = before.copy(path = "cloudreve://my/A2/report.txt")
        val afterMove = before.copy(path = "cloudreve://my/B/C/report.txt")

        val id = DocumentId.of(account, before).encoded
        assertEquals("1@cloud.example.com|id:lpUx", id)
        assertEquals(id, DocumentId.of(account, afterParentRename).encoded)
        assertEquals(id, DocumentId.of(account, afterMove).encoded)
        assertEquals(DocumentId.File(account, "lpUx"), DocumentId.parse(id))
    }

    @Test
    fun legacyPathIdsStillParse() {
        val legacy = DocumentId.parse("1@cloud.example.com|cloudreve://my/A/report.txt")
        assertTrue(legacy is DocumentId.Path)
        assertEquals(listOf("A", "report.txt"), (legacy as DocumentId.Path).uri.segments)
        assertEquals(DocumentId.Root(account), DocumentId.parse("1@cloud.example.com|cloudreve://my"))
        // 根对象（列表的 parent）映射到根 ID，而不是按它的文件 ID
        assertEquals(DocumentId.Root(account), DocumentId.of(account, FileItem(id = "root1", path = "cloudreve://my")))
    }

    @Test
    fun encodedSeparatorCannotEscapeGrantedFolder() {
        val allowed = DocumentId.parse("1@cloud.example.com|cloudreve://my/allowed") as DocumentId.Path
        for (raw in listOf("..%2Fsecret", "..%2fsecret", "%2e%2e%2Fsecret", "%2E%2E/secret")) {
            val probe = DocumentId.parse("1@cloud.example.com|cloudreve://my/allowed/$raw") as DocumentId.Path
            assertEquals(raw, listOf("secret"), probe.uri.segments)
            assertFalse(raw, DocumentId.isAncestor(allowed.uri, probe.uri))
        }
        val inside = DocumentId.parse("1@cloud.example.com|cloudreve://my/allowed/sub%2Fx") as DocumentId.Path
        assertTrue(DocumentId.isAncestor(allowed.uri, inside.uri))
    }

    @Test
    fun foreignFileSystemsAndMalformedIdsAreNotFound() {
        for (bad in listOf(
            "1@cloud.example.com|cloudreve://abc@my/x",
            "1@cloud.example.com|cloudreve://trash/x",
            "1@cloud.example.com|cloudreve://S1@share/x",
            "1@cloud.example.com|cloudreve://my/x?name=y",
            "1@cloud.example.com|id:../x",
            "1@cloud.example.com|id:",
            "|cloudreve://my/x",
            "no-separator",
        )) {
            assertThrows(bad, FileNotFoundException::class.java) { DocumentId.parse(bad) }
        }
    }
}
