package ink.jvm.chatter

import ink.jvm.chatter.crypto.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    @Test
    fun round_trip_and_wrong_pin() {
        val pin = Migration.randomPin()
        assertEquals(6, pin.length)
        val payload = """{"server":"https://x","token":"t","userId":1,"keyRing":"${"k".repeat(1200)}"}"""
        val packed = Migration.pack(payload, pin)
        assertTrue(packed.startsWith(Migration.PREFIX))
        assertEquals(payload, Migration.unpack(packed, pin))
        assertNull(Migration.unpack(packed, if (pin == "000000") "000001" else "000000"))
        assertNull(Migration.unpack("nope:" + packed.substring(Migration.PREFIX.length), pin))
        assertNull(Migration.unpack(Migration.PREFIX + "AAAA", pin))
    }
}
