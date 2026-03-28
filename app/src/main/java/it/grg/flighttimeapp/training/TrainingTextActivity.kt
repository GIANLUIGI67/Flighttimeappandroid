package it.grg.flighttimeapp.training

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R
import java.io.BufferedReader
import java.io.InputStreamReader

class TrainingTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_training_text)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.training_read_memory)
        val baseName = intent.getStringExtra(EXTRA_BASE_NAME) ?: ""

        findViewById<TextView>(R.id.textTitle).text = title
        findViewById<View>(R.id.textBack).setOnClickListener { finish() }
        findViewById<View>(R.id.textDone).setOnClickListener { finish() }

        val content = if (baseName.isNotBlank()) {
            loadTextFromAssets(baseName)
        } else {
            getString(R.string.training_text_missing)
        }

        findViewById<TextView>(R.id.trainingText).text = content
    }

    private fun loadTextFromAssets(baseName: String): String {
        val rtfPath = "training/text/$baseName.rtf"
        val txtPath = "training/text/$baseName.txt"
        return try {
            assets.open(rtfPath).use { input ->
                val raw = BufferedReader(InputStreamReader(input)).readText()
                rtfToText(raw)
            }
        } catch (_: Exception) {
            try {
                assets.open(txtPath).use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }
            } catch (_: Exception) {
                getString(R.string.training_text_missing)
            }
        }
    }

    private fun rtfToText(rtf: String): String {
        var text = rtf
        text = text.replace("\\par", "\n")
        text = text.replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
        text = text.replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        text = text.replace(Regex("[{}]"), "")
        return text.trim()
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BASE_NAME = "extra_base_name"
    }
}
