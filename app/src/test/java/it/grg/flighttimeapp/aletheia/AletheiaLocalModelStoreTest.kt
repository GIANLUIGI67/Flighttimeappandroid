package it.grg.flighttimeapp.aletheia

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CT-MODEL-IMPORT / CT-MODEL-LOCATE / CT-MODEL-STATUS / CT-MODEL-RESCAN and the third-party removal. */
class AletheiaLocalModelStoreTest {

    @Test
    fun recordedArtifact_isTheAletheia15B_fromTheRepoManifest() {
        assertEquals("aletheia-1_5b-qwen32-factualrepair-v3-ggufdetail-q4_k_m.gguf", AletheiaLocalModelStore.ALETHEIA_15B_FILE_NAME)
        assertEquals(986_048_000L, AletheiaLocalModelStore.ALETHEIA_15B_SIZE)
        assertEquals("0785b8514c1eec1ac33df6681c729272a3548e821d8c6357d593fc7a4c80a919", AletheiaLocalModelStore.ALETHEIA_15B_SHA256)
    }

    @Test
    fun acceptedNames_mirrorIOSPreset_andRejectThirdPartyFiles() {
        assertTrue(AletheiaLocalModelStore.matchesCandidate("aletheia-1_5b-qwen32-factualrepair-v3-ggufdetail-q4_k_m.gguf"))
        assertTrue(AletheiaLocalModelStore.matchesCandidate("aletheia-higher-reasoning.gguf"))
        assertTrue(AletheiaLocalModelStore.matchesCandidate("Aletheia-1.5B-copy.GGUF"))
        assertFalse(AletheiaLocalModelStore.matchesCandidate("merged_llama-3.2-3b-aviationqa-ansh-q4_k_m.gguf"))
        assertFalse(AletheiaLocalModelStore.matchesCandidate("offline.gguf"))
        assertFalse(AletheiaLocalModelStore.matchesCandidate("aletheia-1_5b.bin"))
        assertFalse(AletheiaLocalModelStore.matchesCandidate(""))
    }

    @Test
    fun import_matchingHash_isVerified_andReplacesNothingElse() {
        val dir = TestFiles.tempDir()
        val bytes = TestFiles.fakeGguf()
        val store = AletheiaLocalModelStore(dir, expectedSha256 = TestFiles.sha256(bytes), expectedSize = bytes.size.toLong())
        assertNull(store.installedModel())
        val r = store.import(AletheiaLocalModelStore.ALETHEIA_15B_FILE_NAME, bytes.inputStream(), nowMs = 42)
        val rec = (r as AletheiaLocalModelStore.ImportResult.Imported).record
        assertTrue(rec.verified)
        assertEquals(bytes.size.toLong(), rec.sizeBytes)
        assertEquals(TestFiles.sha256(bytes), rec.sha256)
        assertEquals(42L, rec.importedAtMs)
        assertEquals(rec, store.installedModel())
        assertTrue(store.modelFile.readBytes().contentEquals(bytes))
        assertFalse(File(dir, "aletheia-1_5b.gguf.import").exists())
    }

    @Test
    fun import_nameOkButHashDifferent_isUsableButNotVerified() {
        val dir = TestFiles.tempDir()
        val store = AletheiaLocalModelStore(dir) // real expected SHA of the 1.5B
        val r = store.import("aletheia-higher-reasoning.gguf", TestFiles.fakeGguf().inputStream())
        val rec = (r as AletheiaLocalModelStore.ImportResult.Imported).record
        assertFalse(rec.verified)
        assertNotNull(store.installedModel())
        assertEquals(AletheiaUiRules.ImportOutcome.OK_UNVERIFIED, AletheiaUiRules.importOutcome(r))
    }

    @Test
    fun import_rejectsWrongName_notGguf_tooSmall_andIoErrors() {
        val dir = TestFiles.tempDir()
        val store = AletheiaLocalModelStore(dir)
        assertEquals(AletheiaLocalModelStore.ImportResult.RejectedName,
            store.import("merged_llama-3.2-3b-aviationqa.gguf", TestFiles.fakeGguf().inputStream()))
        assertEquals(AletheiaLocalModelStore.ImportResult.RejectedName, store.import(null, TestFiles.fakeGguf().inputStream()))
        val notGguf = TestFiles.fakeGguf().also { it[0] = 0x50 }
        assertEquals(AletheiaLocalModelStore.ImportResult.RejectedNotModel,
            store.import("aletheia-1_5b.gguf", notGguf.inputStream()))
        assertEquals(AletheiaLocalModelStore.ImportResult.RejectedNotModel,
            store.import("aletheia-1_5b.gguf", TestFiles.fakeGguf(4096).inputStream()))
        val broken = object : InputStream() { override fun read(): Int = throw IOException("disk") }
        assertTrue(store.import("aletheia-1_5b.gguf", broken) is AletheiaLocalModelStore.ImportResult.Failed)
        assertNull(store.installedModel())
        assertFalse(File(dir, "aletheia-1_5b.gguf.import").exists())
    }

    @Test
    fun rescan_dropsRecord_whenFileVanishesOrChanges() {
        val env = TestEnv()
        env.installModel(verified = true)
        assertNotNull(env.store.rescan())
        env.store.modelFile.appendBytes(byteArrayOf(1, 2, 3))
        assertNull(env.store.rescan())
        assertNull(env.store.installedModel())
        env.installModel(verified = false)
        env.store.removeInstalled()
        assertNull(env.store.rescan())
    }

    @Test
    fun legacyThirdPartyDownload_isDeleted() {
        val base = TestFiles.tempDir()
        val legacy = File(base, "offline.gguf").apply { writeBytes(TestFiles.fakeGguf()) }
        val staging = File(base, "ext/aletheia/offline.gguf.download").apply { parentFile.mkdirs(); writeText("x") }
        val store = AletheiaLocalModelStore(File(base, "models"), listOf(legacy, staging, File(base, "absent")))
        assertEquals(2, store.cleanupLegacy())
        assertFalse(legacy.exists())
        assertFalse(staging.exists())
        assertEquals(0, store.cleanupLegacy())
        // The legacy file is never picked up as the model.
        assertNull(store.installedModel())
    }

    @Test
    fun noNetworkDownloadCode_remainsInTheApp() {
        val dir = TestFiles.project("src/main/java/it/grg/flighttimeapp/aletheia")
        val sources = dir.listFiles { f -> f.name.endsWith(".kt") }!!.joinToString("\n") { it.readText() }
        assertFalse(File(dir, "AletheiaModelInstaller.kt").exists())
        assertFalse(sources.contains("huggingface.co"))
        assertFalse(sources.contains("DownloadManager.Request"))
        assertFalse(sources.contains("enqueueDownload"))
    }
}
