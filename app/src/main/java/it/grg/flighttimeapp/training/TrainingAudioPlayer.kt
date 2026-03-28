package it.grg.flighttimeapp.training

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
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
        return context.resources.getIdentifier(resName, "raw", context.packageName)
    }

    private fun toastMissing(name: String) {
        Toast.makeText(context, "Missing audio: $name", Toast.LENGTH_SHORT).show()
    }
}
