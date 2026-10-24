package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * On-device aviation assistant.
 *
 * Packaging note vs iOS:
 * - iOS Aletheia Offline typically ships or downloads a domain GGUF inside the app sandbox.
 * - Android APK size limits make embedding a multi-hundred-MB GGUF undesirable; the same
 *   model file should be downloaded once into [modelFile] (filesDir/aletheia/offline.gguf).
 * - Native llama.cpp / MediaPipe wiring can replace [heuristicAnswer] when the JNI/AAR
 *   used by Aletheia Offline is linked; until then manuals-first + domain heuristic keep
 *   Offline unlimited and syncable via [AletheiaOutbox].
 */
class AletheiaOfflineEngine(private val context: Context) {
    private val modelFile: File
        get() = File(context.filesDir, "aletheia/offline.gguf")

    fun isModelInstalled(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES

    fun modelPath(): String = modelFile.absolutePath

    fun isPowerSaveMode(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode == true
    }

    suspend fun answer(prompt: String): Pair<String, AletheiaSource> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        // Thinking delay parity (~1–4s feel when offline is selected / slow).
        val thinkMs = if (isPowerSaveMode()) 3500L else 1200L
        delay(thinkMs)

        AletheiaManuals.lookup(prompt)?.let { return@withContext it }

        val text = if (isModelInstalled()) {
            // Placeholder for native GGUF inference — keep aviation tone and clean prose.
            heuristicAnswer(prompt) + "\n\n[" + AletheiaFeature.BRAND + " Offline model file detected at ${modelFile.name}. Native GGUF runtime not linked in this build; using domain heuristic. See docs/ALETHEIA_ANDROID.md.]"
        } else {
            heuristicAnswer(prompt)
        }
        Log.d(TAG, "Offline answer in ${System.currentTimeMillis() - started}ms")
        text to AletheiaSource.OFFLINE
    }

    private fun heuristicAnswer(prompt: String): String {
        val mid = UUID.randomUUID().toString().take(8)
        return buildString {
            append(AletheiaFeature.BRAND)
            append(" (Offline)\n\n")
            append("1. I am running fully on-device. Unlimited Offline chats are available without using the daily Online quota.\n")
            append("2. For aviation questions I prioritise manuals-style structure: numbered prose, no markdown tables.\n")
            append("3. Your question: ")
            append(prompt.trim().take(280))
            append('\n')
            append("4. Without a matched manual entry or linked GGUF weights, treat this as a study aid only and verify against the AFM / FCOM / operator SOP.\n")
            append("5. When connectivity returns, this turn is queued to Firebase for security logging (RL use only if you consented Yes).\n")
            append("\nRef: ")
            append(mid)
        }
    }

    companion object {
        private const val TAG = "AletheiaOffline"
        private const val MIN_MODEL_BYTES = 1_000_000L
    }
}
