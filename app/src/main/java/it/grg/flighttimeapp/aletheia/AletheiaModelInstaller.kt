package it.grg.flighttimeapp.aletheia

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Downloads the aviation-domain GGUF into app-private storage.
 *
 * Weights are **not** shipped in the APK (Play size / binary asset blocker).
 * Default URL points at a public AviationQA Q4_K_M GGUF suitable for Offline study-aid use;
 * replace with the official Aletheia Offline weights URL when the coordinator shares it.
 *
 * Expected destination: [filesDir]/aletheia/offline.gguf
 */
class AletheiaModelInstaller(context: Context) {
    private val app = context.applicationContext

    fun modelFile(): File = File(app.filesDir, MODEL_REL_PATH)

    fun isInstalled(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() >= MIN_BYTES
    }

    /**
     * Enqueues a Wi‑Fi download of [DEFAULT_MODEL_URL] into [modelFile].
     * Returns the DownloadManager id, or -1 if already installed / enqueue failed.
     */
    fun enqueueDownload(wifiOnly: Boolean = true): Long {
        if (isInstalled()) return -1L
        val dest = modelFile()
        dest.parentFile?.mkdirs()
        // DownloadManager requires a content/file URI destination under public dirs for some APIs;
        // for app-private files we download to cache then callers can rename, or use setDestinationUri
        // with FileProvider. Simplest reliable path: download to external files then copy.
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val staging = File(app.getExternalFilesDir(null), "aletheia/offline.gguf.download")
        staging.parentFile?.mkdirs()
        if (staging.exists()) staging.delete()
        val request = DownloadManager.Request(Uri.parse(DEFAULT_MODEL_URL)).apply {
            setTitle("Aletheia Offline model")
            setDescription("Aviation GGUF for on-device Offline mode")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (wifiOnly) {
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            } else {
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
            }
            setDestinationUri(Uri.fromFile(staging))
        }
        return try {
            val id = dm.enqueue(request)
            prefs().edit().putLong(PREF_DOWNLOAD_ID, id).apply()
            id
        } catch (t: Throwable) {
            Log.e(TAG, "enqueueDownload failed", t)
            -1L
        }
    }

    /** If a prior download finished, copy staging → [modelFile]. Returns true when model ready. */
    fun promoteIfDownloaded(): Boolean {
        if (isInstalled()) return true
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = prefs().getLong(PREF_DOWNLOAD_ID, -1L)
        if (id < 0) return false
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) return false
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return false
            val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                ?: return false
            val src = uriToFile(local) ?: return false
            val dest = modelFile()
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            runCatching { src.delete() }
            Log.i(TAG, "Promoted GGUF to ${dest.absolutePath} (${dest.length()} bytes)")
            return isInstalled()
        }
        return false
    }

    fun downloadProgress(): Pair<Long, Long>? {
        val id = prefs().getLong(PREF_DOWNLOAD_ID, -1L)
        if (id < 0) return null
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c: Cursor ->
            if (!c.moveToFirst()) return null
            val sofar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return sofar to total
        }
        return null
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun uriToFile(uriString: String): File? {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }
            else -> null
        }
    }

    companion object {
        private const val TAG = "AletheiaModel"
        private const val PREFS = "aletheia_model"
        private const val PREF_DOWNLOAD_ID = "download_id"
        const val MODEL_REL_PATH = "aletheia/offline.gguf"
        const val MIN_BYTES = 1_000_000L

        /**
         * Public AviationQA Q4_K_M GGUF (HF). Coordinator should replace with the official
         * Aletheia Offline domain weights when available. Do not embed in APK.
         */
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/ANSHG/merged_llama-3.2-3b-AviationQA-ansh-Q4_K_M-GGUF/resolve/main/" +
                "merged_llama-3.2-3b-aviationqa-ansh-q4_k_m.gguf"
    }
}
