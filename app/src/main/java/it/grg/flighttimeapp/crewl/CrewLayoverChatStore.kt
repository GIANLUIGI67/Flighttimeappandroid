package it.grg.flighttimeapp.crewl

import android.graphics.Bitmap
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import it.grg.flighttimeapp.R
import java.io.ByteArrayOutputStream
import java.util.Date

class CrewLayoverChatStore private constructor() {

    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference

    private var threadsHandle: ValueEventListener? = null
    private val messagesHandles: MutableMap<String, ChildEventListener> = mutableMapOf()
    private val messagesRefs: MutableMap<String, DatabaseReference> = mutableMapOf()
    private val suppressBannerForThread: MutableSet<String> = mutableSetOf()
    private val lastBannerMessageIdByThread: MutableMap<String, String> = mutableMapOf()
    private val lastSeedCreatedAtMsByThread: MutableMap<String, Long> = mutableMapOf()
    private val peerNameByThread: MutableMap<String, String> = mutableMapOf()

    private val _threads = androidx.lifecycle.MutableLiveData<List<CrewChatThread>>(emptyList())
    val threads: androidx.lifecycle.LiveData<List<CrewChatThread>> = _threads

    private val _messagesByThread = androidx.lifecycle.MutableLiveData<Map<String, List<CrewChatMessage>>>(emptyMap())
    val messagesByThread: androidx.lifecycle.LiveData<Map<String, List<CrewChatMessage>>> = _messagesByThread

    private val _unreadThreadIds = androidx.lifecycle.MutableLiveData<Set<String>>(emptySet())
    val unreadThreadIds: androidx.lifecycle.LiveData<Set<String>> = _unreadThreadIds

    private fun myUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    fun threadIdFor(peer: NearbyCrewUser): String {
        val me = myUid() ?: ""
        val pair = listOf(me, peer.userId).sorted()
        return "thread_" + pair.joinToString("_")
    }

    fun startThreadsObserver() {
        val me = myUid() ?: return
        stopThreadsObserver()

        val ref = root.child("userThreads").child(me)
        val query = ref.orderByChild("lastMessageAt").limitToLast(100)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newThreads = mutableListOf<CrewChatThread>()
                val newUnread = mutableSetOf<String>()

                snapshot.children.forEach { cs ->
                    val dict = cs.value as? Map<*, *> ?: return@forEach
                    val thread = decodeThread(cs.key ?: return@forEach, dict)
                    if (thread != null) {
                        val lastReadMs = readMs(dict["lastReadAt"])
                        val lastMessageMs = readMs(dict["lastMessageAt"])
                        val lastSender = dict["lastMessageSender"] as? String ?: thread.lastMessageSender ?: ""
                        val isUnread = lastMessageMs > lastReadMs && lastSender.isNotEmpty() && lastSender != me
                        if (isUnread) newUnread.add(thread.id)
                        newThreads.add(thread)
                    }
                }

                newThreads.sortWith { a, b ->
                    val aUnread = newUnread.contains(a.id)
                    val bUnread = newUnread.contains(b.id)
                    if (aUnread != bUnread) {
                        if (aUnread) -1 else 1
                    } else {
                        b.lastMessageAt.compareTo(a.lastMessageAt)
                    }
                }

                _threads.postValue(newThreads)
                _unreadThreadIds.postValue(newUnread)

                peerNameByThread.clear()
                newThreads.forEach { t ->
                    val name = t.peerNickname.ifBlank { t.peerId }
                    peerNameByThread[t.id] = name
                }

                syncThreadObservers(newThreads)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        query.addValueEventListener(listener)
        threadsHandle = listener
    }

    fun stopThreadsObserver() {
        val me = myUid() ?: return
        threadsHandle?.let { root.child("userThreads").child(me).removeEventListener(it) }
        threadsHandle = null
    }

    private fun syncThreadObservers(threads: List<CrewChatThread>) {
        val ids = threads.map { it.id }.toSet()
        val existing = messagesHandles.keys.toSet()
        val toAdd = ids.subtract(existing)
        val toRemove = existing.subtract(ids)

        toAdd.forEach { startMessagesObserver(it) }
        toRemove.forEach { stopMessagesObserver(it) }
    }

