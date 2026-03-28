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

class A380MemoryItemsActivity : AppCompatActivity() {

    data class MemoryItem(
        val titleRes: Int,
        val memoryTextBase: String,
        val procedureTextBase: String,
        val memoryAudioBase: String,
        val procedureAudioBase: String
    )

    private val items = listOf(
        MemoryItem(
            titleRes = R.string.a380_loss_of_braking_title,
            memoryTextBase = "A380_LOSS_OF_BRAKING_MEMORY",
            procedureTextBase = "A380_LOSS_OF_BRAKING_PROCEDURE",
            memoryAudioBase = "A380_LOSS_OF_BRAKING_MEMORY",
            procedureAudioBase = "A380_LOSS_OF_BRAKING_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_misc_emer_descent_title,
            memoryTextBase = "A380_MISC_EMER_DESCENT_MEMORY",
            procedureTextBase = "A380_MISC_EMER_DESCENT_PROCEDURE",
            memoryAudioBase = "A380_MISC_EMER_DESCENT_MEMORY",
            procedureAudioBase = "A380_MISC_EMER_DESCENT_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_stall_recovery_title,
            memoryTextBase = "A380_STALL_RECOVERY_MEMORY",
            procedureTextBase = "A380_STALL_RECOVERY_PROCEDURE",
            memoryAudioBase = "A380_STALL_RECOVERY_MEMORY",
            procedureAudioBase = "A380_STALL_RECOVERY_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_stall_warning_liftoff_title,
            memoryTextBase = "A380_STALL_WARNING_AT_LIFTOFF_MEMORY",
            procedureTextBase = "A380_STALL_WARNING_AT_LIFTOFF_PROCEDURE",
            memoryAudioBase = "A380_STALL_WARNING_AT_LIFTOFF_MEMORY",
            procedureAudioBase = "A380_STALL_WARNING_AT_LIFTOFF_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_unreliable_speed_title,
            memoryTextBase = "A380_UNRELIABLE_SPEED_MEMORY",
            procedureTextBase = "A380_UNRELIABLE_SPEED_INDICATION_PROCEDURE",
            memoryAudioBase = "A380_UNRELIABLE_SPEED_MEMORY",
            procedureAudioBase = "A380_UNRELIABLE_SPEED_INDICATION_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_taws_caution_title,
            memoryTextBase = "A380_TAWS_CAUTION_MEMORY",
            procedureTextBase = "A380_TAWS_CAUTION_PROCEDURE",
            memoryAudioBase = "A380_TAWS_CAUTION_MEMORY",
            procedureAudioBase = "A380_TAWS_CAUTION_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_taws_warning_title,
            memoryTextBase = "A380_TAWS_WARNING_MEMORY",
            procedureTextBase = "A380_TAWS_WARNING_PROCEDURE",
            memoryAudioBase = "A380_TAWS_WARNING_MEMORY",
            procedureAudioBase = "A380_TAWS_WARNING_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_tcas_caution_title,
            memoryTextBase = "A380_TCAS_CAUTION_TRAFFIC_ADVISORY_MEMORY",
            procedureTextBase = "A380_TCAS_CAUTION_TRAFFIC_ADVISORY_PROCEDURE",
            memoryAudioBase = "A380_TCAS_CAUTION_TRAFFIC_ADVISORY_MEMORY",
            procedureAudioBase = "A380_TCAS_CAUTION_TRAFFIC_ADVISORY_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_tcas_warning_title,
            memoryTextBase = "A380_TCAS_WARNING_RESOLUTION_ADVISORY_MEMORY",
            procedureTextBase = "A380_TCAS_WARNING_RESOLUTION_ADVISORY_PROCEDURE",
            memoryAudioBase = "A380_TCAS_WARNING_RESOLUTION_ADVISORY_MEMORY",
            procedureAudioBase = "A380_TCAS_WARNING_RESOLUTION_ADVISORY_PROCEDURE"
        ),
        MemoryItem(
            titleRes = R.string.a380_windshear_reactive_title,
            memoryTextBase = "A380_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_MEMORY",
            procedureTextBase = "A380_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_PROCEDURE",
            memoryAudioBase = "A380_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_MEMORY",
            procedureAudioBase = "A380_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_PROCEDURE"
        )
    )

    private lateinit var audioPlayer: TrainingAudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_items)

        audioPlayer = TrainingAudioPlayer(this)

        findViewById<View>(R.id.memoryBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.memoryTitle).text = getString(R.string.a380_memory_items_title)
        findViewById<TextView>(R.id.memoryHeader).text = getString(R.string.a380_memory_items_header)

        val container = findViewById<LinearLayout>(R.id.memoryItemsContainer)
        val inflater = LayoutInflater.from(this)

        findViewById<Button>(R.id.btnPlayAllMemory).setOnClickListener {
            audioPlayer.playAll(items.map { it.memoryAudioBase }, loop = false)
        }
        findViewById<Button>(R.id.btnLoopAllMemory).setOnClickListener {
            audioPlayer.playAll(items.map { it.memoryAudioBase }, loop = true)
        }
        findViewById<Button>(R.id.btnPlayAllProcedure).setOnClickListener {
            audioPlayer.playAll(items.map { it.procedureAudioBase }, loop = false)
        }
        findViewById<Button>(R.id.btnLoopAllProcedure).setOnClickListener {
            audioPlayer.playAll(items.map { it.procedureAudioBase }, loop = true)
        }
        findViewById<Button>(R.id.btnStopAll).setOnClickListener {
            audioPlayer.stop()
        }

        items.forEach { item ->
            val card = inflater.inflate(R.layout.item_memory_card, container, false)
            card.findViewById<TextView>(R.id.memoryItemTitle).text = getString(item.titleRes)

            card.findViewById<Button>(R.id.btnReadMemory).setOnClickListener {
                openText(title = getString(R.string.training_read_memory), baseName = item.memoryTextBase)
            }
            card.findViewById<Button>(R.id.btnReadProcedure).setOnClickListener {
                openText(title = getString(R.string.training_read_procedure), baseName = item.procedureTextBase)
            }

            card.findViewById<Button>(R.id.btnPlayMemory).setOnClickListener {
                audioPlayer.playSingle(item.memoryAudioBase, loop = false)
            }
            card.findViewById<Button>(R.id.btnPlayProcedure).setOnClickListener {
                audioPlayer.playSingle(item.procedureAudioBase, loop = false)
            }

            card.findViewById<Button>(R.id.btnLoopMemory).setOnClickListener {
                audioPlayer.playSingle(item.memoryAudioBase, loop = true)
            }
            card.findViewById<Button>(R.id.btnLoopProcedure).setOnClickListener {
                audioPlayer.playSingle(item.procedureAudioBase, loop = true)
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
