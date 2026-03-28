package it.grg.flighttimeapp.training

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast

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
        player = MediaPlayer.create(context, resId)
        player?.isLooping = loop
        player?.start()
    }

    fun playAll(baseNames: List<String>, loop: Boolean) {
        stop()
        val ids = baseNames.map { resolveResId(it) }.filter { it != 0 }
        if (ids.isEmpty()) {
            toastMissing("audio")
            return
        }
        queue = ids
        index = 0
        loopAll = loop
        playNextInQueue()
    }

    fun stop() {
        player?.setOnCompletionListener(null)
        player?.stop()
        player?.release()
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
        player = MediaPlayer.create(context, resId)
        player?.setOnCompletionListener {
            index += 1
            playNextInQueue()
        }
        player?.start()
    }

    private fun resolveResId(baseName: String): Int {
        val resName = baseName.lowercase()
        return context.resources.getIdentifier(resName, "raw", context.packageName)
    }

    private fun toastMissing(name: String) {
        Toast.makeText(context, "Missing audio: $name", Toast.LENGTH_SHORT).show()
    }
}