    fun startMessagesObserver(threadId: String) {
        if (messagesHandles.containsKey(threadId)) return

        val ref = root.child("chatMessages").child(threadId)
        val query = ref.orderByChild("createdAt").limitToLast(100)
        suppressBannerForThread.add(threadId)

        // seed
        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val msgs = mutableListOf<CrewChatMessage>()
                var lastCreatedAtMs = 0L
                var lastId: String? = null
                snapshot.children.forEach { cs ->
                    val dict = cs.value as? Map<*, *> ?: return@forEach
                    val msg = decodeMessage(cs.key ?: return@forEach, threadId, dict)
                    msgs.add(msg)
                    val createdAtMs = readMs(dict["createdAt"])
                    if (createdAtMs >= lastCreatedAtMs) {
                        lastCreatedAtMs = createdAtMs
                        lastId = msg.id
                    }
                }
                msgs.sortBy { it.createdAt }
                val map = _messagesByThread.value?.toMutableMap() ?: mutableMapOf()
                map[threadId] = msgs
                _messagesByThread.postValue(map)
                if (lastCreatedAtMs > 0L) {
                    lastSeedCreatedAtMsByThread[threadId] = lastCreatedAtMs
                }
                if (lastId != null) {
                    lastBannerMessageIdByThread[threadId] = lastId!!
                }
                suppressBannerForThread.remove(threadId)
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val dict = snapshot.value as? Map<*, *> ?: return
                val msg = decodeMessage(snapshot.key ?: return, threadId, dict)
                val map = _messagesByThread.value?.toMutableMap() ?: mutableMapOf()
                val list = map[threadId]?.toMutableList() ?: mutableListOf()
                if (list.none { it.id == msg.id }) {
                    list.add(msg)
                    list.sortBy { it.createdAt }
                    map[threadId] = list
                    _messagesByThread.postValue(map)
                }

                val me = myUid()
                val seedMs = lastSeedCreatedAtMsByThread[threadId] ?: 0L
                val msgCreatedAtMs = readMs(dict["createdAt"])
                if (me != null &&
                    msg.senderUid != me &&
                    !suppressBannerForThread.contains(threadId) &&
                    (seedMs == 0L || msgCreatedAtMs > seedMs)
                ) {
                    val lastId = lastBannerMessageIdByThread[threadId]
                    if (lastId != msg.id) {
                        lastBannerMessageIdByThread[threadId] = msg.id
                        val display = peerNameByThread[threadId].orEmpty().ifBlank { "Crew" }
                        val ctx = FirebaseApp.getInstance().applicationContext
                        val msg = ctx.getString(R.string.cl_new_message_from, display)
                        CrewBannerCenter.shared.show(msg)
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        query.addChildEventListener(childListener)
        messagesHandles[threadId] = childListener
        messagesRefs[threadId] = ref
    }

    fun stopMessagesObserver(threadId: String) {
        val ref = messagesRefs[threadId]
        val handle = messagesHandles[threadId]
        if (ref != null && handle != null) {
            ref.removeEventListener(handle)
        }
        messagesRefs.remove(threadId)
        messagesHandles.remove(threadId)
    }

    fun stopAllObservers() {
        stopThreadsObserver()
        messagesHandles.keys.toList().forEach { stopMessagesObserver(it) }
    }

    fun ensureThread(peer: NearbyCrewUser): String? {
        val me = myUid() ?: return null
        val threadId = threadIdFor(peer)
        val now = System.currentTimeMillis()

        val meThread = mapOf(
            "peerId" to peer.userId,
            "peerNickname" to peer.nickname,
            "peerCompany" to (peer.companyName ?: ""),
            "createdAt" to now,
            "lastMessageAt" to now,
            "lastMessageText" to "",
            "lastMessageSender" to "",
            "lastReadAt" to now,
            "members" to listOf(me, peer.userId)
        )
        val peerThread = mapOf(
            "peerId" to me,
            "peerNickname" to "",
            "peerCompany" to "",
            "createdAt" to now,
            "lastMessageAt" to now,
            "lastMessageText" to "",
            "lastMessageSender" to "",
            "lastReadAt" to 0L,
            "members" to listOf(me, peer.userId)
        )

        val updates = hashMapOf<String, Any>(
            "/userThreads/$me/$threadId" to meThread,
            "/userThreads/${peer.userId}/$threadId" to peerThread,
            "/threadMeta/$threadId/members" to listOf(me, peer.userId)
        )
        root.updateChildren(updates)
        return threadId
    }

    fun sendMessage(threadId: String, peerUid: String, text: String) {
        val me = myUid() ?: return
        val t = text.trim()
        if (t.isEmpty()) return

        val ref = root.child("chatMessages").child(threadId).push()
        val msgId = ref.key ?: java.util.UUID.randomUUID().toString()
        val payload = mapOf(
            "senderUid" to me,
            "text" to t,
            "createdAt" to ServerValue.TIMESTAMP
        )
        ref.setValue(payload)

        val now = System.currentTimeMillis()
        val updates = hashMapOf<String, Any>(
            "/userThreads/$me/$threadId/lastMessageAt" to now,
            "/userThreads/$me/$threadId/lastMessageText" to t,
            "/userThreads/$me/$threadId/lastMessageSender" to me,
            "/userThreads/$me/$threadId/lastReadAt" to now,
            "/userThreads/$peerUid/$threadId/lastMessageAt" to now,
            "/userThreads/$peerUid/$threadId/lastMessageText" to t,
            "/userThreads/$peerUid/$threadId/lastMessageSender" to me
        )
        root.updateChildren(updates)
    }

    fun sendImageMessage(
        threadId: String,
        peerUid: String,
        bitmap: Bitmap,
        expiresInSeconds: Int?,
        previewText: String
    ) {
        val me = myUid() ?: return
        val b64 = bitmapToBase64(bitmap)
        if (b64.isBlank()) return

        val now = System.currentTimeMillis()
        val expiresAtMs = if (expiresInSeconds != null && expiresInSeconds > 0) {
            now + expiresInSeconds * 1000L
        } else 0L

        val ref = root.child("chatMessages").child(threadId).push()
        val msgId = ref.key ?: java.util.UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any>(
            "senderUid" to me,
            "text" to "",
            "imageBase64" to b64,
            "createdAt" to ServerValue.TIMESTAMP
        )
        if (expiresAtMs > 0L) payload["imageExpiresAtMs"] = expiresAtMs
        ref.setValue(payload)

        val updates = hashMapOf<String, Any>(
            "/userThreads/$me/$threadId/lastMessageAt" to now,
            "/userThreads/$me/$threadId/lastMessageText" to previewText,
            "/userThreads/$me/$threadId/lastMessageSender" to me,
            "/userThreads/$me/$threadId/lastReadAt" to now,
            "/userThreads/$peerUid/$threadId/lastMessageAt" to now,
            "/userThreads/$peerUid/$threadId/lastMessageText" to previewText,
            "/userThreads/$peerUid/$threadId/lastMessageSender" to me
        )
        root.updateChildren(updates)

        if (expiresInSeconds != null && expiresInSeconds > 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                root.child("chatMessages").child(threadId).child(msgId).removeValue()
            }, expiresInSeconds * 1000L)
        }
    }

