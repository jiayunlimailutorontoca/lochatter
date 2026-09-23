package ink.jvm.chatter

import ink.jvm.chatter.call.CallCaptions
import ink.jvm.chatter.call.CaptionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** call.caption: call id filter, late / duplicate lines, and interrupt-style phase updates. */
class CallCaptionsTest {
    private fun frame(id: String, who: String, text: String, state: String, ts: Long, phase: String? = null) =
        CaptionFrame(id, who, text, state, phase, ts)

    @Test
    fun partial_then_final_keeps_one_line() {
        var b = CallCaptions.begin("c")
        b = CallCaptions.apply(b, frame("c", "user", "你", "partial", 1, "listening"))
        assertEquals("你", b.draftUser)
        assertEquals("listening", b.phase)
        b = CallCaptions.apply(b, frame("c", "user", "你好", "final", 2, "thinking"))
        assertEquals("", b.draftUser)
        assertEquals(listOf("你好"), CallCaptions.rows(b).map { it.text })
        assertEquals("thinking", b.phase)
    }

    @Test
    fun other_call_and_closed_session_are_ignored() {
        var b = CallCaptions.begin("c")
        b = CallCaptions.apply(b, frame("other", "assistant", "串台", "final", 5))
        assertTrue(CallCaptions.rows(b).isEmpty())
        b = CallCaptions.apply(b, frame("c", "assistant", "我在", "final", 3))
        b = CallCaptions.close(b)
        b = CallCaptions.apply(b, frame("c", "assistant", "迟到", "final", 9))
        assertEquals(listOf("我在"), CallCaptions.rows(b).map { it.text })
    }

    @Test
    fun late_and_duplicate_lines_do_not_replace_a_newer_final() {
        var b = CallCaptions.begin("c")
        b = CallCaptions.apply(b, frame("c", "assistant", "第一句", "final", 10))
        b = CallCaptions.apply(b, frame("c", "assistant", "半句", "partial", 9))
        b = CallCaptions.apply(b, frame("c", "assistant", "更早", "final", 8))
        b = CallCaptions.apply(b, frame("c", "assistant", "第一句", "final", 10))
        b = CallCaptions.apply(b, frame("c", "user", "插话", "final", 4))
        b = CallCaptions.apply(b, frame("c", "assistant", "第二句", "final", 11, "speaking"))
        assertEquals(listOf("第一句", "插话", "第二句"), CallCaptions.rows(b).map { it.text })
        assertEquals("speaking", b.phase)
    }

    @Test
    fun drafts_stay_on_their_own_side_and_old_lines_drop_off() {
        var b = CallCaptions.begin("c")
        b = CallCaptions.apply(b, frame("c", "user", "我", "partial", 1))
        b = CallCaptions.apply(b, frame("c", "assistant", "嗯", "partial", 1))
        assertEquals(listOf("我", "嗯"), CallCaptions.rows(b).map { it.text })
        repeat(CallCaptions.MAX_LINES + 5) { i ->
            b = CallCaptions.apply(b, frame("c", "assistant", "n$i", "final", 100L + i))
        }
        assertEquals(CallCaptions.MAX_LINES, b.lines.size)
        assertEquals("n${CallCaptions.MAX_LINES + 4}", b.lines.last().text)
        assertEquals("我", b.draftUser)
    }
}
