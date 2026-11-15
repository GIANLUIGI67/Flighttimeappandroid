package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-5 and CT-LOCALIZATION: every Aletheia text the user can see (string resources in the
 * three Aletheia locales, layouts, fixed on-device texts) never names an upstream model,
 * vendor or runtime; the three locales have the same keys and the same format arguments.
 */
class AletheiaUserTextTest {
    private val banned = Regex(
        """(?i)\b(haiku|groq|claude|anthropic|openai|chat\s?gpt|gpt|llama|llama\.cpp|aviationqa|hugging\s?face|huggingface|flash|qwen|gemini|mistral|deepseek)\b|(?<!\.)\bgguf\b"""
    )

    private fun strings(dir: String): Map<String, String> {
        val text = TestFiles.project("src/main/res/$dir/strings.xml").readText()
        return Regex("""<string name="(aletheia_[a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val locales = listOf("values", "values-en", "values-it")

    @Test
    fun noVendorOrModelNames_inAnyAletheiaString() {
        for (dir in locales) {
            for ((k, v) in strings(dir)) {
                val hit = banned.find(v)
                assertTrue("$dir/$k shows \"${hit?.value}\": $v", hit == null)
            }
        }
    }

    @Test
    fun sameKeys_andSameFormatArguments_inEnglishDefaultAndItalian() {
        val base = strings("values")
        assertTrue(base.size >= 100)
        for (dir in listOf("values-en", "values-it")) {
            val other = strings(dir)
            assertEquals("key set of $dir", base.keys, other.keys)
            for ((k, v) in base) {
                val args = Regex("""%(\d)\$[sd]""").findAll(v).map { it.value }.toSortedSet()
                val otherArgs = Regex("""%(\d)\$[sd]""").findAll(other.getValue(k)).map { it.value }.toSortedSet()
                assertEquals("format args of $dir/$k", args, otherArgs)
            }
        }
    }

    @Test
    fun engineLabels_andPlanTexts_matchIOSAndServer() {
        val en = strings("values")
        val it = strings("values-it")
        assertEquals("Aletheia online", en["aletheia_engine_online"])
        assertEquals("Aletheia 1.5B (on this phone)", en["aletheia_engine_local"])
        assertEquals("Aletheia 1.5B (sul telefono)", it["aletheia_engine_local"])
        assertTrue(it.getValue("aletheia_engine_local_unverified").contains("non verificato"))
        assertTrue(it.getValue("aletheia_engine_fixed").contains("Controlli fissi"))
        assertTrue(en.getValue("aletheia_credits_s").contains("40"))
        assertTrue(en.getValue("aletheia_credits_m").contains("150"))
        assertTrue(en.getValue("aletheia_credits_l").contains("300"))
        assertTrue(it.getValue("aletheia_credits_s").contains("40"))
        assertTrue(it.getValue("aletheia_credits_l").contains("300"))
        assertTrue(en.getValue("aletheia_plan_pro").contains("50 Aletheia Pro replies"))
        assertTrue(it.getValue("aletheia_delete_button").contains("Cancella i miei dati"))
        assertEquals("Aletheia Beta %1\$s", en["aletheia_version_format"])
        // Removed third-party download texts are gone everywhere.
        listOf("aletheia_model_missing", "aletheia_download_offline_model", "aletheia_model_installed").forEach { key ->
            locales.forEach { assertTrue("$it still has $key", !strings(it).containsKey(key)) }
        }
    }

    @Test
    fun aletheiaLayouts_useStringResources_only() {
        val dir = TestFiles.project("src/main/res/layout")
        val files = dir.listFiles { f -> f.name.contains("aletheia") || f.name == "include_tile_crew_tools.xml" }!!
        assertTrue(files.size >= 6)
        for (f in files) {
            val hardCoded = Regex("""android:(text|hint|contentDescription)="(?!@)([^"]+)"""").findAll(f.readText()).toList()
            assertTrue("${f.name} hard-codes ${hardCoded.map { it.value }}", hardCoded.isEmpty())
        }
    }

    @Test
    fun fixedOnDeviceTexts_noVendorNames_bothLanguages() {
        for (loc in listOf("en", "it")) {
            listOf(
                AletheiaOfflineTexts.offDomain(loc),
                AletheiaOfflineTexts.modelMissing(loc),
                AletheiaOfflineTexts.runtimeUnavailable(loc),
                AletheiaOfflineTexts.generationFailed(loc),
                AletheiaOfflineTexts.unusableOutput(loc),
                AletheiaAnswerFormatter.gracefulDegradedMessage(true),
                AletheiaAnswerFormatter.gracefulDegradedMessage(false)
            ).forEach { t -> assertTrue("$loc: $t", banned.find(t) == null) }
        }
        assertTrue(AletheiaOfflineTexts.modelMissing("it").startsWith("1. Il modello Aletheia 1.5B"))
    }
}
