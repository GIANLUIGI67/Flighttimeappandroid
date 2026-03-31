package it.grg.flighttimeapp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class FlightTimeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        logFirebaseDbUrl()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val root = activity.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                    val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val base = (v.getTag(R.id.insets_padding_tag) as? InsetsPadding)
                        ?: InsetsPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom).also {
                            v.setTag(R.id.insets_padding_tag, it)
                        }
                    v.setPadding(base.left, base.top + sys.top, base.right, base.bottom)
                    insets
                }
                ViewCompat.requestApplyInsets(root)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private data class InsetsPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun logFirebaseDbUrl() {
        try {
            val url = FirebaseDatabase.getInstance().reference.toString()
            Log.d("FlightTimeApp", "Firebase Realtime DB URL: $url")
        } catch (e: Exception) {
            Log.e("FlightTimeApp", "Failed to read Firebase DB URL: ${e.message}")
        }
    }
}
