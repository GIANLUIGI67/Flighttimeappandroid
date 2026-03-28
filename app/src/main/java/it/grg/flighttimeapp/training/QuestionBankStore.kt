package it.grg.flighttimeapp.training

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class QuestionBankStore(
    private val context: Context,
    private val dbFileName: String = DEFAULT_DB_FILE
) {

    private enum class Schema(
        val tableName: String,
        val questionColumn: String,
        val correctColumn: String
    ) {
        LEGACY("questions", "question", "correct"),
        CABIN_CREW("questions", "question_text", "correct_answer"),
        A330_TECHNICAL("technical_questions", "question_text", "correct_answer")
    }

    data class QBQuestion(
        val id: Int,
        val question: String,
        val optionA: String,
        val optionB: String,
        val optionC: String,
        val optionD: String,
        val correct: String
    )

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val seenKey: String = "${KEY_SEEN_PREFIX}_$dbFileName"
    private var db: SQLiteDatabase? = null
    private val schema: Schema = when (dbFileName) {
        "ccmm_a320_cabin_crew.sqlite" -> Schema.CABIN_CREW
        "ccmm_a330_technical_questions.sqlite" -> Schema.A330_TECHNICAL
        else -> Schema.LEGACY
    }

    fun newQuiz(size: Int = 30): List<QBQuestion> {
        ensureDb()
        val seen = loadSeenIds().toMutableSet()
        val total = totalCount()
        if (total - seen.size < size) {
            seen.clear()
            saveSeenIds(seen)
        }
        val questions = fetchRandomUnseen(size, seen)
        if (questions.isNotEmpty()) {
            questions.forEach { seen.add(it.id) }
            saveSeenIds(seen)
        }
        return questions
    }

    fun searchQuestions(query: String, limit: Int = 50): List<QBQuestion> {
        ensureDb()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val sql = """
            SELECT id, ${schema.questionColumn}, option_a, option_b, option_c, option_d, ${schema.correctColumn}
            FROM ${schema.tableName}
            WHERE ${schema.questionColumn} LIKE ?
            ORDER BY id DESC
            LIMIT ?
        """.trimIndent()
        val q = "%$trimmed%"
        val cursor = db!!.rawQuery(sql, arrayOf(q, limit.toString()))
        val out = mutableListOf<QBQuestion>()
        cursor.use {
            while (it.moveToNext()) {
                out.add(readQuestion(it))
            }
        }
        return out
    }

    private fun totalCount(): Int {
        val cursor = db!!.rawQuery("SELECT COUNT(*) FROM ${schema.tableName}", null)
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    private fun fetchRandomUnseen(limit: Int, seen: Set<Int>): List<QBQuestion> {
        val out = mutableListOf<QBQuestion>()
        var attempts = 0
        while (out.size < limit && attempts < 5) {
            val batch = fetchRandomBatch(limit * 2)
            batch.forEach { q ->
                if (!seen.contains(q.id) && out.none { it.id == q.id }) {
                    out.add(q)
                }
            }
            attempts += 1
        }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    private fun fetchRandomBatch(limit: Int): List<QBQuestion> {
        val sql = """
            SELECT id, ${schema.questionColumn}, option_a, option_b, option_c, option_d, ${schema.correctColumn}
            FROM ${schema.tableName}
            ORDER BY RANDOM()
            LIMIT ?
        """.trimIndent()
        val cursor = db!!.rawQuery(sql, arrayOf(limit.toString()))
        val out = mutableListOf<QBQuestion>()
        cursor.use {
            while (it.moveToNext()) {
                out.add(readQuestion(it))
            }
        }
        return out
    }

    private fun readQuestion(c: android.database.Cursor): QBQuestion {
        return QBQuestion(
            id = c.getInt(0),
            question = c.getString(1),
            optionA = c.getString(2) ?: "",
            optionB = c.getString(3) ?: "",
            optionC = c.getString(4) ?: "",
            optionD = c.getString(5) ?: "",
            correct = (c.getString(6) ?: "").uppercase()
        )
    }

    private fun ensureDb() {
        if (db != null) return
        val dbFile = File(context.filesDir, dbFileName)
        if (!dbFile.exists()) {
            context.assets.open("training/db/$dbFileName").use { input ->
                dbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun loadSeenIds(): Set<Int> {
        val raw = prefs.getStringSet(seenKey, emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun saveSeenIds(seen: Set<Int>) {
        prefs.edit().putStringSet(seenKey, seen.map { it.toString() }.toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "qb_store"
        private const val KEY_SEEN_PREFIX = "seen_ids"
        private const val DEFAULT_DB_FILE = "qb_a320.sqlite"
    }
}
