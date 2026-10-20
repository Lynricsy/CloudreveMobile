package plus.ling.opencloudreve.core.model

import kotlinx.serialization.Serializable
import plus.ling.opencloudreve.core.api.CloudreveJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoolSetTest {
    @Test
    fun lowBitFirst() {
        // 0b0000_0101, 0b1000_0000 → 位 0、2、15
        val s = BoolSet(byteArrayOf(0x05, 0x80.toByte()))
        assertTrue(s.has(0))
        assertFalse(s.has(1))
        assertTrue(s.has(2))
        assertTrue(s.has(15))
        assertFalse(s.has(14))
        // 越界与负数
        assertFalse(s.has(16))
        assertFalse(s.has(1000))
        assertFalse(s.has(-1))
        assertEquals("BYA=", s.toBase64())
    }

    @Test
    fun base64RoundTripAndOf() {
        val s = BoolSet.of(NavigatorCapability.CREATE_FILE, NavigatorCapability.MODIFY_PROPS)
        assertEquals(s, BoolSet.fromBase64(s.toBase64()))
        assertTrue(s.has(24))
        assertFalse(s.with(24, false).has(24))
        assertFalse(BoolSet.fromBase64("").has(0))
        // 末尾 0 字节不影响相等性
        assertEquals(BoolSet(byteArrayOf(1)), BoolSet(byteArrayOf(1, 0)))
        assertEquals(BoolSet(byteArrayOf(1)).hashCode(), BoolSet(byteArrayOf(1, 0)).hashCode())
    }

    @Test
    fun myFsCapabilityFromServer() {
        // 服务端 my 文件系统能力：0,1,6,7,8,9,10,14,15,16,18,19,20,23,24
        val bits = listOf(0, 1, 6, 7, 8, 9, 10, 14, 15, 16, 18, 19, 20, 23, 24)
        val s = BoolSet.of(*bits.toIntArray())
        val parsed = BoolSet.fromBase64(s.toBase64())
        (0..31).forEach { assertEquals(it in bits, parsed.has(it), "bit $it") }
        assertFalse(parsed.has(NavigatorCapability.RESTORE))
    }

    @Serializable
    private data class Holder(val capability: BoolSet? = null, val permission: BoolSet = BoolSet.EMPTY)

    @Test
    fun serializer() {
        val h = CloudreveJson.decodeFromString(Holder.serializer(), """{"capability":"Aw==","permission":"not base64!"}""")
        val cap = h.capability!!
        assertTrue(cap.has(0) && cap.has(1))
        // 非法输入宽容地解析为空集合
        assertEquals(BoolSet.EMPTY, h.permission)
        assertEquals("""{"capability":"Aw==","permission":""}""", CloudreveJson.encodeToString(Holder.serializer(), h))
        val user = CloudreveJson.decodeFromString(
            User.serializer(),
            """{"id":"u1","nickname":"n","group":{"id":"g","name":"Admin","permission":"AQ=="}}""",
        )
        assertTrue(user.isAdmin)
        assertTrue(user.hasPermission(GroupPermission.IS_ADMIN))
        assertFalse(user.hasPermission(GroupPermission.SHARE))
    }
}
