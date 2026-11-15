package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Single place where screens obtain the backend. Production uses [AletheiaApi]
 * (Firebase callables); instrumented tests install a fake so no test ever calls
 * the production project.
 */
object AletheiaServiceLocator {
    @Volatile var backendOverride: AletheiaBackend? = null
    @Volatile var networkOverride: (() -> Boolean)? = null

    fun backend(context: Context): AletheiaBackend =
        backendOverride ?: AletheiaApi(context.applicationContext)

    fun network(context: Context): () -> Boolean {
        networkOverride?.let { return it }
        val app = context.applicationContext
        return {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    fun resetForTests() {
        backendOverride = null
        networkOverride = null
    }
}
