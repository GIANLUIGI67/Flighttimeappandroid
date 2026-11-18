package it.grg.flighttimeapp.aletheia

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device answers (parity with iOS AletheiaOfflineEngine + AletheiaLocalGGUFRuntime).
 *
 * Path, in order - the reply records which engine REALLY answered:
 * 1. Manuals package ([AletheiaManuals]) -> Manuali, deterministic.
 * 2. Clearly off-domain -> fixed refusal, deterministic.
 * 3. Aletheia 1.5B (shipped with the app in the install-time asset pack and copied into the model slot
 *    on first use, or imported by the user) -> native on-device generation, LOCAL_MODEL
 *    (labelled verified only when the file matched the recorded SHA-256).
 * 4. Otherwise (no model, engine unavailable, failed or unusable output) -> fixed text,
 *    deterministic. Nothing is ever downloaded and no other model is ever loaded.
 */
class AletheiaOfflineEngine(
    private val store: AletheiaLocalModelStore,
    private val runtime: AletheiaLocalRuntime,
    private val powerSave: () -> Boolean = { false },
    private val lowRam: () -> Boolean = { false },
    /** The model shipped with the app (install-time asset pack); null when this build has none. */
    private val bundledModel: () -> java.io.InputStream? = { null }
) {
    fun isModelInstalled(): Boolean = store.isInstalled()

    fun installedModel(): AletheiaLocalModelStore.ModelRecord? = store.installedModel()

    suspend fun answer(prompt: String, locale: String = "en"): AletheiaEngineReply = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val trimmed = prompt.trim()

        AletheiaManuals.lookup(trimmed)?.let { (text, source) ->
            return@withContext AletheiaEngineReply(text, source, AletheiaEngine.DETERMINISTIC)
        }

        if (AletheiaManuals.isClearlyOffDomainForOffline(trimmed)) {
            return@withContext fixed(AletheiaOfflineTexts.offDomain(locale))
        }

        val record = store.installedModel()
            ?: installBundledModel()
            ?: return@withContext fixed(AletheiaOfflineTexts.modelMissing(locale))

        val nCtx = if (powerSave() || lowRam()) AletheiaLlamaRuntime.LOW_RAM_N_CTX else AletheiaLlamaRuntime.DEFAULT_N_CTX
        val maxTok = if (powerSave()) AletheiaLlamaRuntime.POWER_SAVE_MAX_TOKENS else AletheiaLlamaRuntime.DEFAULT_MAX_TOKENS
        val loaded = try {
            runtime.ensureLoaded(store.modelFile.absolutePath, nCtx)
        } catch (t: Throwable) {
            Log.e(TAG, "on-device model load failed", t)
            false
        }
        if (!loaded) return@withContext fixed(AletheiaOfflineTexts.runtimeUnavailable(locale))

        val raw = try {
            runtime.generate(
                AletheiaOfflineTexts.chatMlPrompt(AletheiaOfflineTexts.systemPrompt(locale), trimmed),
                maxTok
            )
        } catch (t: Throwable) {
            Log.e(TAG, "on-device generation failed", t)
            return@withContext fixed(AletheiaOfflineTexts.generationFailed(locale))
        }
        val cleaned = AletheiaAnswerFormatter.sanitizeLocalModelOutput(raw)
            ?: return@withContext fixed(AletheiaOfflineTexts.unusableOutput(locale))
        Log.d(TAG, "on-device answer in ${System.currentTimeMillis() - started}ms verified=${record.verified}")
        AletheiaEngineReply(
            text = cleaned,
            source = AletheiaSource.OFFLINE,
            engine = AletheiaEngine.LOCAL_MODEL,
            modelVerified = record.verified
        )
    }

    private val installLock = Any()

    /**
     * Founder 17/11/2026: copy the model shipped with the app into the model slot, once, through the same
     * SHA-256-checked import as a manual import. Returns null when the build has no bundled model.
     */
    fun installBundledModel(): AletheiaLocalModelStore.ModelRecord? = synchronized(installLock) {
        store.installedModel()?.let { return it }
        val input = try {
            bundledModel()
        } catch (t: Throwable) {
            null
        } ?: return null
        when (val result = store.import(AletheiaLocalModelStore.ALETHEIA_15B_FILE_NAME, input)) {
            is AletheiaLocalModelStore.ImportResult.Imported -> result.record
            else -> {
                Log.w(TAG, "bundled model not installed: $result")
                null
            }
        }
    }

    private fun fixed(text: String) =
        AletheiaEngineReply(text, AletheiaSource.OFFLINE, AletheiaEngine.DETERMINISTIC)

    companion object {
        private const val TAG = "AletheiaOffline"
        /** Path of the model inside the install-time asset pack :aletheia_model. */
        const val BUNDLED_MODEL_ASSET = "aletheia/" + AletheiaLocalModelStore.ALETHEIA_15B_FILE_NAME

        fun fromContext(context: Context): AletheiaOfflineEngine {
            val app = context.applicationContext
            val store = AletheiaLocalModelStore.fromContext(app)
            // The 1.12.15 third-party download must never be used again.
            store.cleanupLegacy()
            AletheiaLocalModelStore.cancelLegacyDownload(app)
            return AletheiaOfflineEngine(
                store = store,
                runtime = AletheiaLlamaRuntime.get(),
                powerSave = {
                    (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
                },
                lowRam = {
                    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val info = ActivityManager.MemoryInfo()
                    am?.getMemoryInfo(info)
                    info.totalMem in 1 until 6_000_000_000L
                },
                bundledModel = {
                    try {
                        app.assets.open(BUNDLED_MODEL_ASSET)
                    } catch (e: java.io.IOException) {
                        null
                    }
                }
            )
        }
    }
}
