package plus.ling.opencloudreve.core.transfer

import kotlinx.coroutines.test.runTest
import plus.ling.opencloudreve.core.model.StoragePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadValidatorTest {
    private fun policy(
        maxSize: Long = 0,
        allowed: List<String>? = null,
        denied: List<String>? = null,
        allowedRe: String? = null,
        deniedRe: String? = null,
    ) = StoragePolicy(
        id = "p", type = StoragePolicy.TYPE_LOCAL, maxSize = maxSize, allowedSuffix = allowed, deniedSuffix = denied,
        allowedNameRegexp = allowedRe, deniedNameRegexp = deniedRe,
    )

    @Test
    fun sizeLimit() {
        assertNull(UploadValidator.validate("a.txt", 10, policy(maxSize = 10)))
        assertEquals(UploadRejection.TOO_LARGE, UploadValidator.validate("a.txt", 11, policy(maxSize = 10)))
        assertNull(UploadValidator.validate("a.txt", Long.MAX_VALUE, policy(maxSize = 0)), "0 表示不限")
    }

    @Test
    fun suffixListsFollowServerRules() {
        val allow = policy(allowed = listOf("jpg", "png"))
        assertNull(UploadValidator.validate("a.JPG", 1, allow), "服务端扩展名转小写")
        assertEquals(UploadRejection.SUFFIX_NOT_ALLOWED, UploadValidator.validate("a.gif", 1, allow))
        assertEquals(UploadRejection.SUFFIX_NOT_ALLOWED, UploadValidator.validate("noext", 1, allow))
        val deny = policy(denied = listOf("exe"))
        assertEquals(UploadRejection.SUFFIX_DENIED, UploadValidator.validate("x.tar.exe", 1, deny))
        assertNull(UploadValidator.validate("exe", 1, deny), "无扩展名不在禁止列表中")
    }

    @Test
    fun nameRegexps() {
        assertEquals(UploadRejection.NAME_NOT_ALLOWED, UploadValidator.validate("b.txt", 1, policy(allowedRe = "^a")))
        assertNull(UploadValidator.validate("a.txt", 1, policy(allowedRe = "^a")))
        assertEquals(UploadRejection.NAME_DENIED, UploadValidator.validate("tmp.txt", 1, policy(deniedRe = "tmp")))
        assertNull(UploadValidator.validate("x", 1, policy(allowedRe = "(")), "本地无法编译的正则交给服务端")
    }

    @Test
    fun illegalNames() {
        listOf("", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "x".repeat(256)).forEach {
            assertEquals(UploadRejection.ILLEGAL_NAME, UploadValidator.validate(it, 1, null), it)
        }
        assertNull(UploadValidator.validate("x".repeat(255), 1, null))
        assertEquals(UploadRejection.ILLEGAL_NAME, UploadValidator.validate("文".repeat(86), 1, null), "按 UTF-8 字节计长度")
    }

    @Test
    fun loadBalanceAcceptsIfAnyChildAccepts() {
        val lb = StoragePolicy(
            id = "lb", type = StoragePolicy.TYPE_LOAD_BALANCE,
            children = listOf(policy(maxSize = 5), policy(maxSize = 100)),
        )
        assertNull(UploadValidator.validate("a", 50, lb))
        assertEquals(UploadRejection.TOO_LARGE, UploadValidator.validate("a", 500, lb))
    }

    @Test
    fun managerStoresRejectedUploadsAsFailed() = runTest {
        val env = TransferEnv()
        env.fs.sources["s1"] = bytes(20)
        val items = env.manager.enqueueUploads(
            ACCOUNT,
            listOf(UploadSource("s1", "big.bin", 20), UploadSource("s1", "ok.bin", 20)),
            "cloudreve://my",
            policy(maxSize = 10, allowed = listOf("bin")),
        )
        assertEquals(TransferState.FAILED, items[0].state)
        assertEquals(TransferErrorCode.VALIDATION, items[0].errorCode)
        assertEquals(UploadRejection.TOO_LARGE.name, items[0].errorMessage)
        assertEquals(TransferState.FAILED, items[1].state)
        val accepted = env.manager.enqueueUploads(ACCOUNT, listOf(UploadSource("s1", "ok.bin", 5)), "cloudreve://my", policy(maxSize = 10))
        assertEquals(TransferState.QUEUED, accepted.single().state)
        assertEquals("cloudreve://my/ok.bin", accepted.single().targetUri)
        assertTrue(env.requests.isEmpty(), "预校验不发请求")
    }
}
