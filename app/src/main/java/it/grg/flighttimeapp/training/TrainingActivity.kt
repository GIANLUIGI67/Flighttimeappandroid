package it.grg.flighttimeapp.training

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class TrainingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_training)

        findViewById<View>(R.id.trainingBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.trainingTitle).text = getString(R.string.training_title)

        val list = findViewById<LinearLayout>(R.id.trainingList)
        val inflater = LayoutInflater.from(this)

        fun addRow(title: String, isRed: Boolean = false, onClick: () -> Unit) {
            val row = inflater.inflate(R.layout.item_training_row, list, false)
            val titleView = row.findViewById<TextView>(R.id.trainingRowTitle)
            titleView.text = title
            if (isRed) {
                titleView.setTextColor(getColor(R.color.iosRed))
            }
            row.setOnClickListener { onClick() }
            list.addView(row)
        }

        addRow(getString(R.string.a320_memory_items_title), isRed = true) {
            startActivity(Intent(this, MemoryItemsActivity::class.java))
        }
        addRow(getString(R.string.a330_memory_items_title), isRed = true) {
            startActivity(Intent(this, A330MemoryItemsActivity::class.java))
        }
        addRow(getString(R.string.a380_memory_items_title), isRed = true) {
            startActivity(Intent(this, A380MemoryItemsActivity::class.java))
        }
        addRow(getString(R.string.b737_memory_items_title), isRed = true) { toastComingSoon() }
        addRow(getString(R.string.b777_memory_items_title), isRed = true) { toastComingSoon() }
        addRow(getString(R.string.b787_memory_items_title), isRed = true) {
            startActivity(Intent(this, B787MemoryItemsActivity::class.java))
        }

        addRow(getString(R.string.a320_question_bank_title)) {
            startActivity(Intent(this, QuestionBankActivity::class.java))
        }
        addRow(getString(R.string.a320_cabin_crew_question_bank_title)) {
            val intent = Intent(this, QuestionBankActivity::class.java).apply {
                putExtra(QuestionBankActivity.EXTRA_DB_FILE, "ccmm_a320_cabin_crew.sqlite")
                putExtra(QuestionBankActivity.EXTRA_TITLE, getString(R.string.a320_cabin_crew_question_bank_title))
            }
            startActivity(intent)
        }
        addRow(getString(R.string.a330_question_bank_title)) {
            val intent = Intent(this, QuestionBankActivity::class.java).apply {
                putExtra(QuestionBankActivity.EXTRA_DB_FILE, "ccmm_a330_technical_questions.sqlite")
                putExtra(QuestionBankActivity.EXTRA_TITLE, getString(R.string.a330_question_bank_title))
            }
            startActivity(intent)
        }
        addRow(getString(R.string.a380_question_bank_title)) { toastComingSoon() }
        addRow(getString(R.string.b737_question_bank_title)) { toastComingSoon() }
        addRow(getString(R.string.b777_question_bank_title)) { toastComingSoon() }
        addRow(getString(R.string.b787_question_bank_title)) { toastComingSoon() }

        addRow(getString(R.string.support_app_donate_title)) {
            startActivity(Intent(this, DonateActivity::class.java))
        }
    }

    private fun toastComingSoon() {
        Toast.makeText(this, getString(R.string.training_coming_soon), Toast.LENGTH_SHORT).show()
    }
}
