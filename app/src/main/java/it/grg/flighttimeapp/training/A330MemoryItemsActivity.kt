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

class A330MemoryItemsActivity : AppCompatActivity() {

    data class MemoryItem(
        val title: String,
        val memoryTextBase: String,
        val procedureTextBase: String,
        val memoryAudioBase: String,
        val procedureAudioBase: String
    )

    private val items = listOf(
        MemoryItem(
            title = "LOSS OF BRAKING",
            memoryTextBase = "A330_LOSS_OF_BRAKING_MEMORY",
            procedureTextBase = "A330_LOSS_OF_BRAKING_PROCEDURE",
            memoryAudioBase = "A330_LOSS_OF_BRAKING_MEMORY",
            procedureAudioBase = "A330_LOSS_OF_BRAKING_PROCEDURE"
        ),
        MemoryItem(
            title = "EMERGENCY DESCENT",
            memoryTextBase = "A330_EMERGENCY_DESCENT_MEMORY",
            procedureTextBase = "A330_EMERGENCY_DESCENT_PROCEDURE",
            memoryAudioBase = "A330_EMERGENCY_DESCENT_MEMORY",
            procedureAudioBase = "A330_EMERGENCY_DESCENT_PROCEDURE"
        ),
        MemoryItem(
            title = "STALL RECOVERY",
            memoryTextBase = "A330_STALL_RECOVERY_MEMORY",
            procedureTextBase = "A330_STALL_RECOVERY_PROCEDURE",
            memoryAudioBase = "A330_STALL_RECOVERY_MEMORY",
            procedureAudioBase = "A330_STALL_RECOVERY_PROCEDURE"
        ),
        MemoryItem(
            title = "STALL WARNING AT LIFT-OFF",
            memoryTextBase = "A330_STALL_WARNING_AT_LIFTOFF_MEMORY",
            procedureTextBase = "A330_STALL_WARNING_AT_LIFTOFF_PROCEDURE",
            memoryAudioBase = "A330_STALL_WARNING_AT_LIFTOFF_MEMORY",
            procedureAudioBase = "A330_STALL_WARNING_AT_LIFTOFF_PROCEDURE"
        ),
        MemoryItem(
            title = "UNRELIABLE SPEED INDICATION",
            memoryTextBase = "A330_UNRELIABLE_SPEED_INDICATION_MEMORY",
            procedureTextBase = "A330_UNRELIABLE_SPEED_INDICATION_PROCEDURE",
            memoryAudioBase = "A330_UNRELIABLE_SPEED_INDICATION_MEMORY",
            procedureAudioBase = "A330_UNRELIABLE_SPEED_INDICATION_PROCEDURE"
        ),
        MemoryItem(
            title = "TAWS CAUTION",
            memoryTextBase = "A330_TAWS_CAUTION_MEMORY",
            procedureTextBase = "A330_TAWS_CAUTION_PROCEDURE",
            memoryAudioBase = "A330_TAWS_CAUTION_MEMORY",
            procedureAudioBase = "A330_TAWS_CAUTION_PROCEDURE"
        ),
        MemoryItem(
            title = "TAWS WARNING",
            memoryTextBase = "A330_TAWS_WARNING_MEMORY",
            procedureTextBase = "A330_TAWS_WARNING_PROCEDURE",
            memoryAudioBase = "A330_TAWS_WARNING_MEMORY",
            procedureAudioBase = "A330_TAWS_WARNING_PROCEDURE"
        ),
        MemoryItem(
            title = "TCAS CAUTION/TRAFFIC ADVISORY",
            memoryTextBase = "A330_TCAS_CAUTION_TRAFFIC_ADVISORY_MEMORY",
            procedureTextBase = "A330_TCAS_CAUTION_TRAFFIC_ADVISORY_PROCEDURE",
            memoryAudioBase = "A330_TCAS_CAUTION_TRAFFIC_ADVISORY_MEMORY",
            procedureAudioBase = "A330_TCAS_CAUTION_TRAFFIC_ADVISORY_PROCEDURE"
        ),
        MemoryItem(
            title = "TCAS WARNING/RESOLUTION ADVISORY",
            memoryTextBase = "A330_TCAS_WARNING_RESOLUTION_ADVISORY_MEMORY",
            procedureTextBase = "A330_TCAS_WARNING_RESOLUTION_ADVISORY_PROCEDURE",
            memoryAudioBase = "A330_TCAS_WARNING_RESOLUTION_ADVISORY_MEMORY",
            procedureAudioBase = "A330_TCAS_WARNING_RESOLUTION_ADVISORY_PROCEDURE"
        ),
        MemoryItem(
            title = "WINDSHEAR WARNING/REACTIVE WINDSHEAR",
            memoryTextBase = "A330_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_MEMORY",
            procedureTextBase = "A330_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_PROCEDURE",
            memoryAudioBase = "A330_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_MEMORY",
            procedureAudioBase = "A330_WINDSHEAR_WARNING_REACTIVE_WINDSHEAR_PROCEDURE"
        )
    )

    private lateinit var audioPlayer: TrainingAudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_items)

        audioPlayer = TrainingAudioPlayer(this)

        findViewById<View>(R.id.memoryBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.memoryTitle).text = getString(R.string.a330_memory_items_title)
        findViewById<TextView>(R.id.memoryHeader).text = getString(R.string.a330_memory_items_header)

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
            card.findViewById<TextView>(R.id.memoryItemTitle).text = item.title

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
