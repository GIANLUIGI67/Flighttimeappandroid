package it.grg.flighttimeapp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.database.FirebaseDatabase

class FlightTimeApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        installAppCheckProvider()
        scheduleStartupDiagnostics()
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

    private fun scheduleStartupDiagnostics() {
        mainHandler.postDelayed({
            logFirebaseDbUrl()
            probeAppCheckToken()
        }, 1_500L)
    }

    private fun logFirebaseDbUrl() {
        try {
            val url = FirebaseDatabase.getInstance().reference.toString()
            Log.d("FlightTimeApp", "Firebase Realtime DB URL: $url")
        } catch (e: Exception) {
            Log.e("FlightTimeApp", "Failed to read Firebase DB URL: ${e.message}")
        }
    }

    private fun installAppCheckProvider() {
        try {
            val factory = if (isDebuggable()) {
                debugAppCheckProviderFactory() ?: PlayIntegrityAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
            Log.d("FlightTimeApp", "App Check provider installed: ${factory.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w("FlightTimeApp", "App Check provider not installed: ${e.message}")
        }
    }

    private fun probeAppCheckToken() {
        try {
            FirebaseAppCheck.getInstance().getToken(false)
                .addOnSuccessListener {
                    Log.d("FlightTimeApp", "App Check active: token acquired")
                }
                .addOnFailureListener { e ->
                    Log.w("FlightTimeApp", "App Check token not yet available: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w("FlightTimeApp", "App Check token probe failed: ${e.message}")
        }
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun debugAppCheckProviderFactory(): AppCheckProviderFactory? {
        return try {
            val factoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
            val getInstance = factoryClass.getMethod("getInstance")
            getInstance.invoke(null) as AppCheckProviderFactory
        } catch (e: Exception) {
            Log.w("FlightTimeApp", "Debug App Check provider unavailable: ${e.message}")
            null
        }
    }
}
