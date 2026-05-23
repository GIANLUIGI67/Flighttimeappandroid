package it.grg.flighttimeapp.training

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import it.grg.flighttimeapp.R
import java.util.Locale

class TrainingAudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var queue: List<Int> = emptyList()
    private var index: Int = 0
    private var loopAll: Boolean = false

    fun playSingle(baseName: String, loop: Boolean) {
        stop()
        val resId = resolveResId(baseName)
        if (resId == 0) {
            toastMissing(baseName)
            return
        }
        try {
            player = MediaPlayer.create(context, resId)
            player?.isLooping = loop
            player?.start()
        } catch (e: Exception) {
            toastMissing(baseName)
        }
    }

    fun playAll(baseNames: List<String>, loop: Boolean) {
        stop()
        val ids = baseNames.map { resolveResId(it) }.filter { it != 0 }
        if (ids.isEmpty()) {
            toastMissing("audio list")
            return
        }
        queue = ids
        index = 0
        loopAll = loop
        playNextInQueue()
    }

    fun stop() {
        player?.setOnCompletionListener(null)
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) { }
        player = null
        queue = emptyList()
        index = 0
        loopAll = false
    }

    private fun playNextInQueue() {
        if (queue.isEmpty()) return
        if (index >= queue.size) {
            if (loopAll) {
                index = 0
            } else {
                stop()
                return
            }
        }
        val resId = queue[index]
        try {
            player = MediaPlayer.create(context, resId)
            player?.setOnCompletionListener {
                index += 1
                playNextInQueue()
            }
            player?.start()
        } catch (e: Exception) {
            index += 1
            playNextInQueue()
        }
    }

    private fun resolveResId(baseName: String): Int {
        // Resources in Android must be lowercase.
        val resName = baseName.lowercase(Locale.US)
            .replace("-", "_")
            .trim()
        return AUDIO_RESOURCES[resName] ?: 0
    }

    private fun toastMissing(name: String) {
        Toast.makeText(context, "Missing audio: $name", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val AUDIO_RESOURCES: Map<String, Int> = mapOf(
            "a320_emer_descent_memory" to R.raw.a320_emer_descent_memory,
            "a320_emer_descent_procedure" to R.raw.a320_emer_descent_procedure,
            "a320_loss_of_braking_memory" to R.raw.a320_loss_of_braking_memory,
            "a320_loss_of_braking_procedure" to R.raw.a320_loss_of_braking_procedure,
            "a320_stall_recovery_memory" to R.raw.a320_stall_recovery_memory,
            "a320_stall_recovery_procedure" to R.raw.a320_stall_recovery_procedure,
            "a320_stall_warning_at_lift_off_memory" to R.raw.a320_stall_warning_at_lift_off_memory,
            "a320_stall_warning_at_lift_off_procedure" to R.raw.a320_stall_warning_at_lift_off_procedure,
            "a320_taws_caution_memory" to R.raw.a320_taws_caution_memory,
            "a320_taws_caution_procedure" to R.raw.a320_taws_caution_procedure,
            "a320_taws_warning_memory" to R.raw.a320_taws_warning_memory,
            "a320_taws_warning_procedure" to R.raw.a320_taws_warning_procedure,
            "a320_tcas_caution_traffic_advisory_memory" to R.raw.a320_tcas_caution_traffic_advisory_memory,
            "a320_tcas_caution_traffic_advisory_procedure" to R.raw.a320_tcas_caution_traffic_advisory_procedure,
            "a320_tcas_warning_memory" to R.raw.a320_tcas_warning_memory,
            "a320_tcas_warning_procedure" to R.raw.a320_tcas_warning_procedure,
            "a320_unreliable_speed_indication_memory" to R.raw.a320_unreliable_speed_indication_memory,
            "a320_unreliable_speed_indication_procedure" to R.raw.a320_unreliable_speed_indication_procedure,
            "a320_windshear_warning_reactive_windshear_memory" to R.raw.a320_windshear_warning_reactive_windshear_memory,
            "a320_windshear_warning_reactive_windshear_procedure" to R.raw.a320_windshear_warning_reactive_windshear_procedure,
            "a330_emergency_descent_memory" to R.raw.a330_emergency_descent_memory,
            "a330_emergency_descent_procedure" to R.raw.a330_emergency_descent_procedure,
            "a330_loss_of_braking_memory" to R.raw.a330_loss_of_braking_memory,
            "a330_loss_of_braking_procedure" to R.raw.a330_loss_of_braking_procedure,
            "a330_stall_recovery_memory" to R.raw.a330_stall_recovery_memory,
            "a330_stall_recovery_procedure" to R.raw.a330_stall_recovery_procedure,
            "a330_stall_warning_at_liftoff_memory" to R.raw.a330_stall_warning_at_liftoff_memory,
            "a330_stall_warning_at_liftoff_procedure" to R.raw.a330_stall_warning_at_liftoff_procedure,
            "a330_taws_caution_memory" to R.raw.a330_taws_caution_memory,
            "a330_taws_caution_procedure" to R.raw.a330_taws_caution_procedure,
            "a330_taws_warning_memory" to R.raw.a330_taws_warning_memory,
            "a330_taws_warning_procedure" to R.raw.a330_taws_warning_procedure,
            "a330_tcas_caution_traffic_advisory_memory" to R.raw.a330_tcas_caution_traffic_advisory_memory,
            "a330_tcas_caution_traffic_advisory_procedure" to R.raw.a330_tcas_caution_traffic_advisory_procedure,
            "a330_tcas_warning_resolution_advisory_memory" to R.raw.a330_tcas_warning_resolution_advisory_memory,
            "a330_tcas_warning_resolution_advisory_procedure" to R.raw.a330_tcas_warning_resolution_advisory_procedure,
            "a330_unreliable_speed_indication_memory" to R.raw.a330_unreliable_speed_indication_memory,
            "a330_unreliable_speed_indication_procedure" to R.raw.a330_unreliable_speed_indication_procedure,
            "a330_windshear_warning_reactive_windshear_memory" to R.raw.a330_windshear_warning_reactive_windshear_memory,
            "a330_windshear_warning_reactive_windshear_procedure" to R.raw.a330_windshear_warning_reactive_windshear_procedure,
            "a380_loss_of_braking_memory" to R.raw.a380_loss_of_braking_memory,
            "a380_loss_of_braking_procedure" to R.raw.a380_loss_of_braking_procedure,
            "a380_misc_emer_descent_memory" to R.raw.a380_misc_emer_descent_memory,
            "a380_misc_emer_descent_procedure" to R.raw.a380_misc_emer_descent_procedure,
            "a380_stall_recovery_memory" to R.raw.a380_stall_recovery_memory,
            "a380_stall_recovery_procedure" to R.raw.a380_stall_recovery_procedure,
            "a380_stall_warning_at_liftoff_memory" to R.raw.a380_stall_warning_at_liftoff_memory,
            "a380_stall_warning_at_liftoff_procedure" to R.raw.a380_stall_warning_at_liftoff_procedure,
            "a380_taws_caution_memory" to R.raw.a380_taws_caution_memory,
            "a380_taws_caution_procedure" to R.raw.a380_taws_caution_procedure,
            "a380_taws_warning_memory" to R.raw.a380_taws_warning_memory,
            "a380_taws_warning_procedure" to R.raw.a380_taws_warning_procedure,
            "a380_tcas_caution_traffic_advisory_memory" to R.raw.a380_tcas_caution_traffic_advisory_memory,
            "a380_tcas_caution_traffic_advisory_procedure" to R.raw.a380_tcas_caution_traffic_advisory_procedure,
            "a380_tcas_warning_resolution_advisory_memory" to R.raw.a380_tcas_warning_resolution_advisory_memory,
            "a380_tcas_warning_resolution_advisory_procedure" to R.raw.a380_tcas_warning_resolution_advisory_procedure,
            "a380_unreliable_speed_indication_procedure" to R.raw.a380_unreliable_speed_indication_procedure,
            "a380_unreliable_speed_memory" to R.raw.a380_unreliable_speed_memory,
            "a380_windshear_warning_reactive_windshear_memory" to R.raw.a380_windshear_warning_reactive_windshear_memory,
            "a380_windshear_warning_reactive_windshear_procedure" to R.raw.a380_windshear_warning_reactive_windshear_procedure,
            "b787_aborted_engine_start_memory" to R.raw.b787_aborted_engine_start_memory,
            "b787_airspeed_unreliable_memory" to R.raw.b787_airspeed_unreliable_memory,
            "b787_cabin_altitude_memory" to R.raw.b787_cabin_altitude_memory,
            "b787_dual_engine_fail_memory" to R.raw.b787_dual_engine_fail_memory,
            "b787_engine_autostart_memory" to R.raw.b787_engine_autostart_memory,
            "b787_engine_limit_exceed_memory" to R.raw.b787_engine_limit_exceed_memory,
            "b787_engine_severe_damage_memory" to R.raw.b787_engine_severe_damage_memory,
            "b787_engine_surge_memory" to R.raw.b787_engine_surge_memory,
            "b787_fire_engine_memory" to R.raw.b787_fire_engine_memory,
            "b787_stabilizer_memory" to R.raw.b787_stabilizer_memory,
        )
    }
}
