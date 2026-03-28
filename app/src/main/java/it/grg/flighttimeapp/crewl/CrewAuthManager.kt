package it.grg.flighttimeapp.crewl

import com.google.firebase.auth.FirebaseAuth

object CrewAuthManager {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    fun ensureSignedIn(onResult: (String?) -> Unit) {
        val current = auth.currentUser
        if (current != null && !current.uid.isNullOrBlank()) {
            onResult(current.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { res ->
                onResult(res.user?.uid)
            }
            .addOnFailureListener {
                onResult(null)
            }
    }
}
