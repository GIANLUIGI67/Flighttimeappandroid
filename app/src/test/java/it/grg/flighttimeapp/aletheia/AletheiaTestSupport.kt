package it.grg.flighttimeapp.aletheia

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files

/** In-memory SharedPreferences so the real AletheiaPrefs / AletheiaOutbox run on the JVM. */
class InMemoryPrefs : SharedPreferences {
    val map = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    inner class EditorImpl : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed.add(key) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { applyNow(); return true }
        override fun apply() = applyNow()
        private fun applyNow() {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}

/** Records every callable the client makes and answers with scripted data. */
class FakeBackend : AletheiaBackend {
    val calls = mutableListOf<String>()
    val consentCalls = mutableListOf<Pair<Boolean, String>>()
    val chatRequests = mutableListOf<AletheiaChatRequest>()
    val turns = mutableListOf<AletheiaTurnLog>()
    val feedbacks = mutableListOf<AletheiaFeedbackPayload>()
    val deletes = mutableListOf<String>()

    var status = AletheiaStatus(true, "free", 0, 20, 20, 0, 0, 0)
    var statusError: Exception? = null
    var chatError: Exception? = null
    var consentError: Exception? = null
    var logTurnError: Exception? = null
    var feedbackError: Exception? = null
    var deleteResult = AletheiaDeleteResult(true, false, 3, 1, 2)
    var chatAnswer = "Online answer."
    var chatSource: AletheiaSource = AletheiaSource.ONLINE
    var chatRemainingDaily = 19
    var purchase = AletheiaPurchaseResult(true, "plus", 40)

    override suspend fun setConsent(granted: Boolean, source: String): Boolean {
        calls += "aletheiaSetConsent"
        consentError?.let { throw it }
        consentCalls += granted to source
        status = status.copy(trainingConsent = granted)
        return granted
    }

    override suspend fun getStatus(): AletheiaStatus {
        calls += "aletheiaGetStatus"
        statusError?.let { throw it }
        return status
    }

    override suspend fun chat(request: AletheiaChatRequest): AletheiaChatResult {
        calls += "aletheiaChat"
        chatError?.let { throw it }
        chatRequests += request
        return AletheiaChatResult(
            messageId = request.clientRequestId,
            answer = chatAnswer,
            brand = AletheiaFeature.BRAND,
            source = chatSource,
            route = "online",
            remainingDaily = chatRemainingDaily,
            remainingHaiku = 0,
            creditsRemaining = 0,
            dailyUsed = 20 - chatRemainingDaily,
            dailyLimit = 20,
            trainingConsent = true,
            engine = if (chatSource == AletheiaSource.MANUALS) AletheiaEngine.DETERMINISTIC else AletheiaEngine.ONLINE
        )
    }

    override suspend fun logTurn(turn: AletheiaTurnLog): Boolean {
        calls += "aletheiaLogTurn"
        logTurnError?.let { throw it }
        turns += turn
        return true
    }

    override suspend fun submitFeedback(feedback: AletheiaFeedbackPayload): Boolean {
        calls += "aletheiaSubmitFeedback"
        feedbackError?.let { throw it }
        feedbacks += feedback
        return true
    }

    override suspend fun deleteMyData(source: String): AletheiaDeleteResult {
        calls += "aletheiaDeleteMyData"
        deletes += source
        return deleteResult
    }

    override suspend fun verifyPlayPurchase(productId: String, purchaseToken: String): AletheiaPurchaseResult {
        calls += "aletheiaVerifyPlayPurchase"
        return purchase
    }
}

/** Stand-in for the native runtime: scripted load / generation results. */
class FakeRuntime(
    var native: Boolean = true,
    var loads: Boolean = true,
    var output: String = "1. FDP depends on the scheme and report time.\n2. Verify against your OM-A.",
    var generateError: Throwable? = null
) : AletheiaLocalRuntime {
    val prompts = mutableListOf<String>()
    val loadedPaths = mutableListOf<Pair<String, Int>>()
    val maxTokens = mutableListOf<Int>()

    override suspend fun probeNative(): Boolean = native
    override suspend fun ensureLoaded(path: String, nCtx: Int): Boolean {
        loadedPaths += path to nCtx
        return native && loads
    }
    override suspend fun generate(prompt: String, maxTokens: Int): String {
        prompts += prompt
        this.maxTokens += maxTokens
        generateError?.let { throw it }
        return output
    }
}

object TestFiles {
    fun tempDir(prefix: String = "aletheia-test"): File = Files.createTempDirectory(prefix).toFile()

    /** A small fake model file: GGUF magic + filler bytes. */
    fun fakeGguf(size: Int = 1_100_000): ByteArray {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        bytes[0] = 0x47; bytes[1] = 0x47; bytes[2] = 0x55; bytes[3] = 0x46
        return bytes
    }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Locate a project file whether tests run from the module dir or the repo root. */
    fun project(path: String): File {
        val candidates = listOf(File(path), File("app", path), File("..", path))
        return candidates.firstOrNull { it.exists() } ?: error("not found: $path from ${File(".").absolutePath}")
    }
}

class TestEnv(
    val backend: FakeBackend = FakeBackend(),
    val runtime: FakeRuntime = FakeRuntime(),
    var online: Boolean = true,
    var locale: String = "en",
    val modelsDir: File = TestFiles.tempDir()
) {
    val prefsStore = InMemoryPrefs()
    val outboxStore = InMemoryPrefs()
    val prefs = AletheiaPrefs(prefsStore)
    val outbox = AletheiaOutbox(outboxStore)
    val store = AletheiaLocalModelStore(modelsDir)
    private var ids = 0
    val repo = AletheiaRepository(
        prefs = prefs,
        backend = backend,
        outbox = outbox,
        offline = AletheiaOfflineEngine(store, runtime),
        network = { online },
        localeProvider = { locale },
        newId = { "id-%08d".format(++ids) }
    )

    /** Import a fake model; [verifiedAs] makes the store treat it as the recorded artifact. */
    fun installModel(verified: Boolean): AletheiaLocalModelStore.ModelRecord {
        val bytes = TestFiles.fakeGguf()
        val s = if (verified) {
            AletheiaLocalModelStore(modelsDir, expectedSha256 = TestFiles.sha256(bytes), expectedSize = bytes.size.toLong())
        } else store
        val r = s.import(AletheiaLocalModelStore.ALETHEIA_15B_FILE_NAME, bytes.inputStream())
        return (r as AletheiaLocalModelStore.ImportResult.Imported).record
    }
}
