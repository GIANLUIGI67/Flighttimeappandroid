package it.grg.flighttimeapp.crewl

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

class CrewPresenceService private constructor() : DefaultLifecycleObserver {

    enum class State { ONLINE, OFFLINE }

    @Volatile var myState: State = State.OFFLINE
        private set
    @Volatile var myLastChangedMs: Long = 0L
        private set

    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference
    private var connectedHandle: com.google.firebase.database.ValueEventListener? = null
    private var heartbeatRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var uid: String? = null

    fun start(uid: String) {
        val trimmed = uid.trim()
        if (trimmed.isEmpty()) return
        if (this.uid == trimmed) return
        stop()

        this.uid = trimmed

        val userPath = "crew_users/$trimmed"
        val connectedRef = root.child(".info/connected")

        // On disconnect -> offline
        root.child(userPath).onDisconnect().updateChildren(
            mapOf(
                "isOnline" to false,
                "lastSeenMs" to ServerValue.TIMESTAMP
            )
        )

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = (snapshot.value as? Boolean) ?: false
                if (connected) {
                    setOnlineNow()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        connectedRef.addValueEventListener(listener)
        connectedHandle = listener

        // Heartbeat
        startHeartbeat()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        setOnlineNow()
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                val uid = uid ?: return
                root.child("crew_users/$uid").updateChildren(
                    mapOf("lastSeenMs" to ServerValue.TIMESTAMP)
                )
                handler.postDelayed(this, 25_000L)
            }
        }
        heartbeatRunnable = runnable
        handler.postDelayed(runnable, 25_000L)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    fun setOnlineNow() {
        val uid = uid ?: return
        root.child("crew_users/$uid").updateChildren(
            mapOf(
                "isOnline" to true,
                "lastSeenMs" to ServerValue.TIMESTAMP
            )
        )
        myState = State.ONLINE
        myLastChangedMs = System.currentTimeMillis()
    }

    fun setOfflineNow() {
        val uid = uid ?: return
        root.child("crew_users/$uid").updateChildren(
            mapOf(
                "isOnline" to false,
                "lastSeenMs" to ServerValue.TIMESTAMP
            )
        )
        myState = State.OFFLINE
        myLastChangedMs = System.currentTimeMillis()
    }

    fun stop() {
        val uid = this.uid
        if (uid != null) {
            connectedHandle?.let { root.child(".info/connected").removeEventListener(it) }
        }
        connectedHandle = null
        stopHeartbeat()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        this.uid = null
        myState = State.OFFLINE
        myLastChangedMs = 0L
    }

    override fun onStart(owner: LifecycleOwner) {
        setOnlineNow()
    }

    override fun onStop(owner: LifecycleOwner) {
        setOfflineNow()
    }

    companion object {
        val shared = CrewPresenceService()
    }
}
