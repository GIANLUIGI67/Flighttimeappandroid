package it.grg.flighttimeapp.crewl

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class CrewEventChatActivity : AppCompatActivity() {

    private val store = CrewLayoverStore.shared

    private var eventId: String? = null

    private lateinit var joinBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var adapter: CrewChatMessagesAdapter

    private var photoExpires = false
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sendPickedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_event_chat)
        store.init(this)

        findViewById<ImageButton>(R.id.eventChatBack).setOnClickListener { finish() }
        val title = findViewById<TextView>(R.id.eventChatTitle)
        joinBtn = findViewById(R.id.eventJoinLeaveBtn)
        deleteBtn = findViewById(R.id.eventDeleteBtn)
        val input = findViewById<EditText>(R.id.eventChatInput)
        val sendBtn = findViewById<Button>(R.id.eventChatSend)
        val photoBtn = findViewById<ImageButton>(R.id.eventChatPhotoBtn)
        val expiryBtn = findViewById<Button>(R.id.eventChatPhotoExpiryBtn)

        eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        title.text = intent.getStringExtra(EXTRA_EVENT_TITLE) ?: getString(R.string.cl_event)

        val recycler = findViewById<RecyclerView>(R.id.eventChatRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CrewChatMessagesAdapter(emptyList())
        recycler.adapter = adapter

        store.settingsLive.observe(this) { s ->
            val nickname = s.nickname.ifBlank { getString(R.string.cl_chat) }
            val photo = CrewPhotoLoader.shared.myLocalProfileImage()
            adapter.setMyProfile(nickname, photo)
        }

        store.eventChatMessages.observe(this, Observer { msgs ->
            adapter.submit(msgs.map {
                CrewChatMessage(
                    id = it.id,
                    threadId = it.eventId,
                    senderUid = it.senderUid,
                    text = it.text,
                    imageBase64 = it.imageBase64,
                    imageExpiresAtMs = it.imageExpiresAtMs,
                    createdAt = it.createdAt
                )
            })
            if (msgs.isNotEmpty()) recycler.scrollToPosition(msgs.size - 1)
        })

        store.joinedEventIds.observe(this, Observer {
            updateJoinButton()
        })

        store.activeEvents.observe(this, Observer {
            updateDeleteButton()
        })

        joinBtn.setOnClickListener {
            val id = eventId ?: return@setOnClickListener
            val joined = store.joinedEventIds.value?.contains(id) == true
            if (joined) {
                store.leaveEvent(id)
            } else {
                val event = store.activeEvents.value?.firstOrNull { it.id == id }
                if (event != null && store.settingsLive.value?.eventRemindersEnabled == true) {
                    showJoinReminderDialog(event)
                } else {
                    store.joinEvent(id)
                }
            }
            updateJoinButton()
        }

        deleteBtn.setOnClickListener {
            val id = eventId ?: return@setOnClickListener
            store.deleteEvent(id) { ok ->
                if (ok) {
                    finish()
                }
            }
        }

        sendBtn.setOnClickListener {
            val t = input.text.toString().trim()
            val id = eventId ?: return@setOnClickListener
            if (t.isNotBlank()) {
                store.sendEventMessage(id, t)
                input.setText("")
            }
        }

        fun updateExpiryButton() {
            expiryBtn.text = if (photoExpires) "20s" else "∞"
            val bg = if (photoExpires) R.drawable.bg_ios_btn_outline_blue else R.drawable.bg_ios_btn_grey
            expiryBtn.setBackgroundResource(bg)
        }
        updateExpiryButton()
        expiryBtn.setOnClickListener {
            photoExpires = !photoExpires
            updateExpiryButton()
        }

        photoBtn.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
    }

    private fun showJoinReminderDialog(event: CrewLayoverEvent) {
        val options = AlarmOption.entries.toList()
        val labels: Array<CharSequence> = options.map { getString(it.labelResId) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_event_alarm))
            .setItems(labels) { _, which ->
                val selected = options.getOrNull(which) ?: AlarmOption.NONE
                store.joinEvent(event.id, selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        val id = eventId ?: return
        store.openEventChat(id)
        updateJoinButton()
    }

    override fun onStop() {
        super.onStop()
        store.closeEventChat()
    }

    private fun updateJoinButton() {
        val id = eventId ?: return
        val joined = store.joinedEventIds.value?.contains(id) == true
        joinBtn.text = if (joined) getString(R.string.cl_leave) else getString(R.string.cl_join)
    }

    private fun updateDeleteButton() {
        val id = eventId ?: return
        deleteBtn.visibility = if (store.canDeleteEvent(id)) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun sendPickedImage(uri: android.net.Uri) {
        val id = eventId ?: return
        val stream = contentResolver.openInputStream(uri) ?: return
        val bitmap = stream.use { BitmapFactory.decodeStream(it) } ?: return
        val expires = if (photoExpires) 20 else null
        store.sendEventImageMessage(id, bitmap, expires)
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_TITLE = "event_title"
    }
}
