package it.grg.flighttimeapp.crewl

import com.google.firebase.auth.FirebaseAuth

object CrewAuthManager {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    fun ensureSignedIn(onResult: (String?) -> Unit) {
        val current = auth.currentUser
        if (current != null && !current.uid.isNullOrBlank()) {
            // Force-refresh the token so Firebase server confirms the account still exists.
            // Without this, a deleted or expired anonymous account returns a cached UID
            // that gets permission-denied on every write until the app is reinstalled.
            current.getIdToken(true)
                .addOnSuccessListener { onResult(current.uid) }
                .addOnFailureListener {
                    // Account was deleted or token is permanently invalid — sign in fresh.
                    auth.signInAnonymously()
                        .addOnSuccessListener { res -> onResult(res.user?.uid) }
                        .addOnFailureListener { onResult(null) }
                }
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { res -> onResult(res.user?.uid) }
            .addOnFailureListener { onResult(null) }
    }
}
