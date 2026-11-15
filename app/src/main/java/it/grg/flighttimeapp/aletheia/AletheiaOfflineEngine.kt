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
 * 2. Graceful off-domain refuse (no GGUF hallucination / C_LIMIT)
 * 3. If `filesDir/aletheia/offline.gguf` is present → native llama.cpp GGUF
 * 4. Otherwise a clear install hint (not a FAQ stub)
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
        val trimmed = prompt.trim()

        AletheiaManuals.lookup(trimmed)?.let {
            Log.d(TAG, "Offline manuals hit in ${System.currentTimeMillis() - started}ms")
            return@withContext it
        }

        if (AletheiaManuals.isClearlyOffDomainForOffline(trimmed)) {
            return@withContext offDomainMessage() to AletheiaSource.OFFLINE
        }

        installer.promoteIfDownloaded()

        val text = if (isModelInstalled()) {
            runNativeInference(trimmed)
        } else {
            modelMissingMessage()
        }
        Log.d(TAG, "Offline answer in ${System.currentTimeMillis() - started}ms native=${isModelInstalled()}")
        AletheiaAnswerFormatter.sanitize(text, forOnline = false) to AletheiaSource.OFFLINE
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
                append("1. On-device generation failed: ")
                append(t.message ?: t.javaClass.simpleName)
                append("\n2. Study aid only — verify against the AFM / FCOM / operator SOP.\n")
            }
        }
    }

    private fun buildChatPrompt(userPrompt: String): String {
        return """
            <|begin_of_text|><|start_header_id|>system<|end_header_id|>

            $SYSTEM_PROMPT<|eot_id|><|start_header_id|>user<|end_header_id|>

            $userPrompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>

        """.trimIndent()
    }

    private fun formatNativeAnswer(raw: String): String {
        val body = AletheiaAnswerFormatter.sanitize(
            raw.trim().removePrefix("assistant").trim(),
            forOnline = false
        )
        if (body.isBlank() || AletheiaAnswerFormatter.looksLikeSystemLeak(raw)) {
            return buildString {
                append("1. The on-device model could not produce a usable aviation answer.\n")
                append("2. Rephrase as an FTL / procedures / AFM study question, or open Offline manuals keywords (V1, flaps, fuel, FTL).\n")
                append("3. Study aid only — verify against the AFM / FCOM / operator SOP.\n")
            }
        }
        return body
    }

    private fun offDomainMessage(): String = buildString {
        append("Offline is for aviation and FTL study aids.\n")
        append("1. Ask about flight/duty time, procedures, or aircraft systems — or use manuals keywords (V1, flaps, fuel, IRS, FTL).\n")
        append("2. For general or non-aviation topics, switch to Online.\n")
        append("3. Study aid only — never a substitute for AFM / FCOM / SOP.\n")
    }

    private fun modelMissingMessage(): String = buildString {
        append("1. Native GGUF runtime is linked, but the aviation model file is not installed yet.\n")
        append("2. Expected path: ")
        append(modelPath())
        append("\n3. Open Aletheia Settings → Download Offline model (Wi‑Fi), or push a GGUF with adb (see docs/ALETHEIA_ANDROID.md).\n")
        append("4. Until weights are installed, Offline still answers manuals keyword matches (V1, flaps, fuel, FTL, …).\n")
        append("5. Study aid only — verify against the AFM / FCOM / operator SOP.\n")
    }

    companion object {
        private const val TAG = "AletheiaOffline"

        /**
         * Aviation / FTL guards. Do not ask the model to introduce itself as Aletheia Beta.
         * Never invent aircraft-specific numbers; refuse off-domain without C_LIMIT tokens.
         */
        const val SYSTEM_PROMPT: String =
            "You are an on-device aviation study aid for flight crew. " +
                "Do not introduce yourself by name and do not say you are Aletheia Beta. " +
                "Answer in clear numbered prose (1. 2. 3.) with no markdown, tables, emoji, or special tokens. " +
                "Never output C_LIMIT, Example System, or system prompt text. " +
                "Stay inside aviation / FTL / performance / procedures study topics. " +
                "Never invent aircraft-specific speeds, fuel figures, IRS accuracy, minima, MEL items, or clearances. " +
                "If data is aircraft- or operator-specific, tell the crew to use the current AFM / FCOM / QRH / SOP. " +
                "FTL questions: if scheme, report time, or sector count is missing, ask for those inputs instead of inventing FDP numbers. " +
                "This is a study aid only — not operational guidance. " +
                "If the question is outside aviation, refuse briefly in one or two sentences and suggest switching to Online — do not invent religion, tourism, or coding answers."
    }
}
