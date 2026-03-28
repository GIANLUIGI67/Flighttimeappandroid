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

class LayoverListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_YEAR = "year"
        const val EXTRA_MONTH = "month"
        const val EXTRA_KIND = "kind"
    }

    private lateinit var storage: SalaryStorage
    private var month: SalaryMonth = SalaryMonth.forCurrentMonth()
    private lateinit var adapter: LayoverAdapter
    private var kind: LayoverKind = LayoverKind.DOMESTIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layover_list)

        storage = SalaryStorage(this)
        val y = intent.getIntExtra(EXTRA_YEAR, month.year)
        val m = intent.getIntExtra(EXTRA_MONTH, month.month)
        kind = LayoverKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: LayoverKind.DOMESTIC.name)
        month = storage.selectOrCreateMonth(y, m)

        findViewById<TextView>(R.id.layoverClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.layoverTitle).text = if (kind == LayoverKind.DOMESTIC)
            getString(R.string.salary_list_layover_domestic_title) else getString(R.string.salary_list_layover_international_title)

        val recycler = findViewById<RecyclerView>(R.id.layoverRecycler)
        adapter = LayoverAdapter(emptyList()) { log -> openEditor(log) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        updateList()
    }

    private fun updateList() {
        val filtered = month.layoverLogs.filter { it.kind == kind }.sortedByDescending { it.date }
        adapter.update(filtered)
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val deleted = data.getBooleanExtra(LayoverEditorActivity.EXTRA_DELETED, false)
        val id = data.getStringExtra(LayoverEditorActivity.EXTRA_ID)

        if (deleted && id != null) {
            val updatedLogs = month.layoverLogs.filterNot { it.id == id }
            val domestic = updatedLogs.count { it.kind == LayoverKind.DOMESTIC }
            val international = updatedLogs.count { it.kind == LayoverKind.INTERNATIONAL }
            month = month.copy(
                layoverLogs = updatedLogs,
                layoverDomesticDays = domestic,
                layoverInternationalDays = international
            ).syncCountsAlways()
            storage.upsert(month)
            updateList()
            return@registerForActivityResult
        }

        val dateRaw = data.getStringExtra(LayoverEditorActivity.EXTRA_DATE) ?: return@registerForActivityResult
        val location = data.getStringExtra(LayoverEditorActivity.EXTRA_LOCATION) ?: ""
        val updated = LayoverLog(
            id = id ?: java.util.UUID.randomUUID().toString(),
            date = java.time.LocalDate.parse(dateRaw),
            location = location,
            kind = kind
        )
        val list = month.layoverLogs.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        val domestic = list.count { it.kind == LayoverKind.DOMESTIC }
        val international = list.count { it.kind == LayoverKind.INTERNATIONAL }
        month = month.copy(
            layoverLogs = list,
            layoverDomesticDays = domestic,
            layoverInternationalDays = international
        ).syncCountsAlways()
        storage.upsert(month)
        updateList()
    }

    private fun openEditor(existing: LayoverLog?) {
        val intent = Intent(this, LayoverEditorActivity::class.java)
        intent.putExtra(LayoverEditorActivity.EXTRA_KIND, kind.name)
        if (existing != null) {
            intent.putExtra(LayoverEditorActivity.EXTRA_ID, existing.id)
            intent.putExtra(LayoverEditorActivity.EXTRA_DATE, existing.date.toString())
            intent.putExtra(LayoverEditorActivity.EXTRA_LOCATION, existing.location)
        }
        editorLauncher.launch(intent)
    }
}
