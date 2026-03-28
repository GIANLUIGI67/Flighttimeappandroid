package it.grg.flighttimeapp.crewl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class EventExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        if (eventId.isBlank()) return

        val root = FirebaseDatabase.getInstance().reference
        root.child("events").child(eventId).removeValue()
        root.child("event_members").child(eventId).removeValue()
        root.child("event_messages").child(eventId).removeValue()

        root.child("user_event_invites").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { userSnap ->
                    userSnap.ref.child(eventId).removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        root.child("event_hidden").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { userSnap ->
                    userSnap.ref.child(eventId).removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}
