package it.grg.flighttimeapp.training

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class B787MemoryItemsActivity : AppCompatActivity() {

    data class MemoryItem(
        val titleRes: Int,
        val memoryTextBase: String,
        val memoryAudioBase: String
    )

    private val items = listOf(
        MemoryItem(
            titleRes = R.string.b787_aborted_engine_start_title,
            memoryTextBase = "B787_ABORTED_ENGINE_START_MEMORY",
            memoryAudioBase = "B787_ABORTED_ENGINE_START_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_airspeed_unreliable_title,
            memoryTextBase = "B787_AIRSPEED_UNRELIABLE_MEMORY",
            memoryAudioBase = "B787_AIRSPEED_UNRELIABLE_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_cabin_altitude_title,
            memoryTextBase = "B787_CABIN_ALTITUDE_MEMORY",
            memoryAudioBase = "B787_CABIN_ALTITUDE_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_dual_engine_fail_title,
            memoryTextBase = "B787_DUAL_ENGINE_FAIL_MEMORY",
            memoryAudioBase = "B787_DUAL_ENGINE_FAIL_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_engine_autostart_title,
            memoryTextBase = "B787_ENGINE_AUTOSTART_MEMORY",
            memoryAudioBase = "B787_ENGINE_AUTOSTART_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_engine_limit_exceed_title,
            memoryTextBase = "B787_ENGINE_LIMIT_EXCEED_MEMORY",
            memoryAudioBase = "B787_ENGINE_LIMIT_EXCEED_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_engine_severe_damage_title,
            memoryTextBase = "B787_ENGINE_SEVERE_DAMAGE_MEMORY",
            memoryAudioBase = "B787_ENGINE_SEVERE_DAMAGE_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_engine_surge_title,
            memoryTextBase = "B787_ENGINE_SURGE_MEMORY",
            memoryAudioBase = "B787_ENGINE_SURGE_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_fire_engine_title,
            memoryTextBase = "B787_FIRE_ENGINE_MEMORY",
            memoryAudioBase = "B787_FIRE_ENGINE_MEMORY"
        ),
        MemoryItem(
            titleRes = R.string.b787_stabilizer_title,
            memoryTextBase = "B787_STABILIZER_MEMORY",
            memoryAudioBase = "B787_STABILIZER_MEMORY"
        )
    )

    private lateinit var audioPlayer: TrainingAudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_items)

        audioPlayer = TrainingAudioPlayer(this)

        findViewById<View>(R.id.memoryBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.memoryTitle).text = getString(R.string.b787_memory_items_title)
        findViewById<TextView>(R.id.memoryHeader).text = getString(R.string.b787_memory_items_header)

        findViewById<Button>(R.id.btnPlayAllProcedure).visibility = View.GONE
        findViewById<Button>(R.id.btnLoopAllProcedure).visibility = View.GONE

        val container = findViewById<LinearLayout>(R.id.memoryItemsContainer)
        val inflater = LayoutInflater.from(this)

        findViewById<Button>(R.id.btnPlayAllMemory).setOnClickListener {
            audioPlayer.playAll(items.map { it.memoryAudioBase }, loop = false)
        }
        findViewById<Button>(R.id.btnLoopAllMemory).setOnClickListener {
            audioPlayer.playAll(items.map { it.memoryAudioBase }, loop = true)
        }
        findViewById<Button>(R.id.btnStopAll).setOnClickListener {
            audioPlayer.stop()
        }

        items.forEach { item ->
            val card = inflater.inflate(R.layout.item_memory_card_memory_only, container, false)
            card.findViewById<TextView>(R.id.memoryItemTitle).text = getString(item.titleRes)

            card.findViewById<Button>(R.id.btnReadMemory).setOnClickListener {
                openText(title = getString(R.string.training_read_memory), baseName = item.memoryTextBase)
            }

            card.findViewById<Button>(R.id.btnPlayMemory).setOnClickListener {
                audioPlayer.playSingle(item.memoryAudioBase, loop = false)
            }

            card.findViewById<Button>(R.id.btnLoopMemory).setOnClickListener {
                audioPlayer.playSingle(item.memoryAudioBase, loop = true)
            }

            card.findViewById<Button>(R.id.btnStopSingle).setOnClickListener {
                audioPlayer.stop()
            }

            container.addView(card)
        }
    }

    override fun onStop() {
        super.onStop()
        audioPlayer.stop()
    }

    private fun openText(title: String, baseName: String) {
        val intent = Intent(this, TrainingTextActivity::class.java)
        intent.putExtra(TrainingTextActivity.EXTRA_TITLE, title)
        intent.putExtra(TrainingTextActivity.EXTRA_BASE_NAME, baseName)
        startActivity(intent)
    }
}
