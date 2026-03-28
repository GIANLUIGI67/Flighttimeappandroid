package it.grg.flighttimeapp.crewl

import android.os.Handler
import android.os.Looper

class CrewBannerCenter private constructor() {
    data class Banner(val message: String)

    @Volatile var current: Banner? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    fun show(message: String, durationMs: Long = 6000L) {
        current = Banner(message)
        clearRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (current?.message == message) {
                current = null
            }
        }
        clearRunnable = r
        handler.postDelayed(r, durationMs)
    }

    fun clear() {
        clearRunnable?.let { handler.removeCallbacks(it) }
        clearRunnable = null
        current = null
    }

    companion object {
        val shared = CrewBannerCenter()
    }
}