    fun sendProfileReminderToIncompleteUserIfNeeded(peerUid: String, isPeerComplete: Boolean) {
        if (isPeerComplete) return

        val today = (System.currentTimeMillis() / 86400000L).toInt()
        val ref = root.child("crew_user_meta").child(peerUid).child("profileReminderDay")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lastDay = (snapshot.value as? Number)?.toInt() ?: 0
                if (lastDay >= today) return
                sendSystemReminderMessage(peerUid)
                ref.setValue(today)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun sendSystemReminderMessage(peerUid: String) {
        val ctx = com.google.firebase.FirebaseApp.getInstance().applicationContext
        val message = ctx.getString(it.grg.flighttimeapp.R.string.cl_profile_incomplete_chat_message)
        val systemName = ctx.getString(it.grg.flighttimeapp.R.string.cl_system_sender_name)
        val now = System.currentTimeMillis()
        val threadId = "thread_system_$peerUid"

        val msgRef = root.child("chatMessages").child(threadId).push()
        val msgId = msgRef.key ?: java.util.UUID.randomUUID().toString()
        val msgPayload = mapOf(
            "senderUid" to "system",
            "text" to message,
            "createdAt" to ServerValue.TIMESTAMP
        )

        val threadPayload = mapOf(
            "peerId" to "system",
            "peerNickname" to systemName,
            "peerCompany" to "",
            "createdAt" to now,
            "lastMessageAt" to now,
            "lastMessageText" to message,
            "lastMessageSender" to "system",
            "lastReadAt" to 0L,
            "members" to listOf(peerUid, "system")
        )

        val updates = hashMapOf<String, Any>(
            "/chatMessages/$threadId/$msgId" to msgPayload,
            "/userThreads/$peerUid/$threadId" to threadPayload,
            "/threadMeta/$threadId/members" to listOf(peerUid, "system")
        )
        root.updateChildren(updates)
    }

    fun markThreadRead(threadId: String) {
        val me = myUid() ?: return
        val now = System.currentTimeMillis()
        root.child("userThreads").child(me).child(threadId).child("lastReadAt").setValue(now)
    }

    fun deleteChat(threadId: String, peerUid: String) {
        val me = myUid() ?: return
        root.child("userThreads").child(me).child(threadId).removeValue()

        val metaRef = root.child("threadMeta").child(threadId)
        metaRef.child("deletedBy").child(me).setValue(true)

        metaRef.child("deletedBy").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val dict = snapshot.value as? Map<*, *> ?: emptyMap<Any, Any>()
                val meDeleted = dict.containsKey(me)
                val peerDeleted = dict.containsKey(peerUid)
                if (meDeleted && peerDeleted) {
                    val updates = hashMapOf<String, Any?>(
                        "/chatMessages/$threadId" to null,
                        "/threadMeta/$threadId" to null,
                        "/userThreads/$me/$threadId" to null,
                        "/userThreads/$peerUid/$threadId" to null
                    )
                    root.updateChildren(updates)
                }
                stopMessagesObserver(threadId)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun deleteAllChatsForMe() {
        val me = myUid() ?: return
        root.child("userThreads").child(me).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { cs ->
                    val threadId = cs.key ?: return@forEach
                    val dict = cs.value as? Map<*, *> ?: return@forEach
                    val peerUid = dict["peerId"] as? String ?: return@forEach
                    deleteChat(threadId, peerUid)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun clearThreadMessages(threadId: String, peerUid: String) {
        val me = myUid() ?: return
        val now = System.currentTimeMillis()
        root.child("chatMessages").child(threadId).removeValue()

        val updates = hashMapOf<String, Any>(
            "/userThreads/$me/$threadId/lastMessageAt" to now,
            "/userThreads/$me/$threadId/lastMessageText" to "",
            "/userThreads/$me/$threadId/lastMessageSender" to "",
            "/userThreads/$me/$threadId/lastReadAt" to now,
            "/userThreads/$peerUid/$threadId/lastMessageAt" to now,
            "/userThreads/$peerUid/$threadId/lastMessageText" to "",
            "/userThreads/$peerUid/$threadId/lastMessageSender" to ""
        )
        root.updateChildren(updates)

        val map = _messagesByThread.value?.toMutableMap() ?: mutableMapOf()
        map[threadId] = emptyList()
        _messagesByThread.postValue(map)
    }

    private fun decodeThread(threadId: String, dict: Map<*, *>): CrewChatThread? {
        val peerId = dict["peerId"] as? String ?: return null
        val peerNickname = dict["peerNickname"] as? String ?: ""
        val peerCompany = dict["peerCompany"] as? String
        val createdAt = Date(readMs(dict["createdAt"]))
        val lastMessageAt = Date(readMs(dict["lastMessageAt"]))
        val lastMessageText = dict["lastMessageText"] as? String
        val lastMessageSender = dict["lastMessageSender"] as? String
        val lastReadAt = readMs(dict["lastReadAt"]).let { if (it == 0L) null else Date(it) }
        val members = (dict["members"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return CrewChatThread(
            id = threadId,
            peerId = peerId,
            peerNickname = peerNickname,
            peerCompany = peerCompany,
            createdAt = createdAt,
            lastMessageAt = lastMessageAt,
            lastMessageText = lastMessageText,
            lastMessageSender = lastMessageSender,
            lastReadAt = lastReadAt,
            members = members
        )
    }

    private fun decodeMessage(msgId: String, threadId: String, dict: Map<*, *>): CrewChatMessage {
        val sender = dict["senderUid"] as? String ?: ""
        val text = dict["text"] as? String ?: ""
        val imageBase64 = dict["imageBase64"] as? String
        val imageExpiresAtMs = readMs(dict["imageExpiresAtMs"])
        val createdAtMs = readMs(dict["createdAt"])
        return CrewChatMessage(
            id = msgId,
            threadId = threadId,
            senderUid = sender,
            text = text,
            imageBase64 = imageBase64,
            imageExpiresAtMs = imageExpiresAtMs,
            createdAt = Date(createdAtMs)
        )
    }

    private fun readMs(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            else -> 0L
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object {
        val shared = CrewLayoverChatStore()
    }
}
