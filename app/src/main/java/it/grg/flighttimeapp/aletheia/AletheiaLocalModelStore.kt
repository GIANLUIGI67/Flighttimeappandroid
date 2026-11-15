package it.grg.flighttimeapp.aletheia

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The only on-device model of FlightTime Crew Tools is Aletheia 1.5B (AGENTS.md PR-1).
 * Nothing is downloaded from the network: the user imports the file from the phone
 * (system file picker). Identity mirrors iOS AletheiaModelPreset (accepted file names)
 * and adds a SHA-256 check against the recorded artifact; a file that passes the name
 * check but not the hash is usable but labelled "not verified" (PR-5).
 *
 * Expected artifact (Aletheia repo models/aletheia_1_5b_qwen32_factualrepair_v3_ggufdetail_q4km_20260609,
 * manifest.json + SHA256SUMS): [ALETHEIA_15B_FILE_NAME], [ALETHEIA_15B_SIZE] bytes, [ALETHEIA_15B_SHA256].
 */
class AletheiaLocalModelStore(
    private val modelsDir: File,
    private val legacyFiles: List<File> = emptyList(),
    private val expectedSha256: String = ALETHEIA_15B_SHA256,
    private val expectedSize: Long = ALETHEIA_15B_SIZE,
    private val minBytes: Long = MIN_BYTES
) {
    data class ModelRecord(
        val originalName: String,
        val sizeBytes: Long,
        val sha256: String,
        val verified: Boolean,
        val importedAtMs: Long
    )

    sealed class ImportResult {
        data class Imported(val record: ModelRecord) : ImportResult()
        /** File name is not one of the Aletheia 1.5B names iOS accepts. */
        object RejectedName : ImportResult()
        /** Not a GGUF file (magic bytes) or too small to be a model. */
        object RejectedNotModel : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    private val gson = Gson()

    val modelFile: File get() = File(modelsDir, INSTALLED_FILE_NAME)
    private val recordFile: File get() = File(modelsDir, RECORD_FILE_NAME)

    /** The imported model if its file is still present with the recorded size. */
    fun installedModel(): ModelRecord? {
        val record = readRecord() ?: return null
        val f = modelFile
        if (!f.isFile || f.length() != record.sizeBytes || f.length() < minBytes) return null
        return record
    }

    fun isInstalled(): Boolean = installedModel() != null

    /** Re-check the slot (iOS "Rescan"): drops a stale record whose file vanished or changed size. */
    fun rescan(): ModelRecord? {
        val record = installedModel()
        if (record == null) {
            readRecord()?.let { recordFile.delete() }
        }
        return record
    }

    /**
     * Copy [input] into the single model slot while computing SHA-256.
     * The previous model is replaced only after the new file is complete and accepted.
     */
    fun import(displayName: String?, input: InputStream, nowMs: Long = System.currentTimeMillis()): ImportResult {
        val name = displayName?.trim().orEmpty()
        if (!matchesCandidate(name)) return ImportResult.RejectedName
        if (!modelsDir.isDirectory && !modelsDir.mkdirs()) return ImportResult.Failed("storage")
        val tmp = File(modelsDir, TMP_FILE_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val header = ByteArray(4)
        var headerFilled = 0
        try {
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (headerFilled < 4) {
                            val take = minOf(4 - headerFilled, n)
                            System.arraycopy(buf, 0, header, headerFilled, take)
                            headerFilled += take
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            return ImportResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        if (headerFilled < 4 || !header.contentEquals(GGUF_MAGIC) || total < minBytes) {
            tmp.delete()
            return ImportResult.RejectedNotModel
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val verified = sha.equals(expectedSha256, ignoreCase = true) && total == expectedSize
        val dest = modelFile
        if (dest.exists() && !dest.delete()) {
            tmp.delete()
            return ImportResult.Failed("replace")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return ImportResult.Failed("rename")
        }
        val record = ModelRecord(
            originalName = name.take(200),
            sizeBytes = total,
            sha256 = sha,
            verified = verified,
            importedAtMs = nowMs
        )
        recordFile.writeText(gson.toJson(record))
        return ImportResult.Imported(record)
    }

    fun removeInstalled() {
        modelFile.delete()
        recordFile.delete()
        File(modelsDir, TMP_FILE_NAME).delete()
    }

    /**
     * Remove the third-party model the 1.12.15 app downloaded (filesDir/aletheia/offline.gguf
     * and its staging file). It is not Aletheia 1.5B and must never be loaded again.
     * @return number of files deleted.
     */
    fun cleanupLegacy(): Int {
        var deleted = 0
        for (f in legacyFiles) {
            if (f.exists() && f.delete()) deleted++
        }
        return deleted
    }

    private fun readRecord(): ModelRecord? {
        val f = recordFile
        if (!f.isFile) return null
        return try {
            gson.fromJson(f.readText(), ModelRecord::class.java)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AletheiaModelStore"

        /** Recorded Aletheia 1.5B Q4_K_M artifact (manifest.json, SHA256SUMS of 2026-06-09). */
        const val ALETHEIA_15B_FILE_NAME = "aletheia-1_5b-qwen32-factualrepair-v3-ggufdetail-q4_k_m.gguf"
        const val ALETHEIA_15B_SIZE = 986_048_000L
        const val ALETHEIA_15B_SHA256 = "0785b8514c1eec1ac33df6681c729272a3548e821d8c6357d593fc7a4c80a919"

        const val MIN_BYTES = 1_000_000L
        const val INSTALLED_FILE_NAME = "aletheia-1_5b.gguf"
        private const val RECORD_FILE_NAME = "aletheia-1_5b.json"
        private const val TMP_FILE_NAME = "aletheia-1_5b.gguf.import"
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

        /** Same accepted names as iOS AletheiaModelPreset.candidateFileNames. */
        val CANDIDATE_FILE_NAMES = listOf(
            "aletheia-higher-reasoning.gguf",
            ALETHEIA_15B_FILE_NAME,
            "aletheia-1_5b-qwen32-factualrepair-v3-q4_k_m.gguf",
            "aletheia-1.5b-factualrepair-v3-q4_k_m.gguf",
            "aletheia-1_5b-factual-repair-v3-q4_k_m.gguf"
        )

        /** Same stems as iOS AletheiaModelPreset.candidateStems. */
        val CANDIDATE_STEMS = listOf(
            "aletheia-higher-reasoning",
            "factualrepair-v3",
            "factual-repair-v3",
            "aletheia-1_5b",
            "aletheia-1.5b"
        )

        fun matchesCandidate(fileName: String): Boolean {
            val lower = fileName.lowercase()
            if (!lower.endsWith(".gguf")) return false
            if (CANDIDATE_FILE_NAMES.any { lower == it.lowercase() }) return true
            return CANDIDATE_STEMS.any { lower.contains(it.lowercase()) }
        }

        fun fromContext(context: Context): AletheiaLocalModelStore {
            val app = context.applicationContext
            val base = File(app.filesDir, "aletheia")
            val legacy = buildList {
                add(File(base, "offline.gguf"))
                app.getExternalFilesDir(null)?.let { add(File(it, "aletheia/offline.gguf.download")) }
            }
            return AletheiaLocalModelStore(File(base, "models"), legacy)
        }

        /** Cancel a still-running 1.12.15 download of the third-party model (DownloadManager). */
        fun cancelLegacyDownload(context: Context) {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("aletheia_model", Context.MODE_PRIVATE)
            val id = prefs.getLong("download_id", -1L)
            if (id < 0) return
            runCatching {
                val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(id)
            }.onFailure { Log.w(TAG, "legacy download cancel failed: ${it.message}") }
            prefs.edit().remove("download_id").apply()
        }
    }
}
