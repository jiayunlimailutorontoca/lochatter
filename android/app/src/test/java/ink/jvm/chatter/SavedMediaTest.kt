package ink.jvm.chatter

import ink.jvm.chatter.util.parseSavedIds
import ink.jvm.chatter.util.serializeSavedIds
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedMediaTest {
    @Test
    fun parse_drops_blank_and_duplicate_lines_and_keeps_order() {
        assertEquals(listOf("a", "b", "c"), parseSavedIds("a\n\n b \r\nc\na\n"))
        assertEquals(emptyList<String>(), parseSavedIds(""))
        assertEquals(emptyList<String>(), parseSavedIds("\n \n"))
    }

    @Test
    fun serialize_keeps_only_the_newest() {
        assertEquals("c\nd", serializeSavedIds(listOf("a", "b", "c", "d"), 2))
        assertEquals("a\nb", serializeSavedIds(listOf("a", "b"), 10))
        assertEquals("", serializeSavedIds(emptyList(), 10))
    }

    @Test
    fun round_trip() {
        val ids = (1..50).map { "media-$it" }
        assertEquals(ids, parseSavedIds(serializeSavedIds(ids, 4000)))
        assertEquals(ids.takeLast(7), parseSavedIds(serializeSavedIds(ids, 7)))
    }
}
