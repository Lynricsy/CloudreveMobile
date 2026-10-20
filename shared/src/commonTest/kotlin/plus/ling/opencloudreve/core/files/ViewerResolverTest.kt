package plus.ling.opencloudreve.core.files

import plus.ling.opencloudreve.core.model.BoolSet
import plus.ling.opencloudreve.core.model.FileItem
import plus.ling.opencloudreve.core.model.FileType
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.NewFileTemplate
import plus.ling.opencloudreve.core.model.SiteExplorer
import plus.ling.opencloudreve.core.model.Viewer
import plus.ling.opencloudreve.core.model.ViewerGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ViewerResolverTest {

    private val explorer = SiteExplorer(
        fileViewers = listOf(
            ViewerGroup(
                listOf(
                    Viewer("image", "builtin", exts = listOf("png", "JPG")),
                    Viewer("markdown", "builtin", exts = listOf("md"), templates = listOf(NewFileTemplate("md", "Markdown"))),
                    Viewer("monaco", "builtin", exts = listOf("md", "txt"), templates = listOf(NewFileTemplate("txt", "Text"), NewFileTemplate("md", "MD"))),
                    Viewer("archive", "builtin", exts = listOf("zip"), requiredGroupPermission = listOf(GroupPermission.ARCHIVE_TASK)),
                    Viewer("pc-only", "custom", exts = listOf("docx"), platform = "pc"),
                    Viewer("mobile-office", "custom", exts = listOf("docx"), platform = "mobile"),
                    Viewer("all-office", "wopi", exts = listOf("docx", "xlsx"), platform = "all"),
                    Viewer("off", "builtin", exts = listOf("png"), disabled = true),
                ),
            ),
            ViewerGroup(listOf(Viewer("pdf", "builtin", exts = listOf("pdf")))),
        ),
        defaultViewerMapping = mapOf("md" to "markdown", "xlsx" to "pc-only"),
    )

    @Test
    fun filtersDisabledPlatformAndPermission() {
        val noPerm = ViewerResolver(explorer, BoolSet.EMPTY)
        assertEquals(
            listOf("image", "markdown", "monaco", "mobile-office", "all-office", "pdf"),
            noPerm.viewers.map { it.id },
        )
        val withArchive = ViewerResolver(explorer, BoolSet.of(GroupPermission.ARCHIVE_TASK))
        assertEquals(listOf("archive"), withArchive.candidates("zip").map { it.id })
        assertEquals(emptyList(), noPerm.candidates("zip"))
        // pc 平台视角下 mobile 专属被过滤
        val pc = ViewerResolver(explorer, null, platform = ViewerResolver.PLATFORM_PC)
        assertEquals(listOf("pc-only", "all-office"), pc.candidates("docx").map { it.id })
    }

    @Test
    fun resolutionOrder() {
        val r = ViewerResolver(explorer, BoolSet.EMPTY)
        // 唯一候选（扩展名大小写不敏感）
        assertEquals("image", (r.resolve("jpg") as ViewerResolution.Open).viewer.id)
        // 多候选按默认映射
        assertEquals("markdown", (r.resolve("MD") as ViewerResolution.Open).viewer.id)
        // 用户偏好优先于默认映射
        assertEquals("monaco", (r.resolve("md", preferredViewerId = "monaco") as ViewerResolution.Open).viewer.id)
        // 偏好不在候选内时忽略
        assertEquals("markdown", (r.resolve("md", preferredViewerId = "pdf") as ViewerResolution.Open).viewer.id)
        // 多候选无映射 → 选择
        val choose = assertIs<ViewerResolution.Choose>(r.resolve("docx"))
        assertEquals(listOf("mobile-office", "all-office"), choose.candidates.map { it.id })
        // 默认映射指向被过滤的预览器 → 只剩一个候选直接打开
        assertEquals("all-office", (r.resolve("xlsx") as ViewerResolution.Open).viewer.id)
        assertIs<ViewerResolution.NoViewer>(r.resolve("exe"))
        assertIs<ViewerResolution.NoViewer>(r.resolve(FileItem(type = FileType.FOLDER, name = "a.png")))
    }

    @Test
    fun newFileTemplatesDedupedByExtension() {
        val r = ViewerResolver(explorer, BoolSet.EMPTY)
        assertEquals(
            listOf("markdown" to "md", "monaco" to "txt"),
            r.newFileOptions().map { it.viewer.id to it.template.ext },
        )
    }
}
