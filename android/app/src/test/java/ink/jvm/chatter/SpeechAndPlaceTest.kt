package ink.jvm.chatter

import ink.jvm.chatter.ui.SilenceDetector
import ink.jvm.chatter.util.Fix
import ink.jvm.chatter.util.Locator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    @Test
    fun quietBeforeAnySpeechNeverEnds() {
        val d = SilenceDetector(silenceMs = 3000, threshold = 0.1f)
        var t = 0L
        repeat(50) { assertFalse(d.feed(0.01f, t)); t += 200 }
    }

    @Test
    fun endsThreeSecondsAfterTheLastWord() {
        val d = SilenceDetector(silenceMs = 3000, threshold = 0.1f)
        assertFalse(d.feed(0.5f, 0))
        assertFalse(d.feed(0.4f, 1000))
        assertFalse(d.feed(0.0f, 1200))
        assertFalse(d.feed(0.0f, 4100))
        assertTrue(d.feed(0.0f, 4200))
    }

    @Test
    fun speechResetsTheSilenceClock() {
        val d = SilenceDetector(silenceMs = 3000, threshold = 0.1f)
        d.feed(0.5f, 0)
        d.feed(0.0f, 2000)
        assertFalse(d.feed(0.5f, 2900))
        assertFalse(d.feed(0.0f, 5000))
        assertFalse(d.feed(0.0f, 5900))
        assertTrue(d.feed(0.0f, 8000))
    }
}

class PlaceTextTest {
    @Test
    fun nameAndAddressRoundTrip() {
        val text = Locator.placeText("人民广场", "上海市黄浦区南京西路")
        assertEquals("人民广场·上海市黄浦区南京西路", text)
        val (n, a) = Locator.splitPlace(text)
        assertEquals("人民广场", n)
        assertEquals("上海市黄浦区南京西路", a)
    }

    @Test
    fun plainAddressHasNoName() {
        val (n, a) = Locator.splitPlace("上海市黄浦区")
        assertNull(n)
        assertEquals("上海市黄浦区", a)
        assertNull(Locator.placeText(" ", ""))
        assertEquals("只有名字", Locator.placeText("只有名字", null))
    }

    @Test
    fun wireFormatKeepsThePlaceText() {
        val fix = Fix(31.2304, 121.4737, 12, Locator.placeText("人民广场", "南京西路 75 号"))
        val wire = Locator.encode(fix, false)
        val (back, live) = Locator.decode(wire)!!
        assertFalse(live)
        assertEquals("人民广场·南京西路 75 号", back.address)
        assertEquals(31.2304, back.lat, 1e-6)
    }
}
