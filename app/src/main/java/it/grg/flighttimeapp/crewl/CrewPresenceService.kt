package it.grg.flighttimeapp.crewl

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import it.grg.flighttimeapp.CLog

class CrewPresenceService private constructor() : DefaultLifecycleObserver {

    enum class State { ONLINE, OFFLINE }

    @Volatile var myState: State = State.OFFLINE
        private set
    @Volatile var myLastChangedMs: Long = 0L
        private set

    @Volatile private var lastLat: Double = 0.0
    @Volatile private var lastLon: Double = 0.0
    @Volatile private var lastPresenceWriteAtMs: Long = 0L

    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference
    private var connectedHandle: ValueEventListener? = null
    private var heartbeatRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var uid: String? = null

    fun start(uid: String) {
        val trimmed = uid.trim()
        if (trimmed.isEmpty()) return
        if (this.uid == trimmed) {
            setOnlineNow() // Re-ensure online
            return
        }
        stop()

        this.uid = trimmed
        CLog.d(TAG, "Starting Presence for $trimmed")

        val userPath = "crew_users/$trimmed"
        val connectedRef = root.child(".info/connected")

        // On disconnect -> offline
        val onDisconnectUpdates = mutableMapOf<String, Any>(
            "isOnline" to false,
            "lastSeenMs" to ServerValue.TIMESTAMP
        )
        root.child(userPath).onDisconnect().updateChildren(onDisconnectUpdates)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = (snapshot.value as? Boolean) ?: false
                if (connected) {
                    CLog.d(TAG, "Firebase connected - forcing online")
                    setOnlineNow()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        connectedRef.addValueEventListener(listener)
        connectedHandle = listener

        startHeartbeat()

        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        setOnlineNow()
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                val currentUid = uid ?: return
                // Only write when actually online. If myState is somehow OFFLINE the
                // heartbeat skips the Firebase write — it never writes isOnline: false.
                // setOfflineNow() / onDisconnect are the only paths that clear the flag.
                if (myState != State.ONLINE) {
                    handler.postDelayed(this, CrewTiming.PRESENCE_OFFLINE_RETRY_DELAY_MS)
                    return
                }
                val updates = mutableMapOf<String, Any>(
                    "isOnline" to true,
                    "lastSeenMs" to ServerValue.TIMESTAMP
                )
                if (lastLat != 0.0 && lastLon != 0.0) {
                    updates["lat"] = lastLat
                    updates["lon"] = lastLon
                }
                lastPresenceWriteAtMs = System.currentTimeMillis()
                root.child("crew_users/$currentUid").updateChildren(updates)
                    .addOnFailureListener { CLog.e(TAG, "Presence heartbeat failed: ${it.message}") }
                handler.postDelayed(this, CrewTiming.PRESENCE_HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = runnable
        handler.postDelayed(runnable, CrewTiming.PRESENCE_INITIAL_DELAY_MS) // First tick immediately after startup
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    fun setOnlineNow() {
        val currentUid = uid ?: return
        myState = State.ONLINE
        myLastChangedMs = System.currentTimeMillis()

        val updates = mutableMapOf<String, Any>(
            "isOnline" to true,
            "lastSeenMs" to ServerValue.TIMESTAMP
        )

        if (lastLat != 0.0 && lastLon != 0.0) {
            updates["lat"] = lastLat
            updates["lon"] = lastLon
        }

        lastPresenceWriteAtMs = System.currentTimeMillis()
        root.child("crew_users/$currentUid").updateChildren(updates)
            .addOnSuccessListener { CLog.d(TAG, "Presence -> ONLINE") }
            .addOnFailureListener { CLog.e(TAG, "Presence failed to set ONLINE: ${it.message}") }
    }

    fun setOfflineNow() {
        val currentUid = uid ?: return
        myState = State.OFFLINE
        myLastChangedMs = System.currentTimeMillis()

        val updates = mutableMapOf<String, Any>(
            "isOnline" to false,
            "lastSeenMs" to ServerValue.TIMESTAMP
        )
        lastPresenceWriteAtMs = System.currentTimeMillis()
        root.child("crew_users/$currentUid").updateChildren(updates)
        CLog.d(TAG, "Presence -> OFFLINE")
    }

    fun updateLocation(lat: Double, lon: Double) {
        val currentUid = uid
        if (lat == 0.0 && lon == 0.0) return
        lastLat = lat
        lastLon = lon
        CLog.d(TAG, "Location updated in memory: $lat, $lon")
        if (currentUid == null || myState != State.ONLINE) return

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPresenceWriteAtMs < CrewTiming.PRESENCE_MIN_LOCATION_WRITE_INTERVAL_MS) return

        val updates = mutableMapOf<String, Any>(
            "isOnline" to true,
            "lastSeenMs" to ServerValue.TIMESTAMP,
            "lat" to lat,
            "lon" to lon
        )
        lastPresenceWriteAtMs = nowMs
        root.child("crew_users/$currentUid").updateChildren(updates)
            .addOnFailureListener { CLog.e(TAG, "Presence location write failed: ${it.message}") }
    }

    fun stop() {
        val currentUid = this.uid
        if (currentUid != null) {
            val updates = mutableMapOf<String, Any>(
                "isOnline" to false,
                "lastSeenMs" to ServerValue.TIMESTAMP
            )
            lastPresenceWriteAtMs = System.currentTimeMillis()
            root.child("crew_users/$currentUid").updateChildren(updates)

            connectedHandle?.let { root.child(".info/connected").removeEventListener(it) }
        }
        connectedHandle = null
        stopHeartbeat()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        this.uid = null
        myState = State.OFFLINE
        lastLat = 0.0
        lastLon = 0.0
    }

    override fun onStart(owner: LifecycleOwner) {
        CLog.d(TAG, "Lifecycle -> ON_START (App Foreground)")
        setOnlineNow()
        startHeartbeat()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Do NOT write isOnline: false here.
        // ProcessLifecycleOwner.onStop() fires whenever the app is backgrounded —
        // even briefly when the user switches away. Writing offline here makes every
        // user disappear from "Near Me" the instant they stop looking at the screen.
        // The onDisconnect handler registered in start() takes care of marking offline
        // when the Firebase connection truly drops (app killed / device goes offline).
        // Pause the heartbeat only to reduce background battery/bandwidth usage.
        stopHeartbeat()
        CLog.d(TAG, "Lifecycle -> ON_STOP (App Background) — heartbeat paused, staying online")
    }

    companion object {
        val shared = CrewPresenceService()
        private const val TAG = "CrewPresence"
    }
}
