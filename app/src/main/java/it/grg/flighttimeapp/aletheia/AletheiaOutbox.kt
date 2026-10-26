package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline → Firebase outbox. Entries sync via [AletheiaApi.logTurn] when network returns.
 * Prefs name [PREFS] is excluded from Android backup / device transfer.
 */
class AletheiaOutbox(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    fun enqueue(entry: AletheiaOutboxEntry) {
        synchronized(lock) {
            val list = loadUnlocked().toMutableList()
            list.removeAll { it.messageId == entry.messageId }
            list.add(entry)
            saveUnlocked(list)
        }
    }

    fun pending(): List<AletheiaOutboxEntry> = synchronized(lock) { loadUnlocked() }

    /**
     * Uploads a snapshot, then removes only successfully uploaded IDs from the
     * latest queue so concurrent [enqueue] turns are not dropped.
     */
    suspend fun flush(api: AletheiaApi): Int = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) { loadUnlocked() }
        if (snapshot.isEmpty()) return@withContext 0
        val uploadedIds = linkedSetOf<String>()
        for (item in snapshot) {
            try {
                val ok = api.logTurn(
                    prompt = item.prompt,
                    answer = item.answer,
                    messageId = item.messageId,
                    source = AletheiaSource.fromServer(item.source)
                )
                if (ok) uploadedIds.add(item.messageId)
            } catch (e: Exception) {
                Log.w(TAG, "Outbox sync failed for ${item.messageId}: ${e.message}")
            }
        }
        if (uploadedIds.isEmpty()) return@withContext 0
        synchronized(lock) {
            val latest = loadUnlocked().toMutableList()
            latest.removeAll { it.messageId in uploadedIds }
            saveUnlocked(latest)
        }
        uploadedIds.size
    }

    private fun loadUnlocked(): List<AletheiaOutboxEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AletheiaOutboxEntry>>() {}.type
            gson.fromJson<List<AletheiaOutboxEntry>>(raw, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveUnlocked(list: List<AletheiaOutboxEntry>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    companion object {
        private const val TAG = "AletheiaOutbox"
        const val PREFS = "aletheia_outbox"
        private const val KEY = "entries"
    }
}
