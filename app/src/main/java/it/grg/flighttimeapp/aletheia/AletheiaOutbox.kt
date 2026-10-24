package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline → Firebase outbox. Entries sync via [AletheiaApi.logTurn] when network returns.
 */
class AletheiaOutbox(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun enqueue(entry: AletheiaOutboxEntry) {
        val list = load().toMutableList()
        list.removeAll { it.messageId == entry.messageId }
        list.add(entry)
        save(list)
    }

    fun pending(): List<AletheiaOutboxEntry> = load()

    suspend fun flush(api: AletheiaApi): Int = withContext(Dispatchers.IO) {
        val items = load()
        if (items.isEmpty()) return@withContext 0
        val remaining = mutableListOf<AletheiaOutboxEntry>()
        var synced = 0
        for (item in items) {
            try {
                val ok = api.logTurn(
                    prompt = item.prompt,
                    answer = item.answer,
                    messageId = item.messageId,
                    source = AletheiaSource.fromServer(item.source)
                )
                if (ok) synced++ else remaining.add(item)
            } catch (e: Exception) {
                Log.w(TAG, "Outbox sync failed for ${item.messageId}: ${e.message}")
                remaining.add(item)
            }
        }
        save(remaining)
        synced
    }

    private fun load(): List<AletheiaOutboxEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AletheiaOutboxEntry>>() {}.type
            gson.fromJson<List<AletheiaOutboxEntry>>(raw, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<AletheiaOutboxEntry>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    companion object {
        private const val TAG = "AletheiaOutbox"
        private const val PREFS = "aletheia_outbox"
        private const val KEY = "entries"
    }
}
