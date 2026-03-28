@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class LayoverEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "layover_id"
        const val EXTRA_DATE = "layover_date"
        const val EXTRA_LOCATION = "layover_location"
        const val EXTRA_KIND = "layover_kind"
        const val EXTRA_DELETED = "layover_deleted"
    }

    private lateinit var dateText: TextView
    private lateinit var locationInput: EditText
    private lateinit var deleteBtn: TextView
    private var selectedDate: LocalDate = LocalDate.now()
    private var logId: String? = null
    private var kind: LayoverKind = LayoverKind.DOMESTIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layover_editor)

        dateText = findViewById(R.id.layoverEditDate)
        locationInput = findViewById(R.id.layoverEditLocation)
        deleteBtn = findViewById(R.id.layoverEditDelete)

        logId = intent.getStringExtra(EXTRA_ID)
        val rawDate = intent.getStringExtra(EXTRA_DATE)
        if (!rawDate.isNullOrBlank()) {
            selectedDate = LocalDate.parse(rawDate)
        }
        locationInput.setText(intent.getStringExtra(EXTRA_LOCATION) ?: "")
        kind = LayoverKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: LayoverKind.DOMESTIC.name)

        findViewById<TextView>(R.id.layoverEditCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.layoverEditSave).setOnClickListener { saveAndReturn() }
        deleteBtn.visibility = if (logId == null) android.view.View.GONE else android.view.View.VISIBLE
        deleteBtn.setOnClickListener { deleteAndReturn() }

        dateText.setOnClickListener { openDatePicker() }
        updateDateText()
    }

    private fun updateDateText() {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        dateText.text = selectedDate.format(f)
    }

    private fun openDatePicker() {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                selectedDate = LocalDate.of(y, m + 1, d)
                updateDateText()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    private fun saveAndReturn() {
        val data = Intent().apply {
            putExtra(EXTRA_ID, logId)
            putExtra(EXTRA_DATE, selectedDate.toString())
            putExtra(EXTRA_LOCATION, locationInput.text.toString().trim())
            putExtra(EXTRA_KIND, kind.name)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun deleteAndReturn() {
        val data = Intent().apply {
            putExtra(EXTRA_ID, logId)
            putExtra(EXTRA_DELETED, true)
            putExtra(EXTRA_KIND, kind.name)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
