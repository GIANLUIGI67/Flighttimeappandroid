package it.grg.flighttimeapp.crewl

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class CrewChatActivity : AppCompatActivity() {

    private val chatStore = CrewLayoverChatStore.shared
    private val crewStore = CrewLayoverStore.shared
    private lateinit var adapter: CrewChatMessagesAdapter

    private var threadId: String? = null
    private var peerId: String? = null
    private var peerName: String? = null

    private var photoExpires = false
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sendPickedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_chat)
        crewStore.init(this)

        findViewById<ImageButton>(R.id.chatBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.chatDeleteBtn).setOnClickListener {
            val tid = threadId
            val pid = peerId
            if (tid != null && pid != null) {
                chatStore.clearThreadMessages(tid, pid)
            }
        }

        val title = findViewById<TextView>(R.id.chatTitle)
        val input = findViewById<EditText>(R.id.chatInput)
        val sendBtn = findViewById<android.widget.Button>(R.id.chatSend)
        val photoBtn = findViewById<ImageButton>(R.id.chatPhotoBtn)
        val expiryBtn = findViewById<TextView>(R.id.chatPhotoExpiryBtn)

        threadId = intent.getStringExtra(EXTRA_THREAD_ID)
        peerId = intent.getStringExtra(EXTRA_PEER_ID)
        peerName = intent.getStringExtra(EXTRA_PEER_NAME)
        title.text = peerName ?: getString(R.string.cl_chat)

        val recycler = findViewById<RecyclerView>(R.id.chatRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CrewChatMessagesAdapter(emptyList()) { msg ->
            showChatImagePreview(msg)
        }
        recycler.adapter = adapter

        crewStore.settingsLive.observe(this, Observer { s ->
            val nickname = s.nickname.ifBlank { getString(R.string.cl_chat) }
            val photo = CrewPhotoLoader.shared.myLocalProfileImage()
            adapter.setMyProfile(nickname, photo)
        })

        chatStore.messagesByThread.observe(this, Observer { map ->
            val msgs = map[threadId] ?: emptyList()
            adapter.submit(msgs)
            if (msgs.isNotEmpty()) recycler.scrollToPosition(msgs.size - 1)
        })

        sendBtn.setOnClickListener {
            val t = input.text.toString().trim()
            val tid = threadId
            val pid = peerId
            if (!t.isNullOrBlank() && tid != null && pid != null) {
                chatStore.sendMessage(tid, pid, t)
                chatStore.markThreadRead(tid)
                input.setText("")
            }
        }

        fun updateExpiryButton() {
            expiryBtn.text = if (photoExpires) "20s" else "∞"
            expiryBtn.setBackgroundResource(R.drawable.bg_ios_btn_blue)
            expiryBtn.setTextColor(getColor(android.R.color.white))
        }
        updateExpiryButton()
        expiryBtn.setOnClickListener {
            photoExpires = !photoExpires
            updateExpiryButton()
        }

        photoBtn.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        if (threadId == null && peerId != null && peerName != null) {
            // Ensure thread if coming from nearby
            val user = NearbyCrewUser(
                userId = peerId!!,
                nickname = peerName!!,
                companyName = intent.getStringExtra(EXTRA_PEER_COMPANY),
                baseCountryCode = "",
                phoneNumber = null,
                role = CrewRole.CABIN_CREW,
                visibilityMode = CrewVisibilityMode.EVERYONE,
                excludedBaseCodes = emptyList(),
                isOnline = false,
                lastSeenMs = 0L,
                lat = 0.0,
                lon = 0.0,
                distanceKm = 0.0,
                photoB64 = null,
                photosB64 = emptyList()
            )
            threadId = chatStore.ensureThread(user)
        }

        threadId?.let { chatStore.startMessagesObserver(it) }

        val pid = peerId
        if (pid != null) {
            val complete = crewStore.isUserProfileComplete(pid)
            chatStore.sendProfileReminderToIncompleteUserIfNeeded(pid, complete)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        threadId?.let { chatStore.stopMessagesObserver(it) }
    }

    private fun sendPickedImage(uri: android.net.Uri) {
        val tid = threadId ?: return
        val pid = peerId ?: return
        val stream = contentResolver.openInputStream(uri) ?: return
        val bitmap = stream.use { BitmapFactory.decodeStream(it) } ?: return
        val preview = getString(R.string.cl_photo_message_preview)
        val expires = if (photoExpires) 20 else null
        chatStore.sendImageMessage(tid, pid, bitmap, expires, preview)
        chatStore.markThreadRead(tid)
    }

    private fun showChatImagePreview(msg: CrewChatMessage) {
        val tid = threadId ?: return
        val base64 = msg.imageBase64 ?: return
        if (base64.isBlank()) return
        val summary = crewStore.getUserSummary(msg.senderUid)
        if (summary == null) {
            crewStore.fetchUserOnce(msg.senderUid) {
                runOnUiThread { showChatImagePreview(msg) }
            }
            return
        }
        val profilePhotos = summary.photosB64
        val avatarB64 = summary.photoB64 ?: profilePhotos.firstOrNull()
        val photos = ArrayList<String>()
        photos.add(base64)
        profilePhotos.forEach { if (it != base64) photos.add(it) }
        CrewPhotoPreviewDialog.show(
            fragmentManager = supportFragmentManager,
            ownerUid = msg.senderUid,
            photosB64 = photos,
            initialIndex = 0,
            threadId = tid,
            messageId = msg.id,
            avatarB64 = avatarB64,
            peerName = peerName ?: getString(R.string.cl_chat),
            peerCompany = ""
        )
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_PEER_COMPANY = "peer_company"
    }
}
