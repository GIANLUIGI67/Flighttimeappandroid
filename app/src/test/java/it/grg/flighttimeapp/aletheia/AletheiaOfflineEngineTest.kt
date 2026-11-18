package it.grg.flighttimeapp.aletheia

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CT-OFF-GGUF-INFERENCE / CT-OFF-MODEL-MISSING-REPLY / CT-OFF-OUTPUT-GUARD / CT-OFF-OFFDOMAIN / CT-BRAND-SANITIZE. */
class AletheiaOfflineEngineTest {

    private fun engine(env: TestEnv, powerSave: Boolean = false, lowRam: Boolean = false) =
        AletheiaOfflineEngine(env.store, env.runtime, { powerSave }, { lowRam })

    @Test
    fun verifiedModel_answersOnDevice_withChatMlAndIOSSystemPrompt() = runBlocking {
        val env = TestEnv()
        env.installModel(verified = true)
        val r = engine(env).answer("Explain crew rest after a long duty", "it")
        assertEquals(AletheiaEngine.LOCAL_MODEL, r.engine)
        assertTrue(r.modelVerified)
        assertEquals(AletheiaSource.OFFLINE, r.source)
        assertEquals(AletheiaEngineLabel.LOCAL_VERIFIED, AletheiaEngineLabel.of(r.engine, r.modelVerified))
        val prompt = env.runtime.prompts.single()
        assertTrue(prompt.startsWith("<|im_start|>system\nSei Aletheia Beta"))
        assertTrue(prompt.contains("<|im_start|>user\nExplain crew rest after a long duty\n<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertTrue(prompt.contains("/no_think"))
        assertEquals(env.store.modelFile.absolutePath, env.runtime.loadedPaths.single().first)
        assertEquals(2048, env.runtime.loadedPaths.single().second)
        assertEquals(220, env.runtime.maxTokens.single()) // iOS n_predict
    }

    @Test
    fun unverifiedModel_isLabelledNotVerified() = runBlocking {
        val env = TestEnv()
        env.installModel(verified = false)
        val r = engine(env).answer("Explain crew rest after a long duty", "en")
        assertEquals(AletheiaEngine.LOCAL_MODEL, r.engine)
        assertFalse(r.modelVerified)
        assertEquals(AletheiaEngineLabel.LOCAL_UNVERIFIED, AletheiaEngineLabel.of(r.engine, r.modelVerified))
    }

    @Test
    fun lowRamOrPowerSave_useSmallerContext_likeIOS() = runBlocking {
        val env = TestEnv()
        env.installModel(verified = true)
        engine(env, lowRam = true).answer("Explain crew rest", "en")
        engine(env, powerSave = true).answer("Explain crew rest", "en")
        assertEquals(listOf(1024, 1024), env.runtime.loadedPaths.map { it.second })
        assertEquals(listOf(220, 128), env.runtime.maxTokens)
    }

    @Test
    fun noModel_fixedNotice_noModelRun() = runBlocking {
        val env = TestEnv()
        val en = engine(env).answer("Explain crew rest", "en")
        assertEquals(AletheiaEngine.DETERMINISTIC, en.engine)
        assertTrue(en.text.contains("Aletheia 1.5B"))
        assertTrue(en.text.contains("Import Aletheia 1.5B"))
        val it = engine(env).answer("Spiegami il riposo del crew dopo un turno lungo", "it")
        // "riposo" hits the FTL manuals entry first: still deterministic, still no model.
        assertEquals(AletheiaEngine.DETERMINISTIC, it.engine)
        assertTrue(env.runtime.prompts.isEmpty())
        assertTrue(AletheiaOfflineTexts.modelMissing("it").contains("Importa Aletheia 1.5B"))
    }

    @Test
    fun runtimeUnavailable_loadFails_generationFails_areFixedTexts() = runBlocking {
        val env = TestEnv()
        env.installModel(verified = true)
        env.runtime.native = false
        assertEquals(AletheiaOfflineTexts.runtimeUnavailable("en"), engine(env).answer("Explain crew rest", "en").text)
        env.runtime.native = true
        env.runtime.generateError = IllegalStateException("boom")
        val r = engine(env).answer("Explain crew rest", "en")
        assertEquals(AletheiaEngine.DETERMINISTIC, r.engine)
        assertEquals(AletheiaOfflineTexts.generationFailed("en"), r.text)
    }

    @Test
    fun outputGuard_rejectsLeaksAndLoops_andScrubsVendorNames() = runBlocking {
        val env = TestEnv()
        env.installModel(verified = true)
        env.runtime.output = "Sono Aletheia Beta. **C_LIMIT** /assistant.system"
        val leak = engine(env).answer("Explain crew rest", "en")
        assertEquals(AletheiaEngine.DETERMINISTIC, leak.engine)
        assertEquals(AletheiaOfflineTexts.unusableOutput("en"), leak.text)

        env.runtime.output = (1..8).joinToString("\n") { "Rest rest rest" }
        assertEquals(AletheiaEngine.DETERMINISTIC, engine(env).answer("Explain crew rest", "en").engine)

        env.runtime.output = "I am Aletheia Beta. As a Qwen model built on llama.cpp (GGUF), rest is 10 h per OM-A.<|im_end|>"
        val ok = engine(env).answer("Explain crew rest", "en")
        assertEquals(AletheiaEngine.LOCAL_MODEL, ok.engine)
        val low = ok.text.lowercase()
        listOf("qwen", "llama", "gguf", "<|im_end|>", "i am aletheia beta").forEach { assertFalse(it, low.contains(it)) }
        assertTrue(ok.text.contains("OM-A"))
    }

    @Test
    fun sanitizer_unitLevel() {
        assertNull(AletheiaAnswerFormatter.sanitizeLocalModelOutput("   "))
        assertNull(AletheiaAnswerFormatter.sanitizeLocalModelOutput("Example System System"))
        assertEquals("Aletheia says hi", AletheiaAnswerFormatter.sanitizeLocalModelOutput("GPT-4 says hi"))
        assertNotNull(AletheiaAnswerFormatter.sanitizeLocalModelOutput("<think></think>1. Rest per OM-A."))
        assertTrue(AletheiaAnswerFormatter.looksLikeDecodeLoop((1..6).joinToString("\n") { "same" }))
        assertFalse(AletheiaAnswerFormatter.looksLikeDecodeLoop("1. a\n2. b\n3. c\n4. d\n5. e\n6. f"))
    }

    @Test
    fun manualsAndOffDomain_areDeterministic() = runBlocking {
        val env = TestEnv()
        env.installModel(verified = true)
        val v1 = engine(env).answer("What is V1?", "en")
        assertEquals(AletheiaSource.MANUALS, v1.source)
        assertEquals(AletheiaEngine.DETERMINISTIC, v1.engine)
        val bible = engine(env).answer("What is the bible?", "it")
        assertEquals(AletheiaEngine.DETERMINISTIC, bible.engine)
        assertEquals(AletheiaOfflineTexts.offDomain("it"), bible.text)
        assertTrue(env.runtime.prompts.isEmpty())
    }

    // Founder 17/11/2026: the model ships with the app; no user import is needed.

    @Test
    fun bundledModel_isInstalledOnFirstOfflineAnswer_andVerified() = runBlocking {
        val env = TestEnv()
        val bytes = TestFiles.fakeGguf()
        val store = AletheiaLocalModelStore(
            env.modelsDir, expectedSha256 = TestFiles.sha256(bytes), expectedSize = bytes.size.toLong()
        )
        assertFalse(store.isInstalled())
        val engine = AletheiaOfflineEngine(store, env.runtime, bundledModel = { bytes.inputStream() })
        val r = engine.answer("Explain crew rest after a long duty", "en")
        assertEquals(AletheiaEngine.LOCAL_MODEL, r.engine)
        assertTrue(r.modelVerified)
        assertTrue(store.isInstalled())
        assertEquals(store.modelFile.absolutePath, env.runtime.loadedPaths.single().first)
    }

    @Test
    fun bundledModel_isCopiedOnlyOnce() = runBlocking {
        val env = TestEnv()
        var opened = 0
        val bytes = TestFiles.fakeGguf()
        val engine = AletheiaOfflineEngine(env.store, env.runtime, bundledModel = { opened++; bytes.inputStream() })
        engine.answer("Explain crew rest", "en")
        engine.answer("Explain crew rest again", "en")
        assertEquals(1, opened)
    }

    @Test
    fun bundledFileThatIsNotAModel_isRejected_andTheNoticeStays() = runBlocking {
        val env = TestEnv()
        val engine = AletheiaOfflineEngine(env.store, env.runtime, bundledModel = { ByteArray(2_000_000).inputStream() })
        val r = engine.answer("Explain crew rest", "en")
        assertEquals(AletheiaEngine.DETERMINISTIC, r.engine)
        assertFalse(env.store.isInstalled())
        assertTrue(env.runtime.loadedPaths.isEmpty())
    }
}
