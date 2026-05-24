package it.grg.flighttimeapp.crewl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseNetworkException

object CrewAuthManager {
    private const val TAG = "CrewAuthManager"
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private var isSigningIn = false
    private val pendingCallbacks = mutableListOf<(AuthResult) -> Unit>()

    enum class Failure {
        NONE,
        NETWORK,
        AUTH
    }

    data class AuthResult(
        val uid: String?,
        val failure: Failure = Failure.NONE,
        val message: String? = null
    ) {
        val isSuccess: Boolean get() = !uid.isNullOrBlank()
        val isNetworkFailure: Boolean get() = failure == Failure.NETWORK
    }

    fun ensureSignedIn(onResult: (String?) -> Unit) {
        ensureSignedInDetailed(null) { result -> onResult(result.uid) }
    }

    fun ensureSignedInDetailed(context: Context?, onResult: (AuthResult) -> Unit) {
        synchronized(this) {
            pendingCallbacks.add(onResult)
            if (isSigningIn) return
            isSigningIn = true
        }

        val current = auth.currentUser
        if (current != null && !current.uid.isNullOrBlank()) {
            // Do not block Near Me on a forced token refresh. The Firebase SDK can use
            // the cached user/session and refresh tokens as needed; forcing a refresh here
            // makes transient App Check/network/provider failures look like auth failure.
            val uid = current.uid
            complete(AuthResult(uid))
            current.getIdToken(false)
                .addOnFailureListener { e -> Log.w(TAG, "Cached auth token refresh failed: ${e.message}") }
            return
        }

        if (context != null && !hasValidatedInternet(context)) {
            complete(AuthResult(null, Failure.NETWORK, "No validated internet connection"))
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener { res -> complete(AuthResult(res.user?.uid)) }
            .addOnFailureListener { e ->
                val failure = if (isNetworkError(e)) Failure.NETWORK else Failure.AUTH
                if (failure == Failure.NETWORK) {
                    Log.w(TAG, "Anonymous sign-in network failure: ${e.message}")
                } else {
                    Log.e(TAG, "Anonymous sign-in failed: ${e.message}", e)
                }
                complete(AuthResult(null, failure, e.message))
            }
    }

    private fun complete(result: AuthResult) {
        val callbacks = synchronized(this) {
            isSigningIn = false
            val copy = pendingCallbacks.toList()
            pendingCallbacks.clear()
            copy
        }
        callbacks.forEach { it(result) }
    }

    private fun isNetworkError(error: Exception): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is FirebaseNetworkException) return true
            val message = current.message?.lowercase().orEmpty()
            val looksNetworkRelated = listOf(
                "network",
                "timeout",
                "timed out",
                "unreachable",
                "host",
                "connect",
                "connection",
                "socket",
                "unable to resolve",
                "no route"
            ).any { message.contains(it) }
            if (looksNetworkRelated) return true
            current = current.cause
        }
        return false
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
