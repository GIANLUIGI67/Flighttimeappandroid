@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class OvertimeListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_YEAR = "year"
        const val EXTRA_MONTH = "month"
    }

    private lateinit var storage: SalaryStorage
    private var month: SalaryMonth = SalaryMonth.forCurrentMonth()
    private lateinit var adapter: OvertimeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overtime_list)

        storage = SalaryStorage(this)
        val y = intent.getIntExtra(EXTRA_YEAR, month.year)
        val m = intent.getIntExtra(EXTRA_MONTH, month.month)
        month = storage.selectOrCreateMonth(y, m)

        findViewById<TextView>(R.id.overtimeClose).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.overtimeRecycler)
        adapter = OvertimeAdapter(emptyList()) { log -> openEditor(log) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        updateList()
    }

    private fun updateList() {
        val sorted = month.overtimeLogs.sortedByDescending { it.date }
        adapter.update(sorted)
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val deleted = data.getBooleanExtra(OvertimeEditorActivity.EXTRA_DELETED, false)
        val id = data.getStringExtra(OvertimeEditorActivity.EXTRA_ID)

        if (deleted && id != null) {
            val updatedLogs = month.overtimeLogs.filterNot { it.id == id }
            month = month.copy(overtimeLogs = updatedLogs, overtimeDays = updatedLogs.size).syncCountsAlways()
            storage.upsert(month)
            updateList()
            return@registerForActivityResult
        }

        val dateRaw = data.getStringExtra(OvertimeEditorActivity.EXTRA_DATE) ?: return@registerForActivityResult
        val updated = OvertimeLog(id = id ?: java.util.UUID.randomUUID().toString(), date = java.time.LocalDate.parse(dateRaw))
        val list = month.overtimeLogs.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        month = month.copy(overtimeLogs = list, overtimeDays = list.size).syncCountsAlways()
        storage.upsert(month)
        updateList()
    }

    private fun openEditor(existing: OvertimeLog?) {
        val intent = Intent(this, OvertimeEditorActivity::class.java)
        if (existing != null) {
            intent.putExtra(OvertimeEditorActivity.EXTRA_ID, existing.id)
            intent.putExtra(OvertimeEditorActivity.EXTRA_DATE, existing.date.toString())
        }
        editorLauncher.launch(intent)
    }
}
