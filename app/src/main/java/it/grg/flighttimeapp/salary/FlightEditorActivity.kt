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
import com.google.android.material.button.MaterialButtonToggleGroup
import it.grg.flighttimeapp.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class FlightEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "flight_id"
        const val EXTRA_DATE = "flight_date"
        const val EXTRA_ROUTE = "flight_route"
        const val EXTRA_MINUTES = "flight_minutes"
        const val EXTRA_SCHEDULED = "flight_scheduled"
        const val EXTRA_DELETED = "flight_deleted"
    }

    private lateinit var dateText: TextView
    private lateinit var routeInput: EditText
    private lateinit var hInput: EditText
    private lateinit var mInput: EditText
    private lateinit var toggle: MaterialButtonToggleGroup
    private lateinit var deleteBtn: TextView

    private var flightId: String? = null
    private var selectedDate: LocalDate = LocalDate.now()
    private var isScheduled: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flight_editor)

        dateText = findViewById(R.id.flightEditorDate)
        routeInput = findViewById(R.id.flightEditorRoute)
        hInput = findViewById(R.id.flightEditorH)
        mInput = findViewById(R.id.flightEditorM)
        toggle = findViewById(R.id.flightEditorToggle)
        deleteBtn = findViewById(R.id.flightEditorDelete)

        findViewById<TextView>(R.id.flightEditorCancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.flightEditorSave).setOnClickListener { saveAndReturn() }

        flightId = intent.getStringExtra(EXTRA_ID)
        val rawDate = intent.getStringExtra(EXTRA_DATE)
        val rawRoute = intent.getStringExtra(EXTRA_ROUTE) ?: ""
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        isScheduled = intent.getBooleanExtra(EXTRA_SCHEDULED, true)

        if (!rawDate.isNullOrBlank()) {
            selectedDate = LocalDate.parse(rawDate)
        }

        val h = minutes / 60
        val m = minutes % 60

        routeInput.setText(rawRoute)
        hInput.setText(h.toString())
        mInput.setText(String.format("%02d", m))

        if (isScheduled) {
            toggle.check(R.id.flightEditorScheduled)
        } else {
            toggle.check(R.id.flightEditorActual)
        }

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isScheduled = checkedId == R.id.flightEditorScheduled
        }

        dateText.setOnClickListener { openDatePicker() }
        updateDateText()

        deleteBtn.visibility = if (flightId == null) android.view.View.GONE else android.view.View.VISIBLE
        deleteBtn.setOnClickListener { deleteAndReturn() }
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
        val hh = hInput.text.toString().toIntOrNull() ?: 0
        val mm = mInput.text.toString().toIntOrNull() ?: 0
        val totalMinutes = (hh * 60) + mm
        if (totalMinutes <= 0) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val data = Intent().apply {
            putExtra(EXTRA_ID, flightId)
            putExtra(EXTRA_DATE, selectedDate.toString())
            putExtra(EXTRA_ROUTE, routeInput.text.toString().trim())
            putExtra(EXTRA_MINUTES, totalMinutes)
            putExtra(EXTRA_SCHEDULED, isScheduled)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun deleteAndReturn() {
        val data = Intent().apply {
            putExtra(EXTRA_ID, flightId)
            putExtra(EXTRA_DELETED, true)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
