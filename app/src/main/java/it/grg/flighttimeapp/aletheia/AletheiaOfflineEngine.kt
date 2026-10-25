package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device aviation assistant (Aletheia Offline parity with iOS).
 *
 * Inference path:
 * 1. Manuals-first index ([AletheiaManuals]) → Manuali badge
 * 2. If `filesDir/aletheia/offline.gguf` is present → native llama.cpp GGUF via [AletheiaLlamaRuntime]
 * 3. Otherwise a clear install hint (not a FAQ stub) — user downloads weights via [AletheiaModelInstaller]
 *
 * Packaging: multi-hundred-MB GGUF is **not** embedded in the APK (Play size). Download once.
 */
class AletheiaOfflineEngine(private val context: Context) {
    private val installer = AletheiaModelInstaller(context)
    private val runtime = AletheiaLlamaRuntime.get()

    private val modelFile: File
        get() = installer.modelFile()

    fun isModelInstalled(): Boolean {
        installer.promoteIfDownloaded()
        return installer.isInstalled()
    }

    fun modelPath(): String = modelFile.absolutePath

    fun isPowerSaveMode(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode == true
    }

    suspend fun answer(prompt: String): Pair<String, AletheiaSource> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()

        AletheiaManuals.lookup(prompt)?.let { return@withContext it }

        installer.promoteIfDownloaded()

        val text = if (isModelInstalled()) {
            runNativeInference(prompt.trim())
        } else {
            modelMissingMessage()
        }
        Log.d(TAG, "Offline answer in ${System.currentTimeMillis() - started}ms native=${isModelInstalled()}")
        text to AletheiaSource.OFFLINE
    }

    private suspend fun runNativeInference(userPrompt: String): String {
        val path = modelPath()
        val nCtx = if (isPowerSaveMode()) 1024 else AletheiaLlamaRuntime.DEFAULT_N_CTX
        val maxTok = if (isPowerSaveMode()) 128 else AletheiaLlamaRuntime.DEFAULT_MAX_TOKENS
        val loaded = try {
            runtime.ensureLoaded(path, nCtx)
        } catch (t: Throwable) {
            Log.e(TAG, "GGUF load failed", t)
            false
        }
        if (!loaded) {
            return buildString {
                append(AletheiaFeature.BRAND)
                append(" (Offline)\n\n")
                append("1. The GGUF file is present but the native llama.cpp runtime could not load it.\n")
                append("2. Confirm the file at ")
                append(path)
                append(" is a valid aviation-domain GGUF (Q4_K_M or similar).\n")
                append("3. Treat any prior answers as study aids only — verify against the AFM / FCOM / operator SOP.\n")
            }
        }
        val chatPrompt = buildChatPrompt(userPrompt)
        return try {
            val raw = runtime.generate(chatPrompt, maxTok)
            formatNativeAnswer(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "GGUF generate failed", t)
            buildString {
                append(AletheiaFeature.BRAND)
                append(" (Offline)\n\n")
                append("1. On-device generation failed: ")
                append(t.message ?: t.javaClass.simpleName)
                append("\n2. Study aid only — verify against the AFM / FCOM / operator SOP.\n")
            }
        }
    }

    private fun buildChatPrompt(userPrompt: String): String {
        // ChatML-style prompt works with Llama-3.x Instruct GGUFs and llama_chat_apply_template
        // via formatChat=true tokenize special tokens. Keep FTL / study-aid guards explicit.
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>

            $SYSTEM_PROMPT<|eot_id|><|start_header_id|>user<|end_header_id|>

            $userPrompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>

        """.trimIndent()
    }

    private fun formatNativeAnswer(raw: String): String {
        val body = raw.trim()
            .removePrefix("assistant")
            .trim()
        if (body.isBlank()) {
            return buildString {
                append(AletheiaFeature.BRAND)
                append(" (Offline)\n\n")
                append("1. The on-device model returned an empty answer.\n")
                append("2. Rephrase the question or verify the GGUF weights.\n")
                append("3. Study aid only — verify against the AFM / FCOM / operator SOP.\n")
            }
        }
        return if (body.startsWith(AletheiaFeature.BRAND)) {
            body
        } else {
            AletheiaFeature.BRAND + " (Offline)\n\n" + body
        }
    }

    private fun modelMissingMessage(): String = buildString {
        append(AletheiaFeature.BRAND)
        append(" (Offline)\n\n")
        append("1. Native GGUF runtime is linked, but the aviation model file is not installed yet.\n")
        append("2. Expected path: ")
        append(modelPath())
        append("\n3. Open Aletheia Settings → Download Offline model (Wi‑Fi), or push a GGUF with adb:\n")
        append("   adb push your-model.gguf /sdcard/Download/offline.gguf\n")
        append("   then copy into the app filesDir (see docs/ALETHEIA_ANDROID.md).\n")
        append("4. Until weights are installed, Offline cannot run on-device inference. This is not a FAQ stub — manuals matches still answer when keywords hit.\n")
        append("5. Study aid only — verify against the AFM / FCOM / operator SOP.\n")
    }

    companion object {
        private const val TAG = "AletheiaOffline"

        /**
         * Aviation domain / FTL-style guards aligned with iOS Aletheia Offline intent.
         * Not operational authority; crew must use AFM/FCOM/SOP.
         */
        const val SYSTEM_PROMPT: String =
            "You are Aletheia Beta Offline, an on-device aviation study aid for flight crew. " +
                "Answer in clear numbered prose (1. 2. 3.) without markdown tables or emoji. " +
                "Stay inside aviation / FTL / performance / procedures study topics. " +
                "Never invent aircraft-specific speeds, minima, MEL items, or clearances. " +
                "If data is aircraft- or operator-specific, say to use the current AFM / FCOM / QRH / SOP. " +
                "This is a study aid only — not operational guidance. " +
                "If the question is outside aviation, refuse briefly and steer back to flight-crew topics."
    }
}
