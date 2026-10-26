package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AletheiaAnswerFormatterTest {
    @Test
    fun sanitize_stripsBrandPrefixAndMarkdown() {
        val raw = """
            Sono **Aletheia Beta**.

            **Cosa fare a Taksim**

            1. **Istiklal Avenue** – walk
        """.trimIndent()
        val out = AletheiaAnswerFormatter.sanitize(raw, forOnline = true)
        assertFalse(out.contains("Aletheia Beta", ignoreCase = true))
        assertFalse(out.contains("**"))
        assertTrue(out.contains("Istiklal Avenue"))
    }

    @Test
    fun sanitize_replacesCLimitAndSystemLeak() {
        val raw = "Sono Aletheia Beta. **C_LIMIT**\nExample System\nExample System."
        val out = AletheiaAnswerFormatter.sanitize(raw, forOnline = false)
        assertFalse(out.contains("C_LIMIT"))
        assertFalse(out.contains("Example System"))
        assertTrue(out.contains("aviation", ignoreCase = true) || out.contains("Offline"))
    }

    @Test
    fun sanitize_onlineRejectsOfflineStub() {
        val raw =
            "I'm an aviation/FTL assistant offline. For religious topics, please switch to Online."
        val out = AletheiaAnswerFormatter.sanitize(raw, forOnline = true)
        assertFalse(out.contains("switch to Online", ignoreCase = true))
        assertTrue(out.contains("Online mode", ignoreCase = true))
    }

    @Test
    fun looksLikeSystemLeak_detectsAssistantSystem() {
        assertTrue(
            AletheiaAnswerFormatter.looksLikeSystemLeak(
                "Sono Aletheia Beta. /assistant.system\nExample System"
            )
        )
    }
}
