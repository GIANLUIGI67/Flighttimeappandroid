package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable queue for `aletheiaLogTurn` / `aletheiaSubmitFeedback` when the network or the
 * callables are unavailable (iOS AletheiaSyncOutbox). Survives process death.
 * Prefs file [PREFS] is not included in Android backup / device transfer.
 */
class AletheiaOutbox(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    private val gson = Gson()
    private val lock = Any()

    fun enqueue(entry: AletheiaOutboxEntry) {
        synchronized(lock) {
            val list = loadUnlocked().toMutableList()
            list.removeAll { it.messageId == entry.messageId && kindOf(it) == kindOf(entry) }
            list.add(entry)
            // iOS AletheiaSyncOutbox maxItems = 200: drop the oldest beyond the cap.
            while (list.size > MAX_ITEMS) list.removeAt(0)
            saveUnlocked(list)
        }
    }

    fun pending(): List<AletheiaOutboxEntry> = synchronized(lock) { loadUnlocked() }

    fun clear() {
        synchronized(lock) { prefs.edit().remove(KEY).commit() }
    }

    /**
     * Uploads a snapshot, then removes only successfully uploaded items from the latest
     * queue so concurrent [enqueue] calls are not dropped. Failed items stay queued.
     */
    suspend fun flush(backend: AletheiaBackend): Int = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) { loadUnlocked() }
        if (snapshot.isEmpty()) return@withContext 0
        val uploaded = linkedSetOf<Pair<String, String>>()
        for (item in snapshot) {
            try {
                val ok = when (kindOf(item)) {
                    AletheiaOutboxEntry.KIND_FEEDBACK -> backend.submitFeedback(toFeedback(item))
                    else -> backend.logTurn(toTurnLog(item))
                }
                if (ok) uploaded.add(item.messageId to kindOf(item))
            } catch (e: Exception) {
                Log.w(TAG, "Outbox sync failed for ${item.messageId}: ${e.message}")
            }
        }
        if (uploaded.isEmpty()) return@withContext 0
        synchronized(lock) {
            val latest = loadUnlocked().toMutableList()
            latest.removeAll { (it.messageId to kindOf(it)) in uploaded }
            saveUnlocked(latest)
        }
        uploaded.size
    }

    private fun kindOf(e: AletheiaOutboxEntry): String = e.kind ?: AletheiaOutboxEntry.KIND_LOG_TURN

    private fun modeOf(e: AletheiaOutboxEntry): String =
        e.mode ?: AletheiaSource.fromServer(e.source).logMode

    private fun toTurnLog(e: AletheiaOutboxEntry) = AletheiaTurnLog(
        messageId = e.messageId,
        prompt = e.prompt,
        answer = e.answer,
        mode = modeOf(e),
        locale = e.locale ?: "en",
        engine = AletheiaEngine.fromApi(e.engine)
    )

    private fun toFeedback(e: AletheiaOutboxEntry) = AletheiaFeedbackPayload(
        messageId = e.messageId,
        rating = AletheiaFeedbackRating.entries.firstOrNull { it.apiValue == e.rating }
            ?: AletheiaFeedbackRating.PARTIAL,
        note = e.note,
        prompt = e.prompt,
        answer = e.answer,
        mode = modeOf(e),
        locale = e.locale ?: "en",
        engine = AletheiaEngine.fromApi(e.engine)
    )

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
        prefs.edit().putString(KEY, gson.toJson(list)).commit()
    }

    companion object {
        private const val TAG = "AletheiaOutbox"
        const val PREFS = "aletheia_outbox"
        private const val KEY = "entries"
        const val MAX_ITEMS = 200
    }
}
