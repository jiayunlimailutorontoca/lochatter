package ink.jvm.chatter

import ink.jvm.chatter.util.BackgroundSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSettingsTest {
    @Test
    fun every_brand_has_steps() {
        // Build.MANUFACTURER is null on the JVM → generic branch; it must still return guidance.
        assertTrue(BackgroundSettings.steps().isNotEmpty())
    }
}
