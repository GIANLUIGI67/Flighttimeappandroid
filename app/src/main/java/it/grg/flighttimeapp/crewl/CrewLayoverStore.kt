package it.grg.flighttimeapp.crewl

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.location.Location
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import it.grg.flighttimeapp.R
import java.util.Date
import java.util.UUID
import kotlin.math.abs

class CrewLayoverStore private constructor() {

    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference

    private var isStarted = false
    private var appContext: Context? = null

    private var myUserId: String? = null
    private var lastLocation: Location? = null

    private var usersHandle: ValueEventListener? = null
    private var eventsHandle: ValueEventListener? = null
    private var eventMembersHandle: ValueEventListener? = null
    private var invitesHandle: ValueEventListener? = null

    private var eventMessagesHandle: ChildEventListener? = null
    private var eventMessagesRef: DatabaseReference? = null

    private val usersCache: MutableMap<String, Map<String, Any?>> = mutableMapOf()

    private val _settingsLive = MutableLiveData(CrewLayoverSettings())
    val settingsLive: LiveData<CrewLayoverSettings> = _settingsLive

    private val _onlineNow = MutableLiveData<List<NearbyCrewUser>>(emptyList())
    val onlineNow: LiveData<List<NearbyCrewUser>> = _onlineNow

    private val _activeLast24h = MutableLiveData<List<NearbyCrewUser>>(emptyList())
    val activeLast24h: LiveData<List<NearbyCrewUser>> = _activeLast24h

    private val _onlineNearbyCount = MutableLiveData(0)
    val onlineNearbyCount: LiveData<Int> = _onlineNearbyCount

    private val _activeEvents = MutableLiveData<List<CrewLayoverEvent>>(emptyList())
    val activeEvents: LiveData<List<CrewLayoverEvent>> = _activeEvents

    private val _joinedEventIds = MutableLiveData<Set<String>>(emptySet())
    val joinedEventIds: LiveData<Set<String>> = _joinedEventIds

    private val _eventChatMessages = MutableLiveData<List<CrewEventMessage>>(emptyList())
    val eventChatMessages: LiveData<List<CrewEventMessage>> = _eventChatMessages

    private val _hasIncomingInvitation = MutableLiveData(false)
    val hasIncomingInvitation: LiveData<Boolean> = _hasIncomingInvitation

    private var isCreatingEvent = false
    private val roleSetKey = "role_set_v1"
    private val distanceUnlimitedKey = "distance_unlimited_v1"
    private val distanceMaxKmKey = "distance_max_km_v1"

    private var distanceUnlimited: Boolean = true
    private var distanceMaxKm: Double = 50.0

    fun isUserProfileComplete(userId: String): Boolean {
        val dict = usersCache[userId] ?: return false
        val nickname = (dict["nickname"] as? String)?.trim().orEmpty()
        val roleRaw = dict["role"] as? String
        val photoB64 = (dict["photoB64"] as? String)?.trim().orEmpty()
        val nicknameOk = nickname.isNotEmpty()
        val roleOk = roleRaw != null && CrewRole.fromRaw(roleRaw).raw == roleRaw
        val photoOk = photoB64.isNotEmpty()
        return nicknameOk && roleOk && photoOk
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        CrewPhotoLoader.init(appContext!!)
        loadSettings()
    }

    fun start(userId: String) {
        val uid = userId.trim()
        if (uid.isEmpty()) return
        if (isStarted) return
        isStarted = true
        myUserId = uid

        refreshNow()
        maybeSendProfileReminder()
        startUsersObserver()
        startEventsObserver()
        startEventMembersObserver()
        startInvitesObserver()
    }

    fun stop() {
        stopAllObservers()
        isStarted = false
        myUserId = null
        _onlineNow.postValue(emptyList())
        _activeLast24h.postValue(emptyList())
        _onlineNearbyCount.postValue(0)
        _activeEvents.postValue(emptyList())
        _joinedEventIds.postValue(emptySet())
        _eventChatMessages.postValue(emptyList())
        _hasIncomingInvitation.postValue(false)
    }

    fun refreshNow() {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        upsertMyProfile(uid)
        updateMyPresence(uid)
        rebuildNearbyListsFromCache()
    }

    fun getDistanceUnlimited(): Boolean = distanceUnlimited

    fun getDistanceMaxKm(): Double = distanceMaxKm

    fun setDistanceFilter(unlimited: Boolean, maxKm: Double) {
        val clamped = maxKm.coerceIn(0.0, 5000.0)
        distanceUnlimited = unlimited
        distanceMaxKm = clamped
        prefs()?.edit {
            putBoolean(distanceUnlimitedKey, unlimited)
            putFloat(distanceMaxKmKey, clamped.toFloat())
        }
        rebuildNearbyListsFromCache()
    }

    fun updateMyLocation(loc: Location) {
        lastLocation = loc
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        updateMyPresence(uid)
        rebuildNearbyListsFromCache()
    }

    fun updateSettings(update: (CrewLayoverSettings) -> CrewLayoverSettings) {
        val current = _settingsLive.value ?: CrewLayoverSettings()
        val next = update(current)
        if (next.role != current.role) {
            markRoleSet()
        }
        _settingsLive.postValue(next)
        saveSettings(next)
        refreshNow()
    }

    fun updateMyPhotoBase64(b64: String) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val trimmed = b64.trim()
        val ref = root.child("crew_users").child(uid)
        if (trimmed.isEmpty()) {
            ref.child("photoB64").removeValue()
        } else {
            ref.updateChildren(mapOf("photoB64" to trimmed))
        }
    }

    fun createEvent(defaultRadiusKm: Double, expiresHours: Double): String? {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return null
        if (isCreatingEvent) return null
        isCreatingEvent = true

        val now = Date()
        val nowMs = now.time
        val draft = _eventDraft

        val dtMs = draft.dateTime.time
        val expiresAt = draft.expirationDateTime ?: Date(now.time + (expiresHours * 3600_000L).toLong())
        val expiresMs = expiresAt.time
        val settings = _settingsLive.value ?: CrewLayoverSettings()

        val loc = lastLocation
        val lat = loc?.latitude ?: 0.0
        val lon = loc?.longitude ?: 0.0

        val eventId = root.child("events").push().key ?: UUID.randomUUID().toString()
        val payload = mapOf(
            "meetingTypeRaw" to draft.meetingType.raw,
            "whereText" to draft.whereText,
            "dateTimeMs" to dtMs,
            "createdAtMs" to nowMs,
            "createdAt" to nowMs,
            "expiresAtMs" to expiresMs,
            "creatorUid" to uid,
            "lat" to lat,
            "lon" to lon,
            "radiusKm" to defaultRadiusKm,
            "acceptedCount" to 0,
            "isClosed" to false,
            "sendToAllNearby" to draft.sendToAllNearby
        )

        root.child("events").child(eventId).setValue(payload)

        if (draft.sendToAllNearby) {
            root.child("crew_users").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { cs ->
                        val otherUid = cs.key ?: return@forEach
                        if (otherUid == uid) return@forEach
                        root.child("user_event_invites").child(otherUid).child(eventId).setValue(true)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
        }

        val eventTitle = draft.whereText.trim().ifEmpty {
            appContext?.getString(draft.meetingType.labelResId).orEmpty()
        }
        if (settings.eventRemindersEnabled && draft.alarmOption != AlarmOption.NONE) {
            scheduleEventReminder(eventId, eventTitle, dtMs, draft.alarmOption)
        }
        scheduleEventExpiry(eventId, expiresMs)

        isCreatingEvent = false

        return eventId
    }

    fun joinEvent(eventId: String, alarmOverride: AlarmOption? = null) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        root.child("event_members").child(eventId).child(uid).setValue(true)
        val current = _joinedEventIds.value ?: emptySet()
        _joinedEventIds.postValue(current + eventId)

        val settings = _settingsLive.value ?: CrewLayoverSettings()
        val alarm = alarmOverride ?: _eventDraft.alarmOption
        if (!settings.eventRemindersEnabled || alarm == AlarmOption.NONE) return
        val event = _activeEvents.value?.firstOrNull { it.id == eventId } ?: return
        val eventTitle = event.whereText.trim().ifEmpty {
            val mt = MeetingType.fromRaw(event.meetingTypeRaw)
            appContext?.getString(mt.labelResId).orEmpty()
        }
        scheduleEventReminder(eventId, eventTitle, event.eventAtMs, alarm)
    }

    fun leaveEvent(eventId: String) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        root.child("event_members").child(eventId).child(uid).removeValue()
        val current = _joinedEventIds.value ?: emptySet()
        _joinedEventIds.postValue(current - eventId)
    }

    fun canDeleteEvent(eventId: String): Boolean {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val event = _activeEvents.value?.firstOrNull { it.id == eventId } ?: return false
        return event.creatorUid == uid
    }

    fun deleteEvent(eventId: String, onDone: (Boolean) -> Unit = {}) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onDone(false)
            return
        }

        root.child("events").child(eventId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val creatorUid = snapshot.child("creatorUid").getValue(String::class.java) ?: ""
                if (creatorUid != uid) {
                    onDone(false)
                    return
                }

                cancelEventReminder(eventId)
                cancelEventExpiry(eventId)
                root.child("events").child(eventId).removeValue()
                root.child("event_members").child(eventId).removeValue()
                root.child("event_messages").child(eventId).removeValue()

                root.child("user_event_invites").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(invitesSnap: DataSnapshot) {
                        invitesSnap.children.forEach { userSnap ->
                            userSnap.ref.child(eventId).removeValue()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })

                root.child("event_hidden").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(hiddenSnap: DataSnapshot) {
                        hiddenSnap.children.forEach { userSnap ->
                            userSnap.ref.child(eventId).removeValue()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })

                _activeEvents.postValue((_activeEvents.value ?: emptyList()).filter { it.id != eventId })
                _joinedEventIds.postValue((_joinedEventIds.value ?: emptySet()) - eventId)
                onDone(true)
            }

            override fun onCancelled(error: DatabaseError) {
                onDone(false)
            }
        })
    }

    fun hideEventFromMyList(eventId: String) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        root.child("event_hidden").child(uid).child(eventId).setValue(true)
        _activeEvents.postValue((_activeEvents.value ?: emptyList()).filter { it.id != eventId })
    }

    fun sendEventMessage(eventId: String, text: String) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val t = text.trim()
        if (t.isEmpty()) return

        val ref = root.child("event_messages").child(eventId).push()
        val msgId = ref.key ?: UUID.randomUUID().toString()
        val payload = mapOf(
            "senderUid" to uid,
            "text" to t,
            "createdAt" to ServerValue.TIMESTAMP
        )
        ref.setValue(payload)

        val local = CrewEventMessage(
            id = msgId,
            eventId = eventId,
            senderUid = uid,
            text = t,
            imageBase64 = null,
            imageExpiresAtMs = 0L,
            createdAt = Date()
        )
        val current = _eventChatMessages.value?.toMutableList() ?: mutableListOf()
        if (current.none { it.id == msgId }) {
            current.add(local)
            current.sortBy { it.createdAt }
            _eventChatMessages.postValue(current)
        }
    }

    fun sendEventImageMessage(eventId: String, bitmap: Bitmap, expiresInSeconds: Int?) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val b64 = bitmapToBase64(bitmap)
        if (b64.isBlank()) return

        val now = System.currentTimeMillis()
        val expiresAtMs = if (expiresInSeconds != null && expiresInSeconds > 0) {
            now + expiresInSeconds * 1000L
        } else 0L

        val ref = root.child("event_messages").child(eventId).push()
        val msgId = ref.key ?: UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any>(
            "senderUid" to uid,
            "text" to "",
            "imageBase64" to b64,
            "createdAt" to ServerValue.TIMESTAMP
        )
        if (expiresAtMs > 0L) payload["imageExpiresAtMs"] = expiresAtMs
        ref.setValue(payload)

        if (expiresInSeconds != null && expiresInSeconds > 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                root.child("event_messages").child(eventId).child(msgId).removeValue()
            }, expiresInSeconds * 1000L)
        }
    }

    fun openEventChat(eventId: String) {
        closeEventChat()
        val ref = root.child("event_messages").child(eventId)
        eventMessagesRef = ref
        val handle = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val dict = snapshot.value as? Map<*, *> ?: return
                val msg = decodeEventMessage(snapshot.key ?: return, eventId, dict)
                val current = _eventChatMessages.value?.toMutableList() ?: mutableListOf()
                if (current.none { it.id == msg.id }) {
                    current.add(msg)
                    current.sortBy { it.createdAt }
                    _eventChatMessages.postValue(current)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(handle)
        eventMessagesHandle = handle
    }

    fun closeEventChat() {
        eventMessagesHandle?.let { h ->
            eventMessagesRef?.removeEventListener(h)
        }
        eventMessagesHandle = null
        eventMessagesRef = null
        _eventChatMessages.postValue(emptyList())
    }

    private fun startUsersObserver() {
        val ref = root.child("crew_users")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usersCache.clear()
                snapshot.children.forEach { c ->
                    val key = c.key ?: return@forEach
                    val dictAny = c.value as? Map<*, *> ?: return@forEach
                    val dict = dictAny.entries
                        .mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                        .toMap()
                    usersCache[key] = dict
                }
                rebuildNearbyListsFromCache()
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        usersHandle = listener
    }

    private fun startEventsObserver() {
        val ref = root.child("events")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rebuildEventsList(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        eventsHandle = listener
    }

    private fun startEventMembersObserver() {
        val ref = root.child("event_members")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                refreshEventsFromDb()
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        eventMembersHandle = listener
    }

    private fun startInvitesObserver() {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = root.child("user_event_invites").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _hasIncomingInvitation.postValue(snapshot.childrenCount > 0)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        invitesHandle = listener
    }

    private fun stopAllObservers() {
        usersHandle?.let { root.child("crew_users").removeEventListener(it) }
        eventsHandle?.let { root.child("events").removeEventListener(it) }
        eventMembersHandle?.let { root.child("event_members").removeEventListener(it) }
        val uid = myUserId
        if (uid != null) {
            invitesHandle?.let { root.child("user_event_invites").child(uid).removeEventListener(it) }
        }
        usersHandle = null
        eventsHandle = null
        eventMembersHandle = null
        invitesHandle = null
        closeEventChat()
    }

    private fun refreshEventsFromDb() {
        root.child("events").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rebuildEventsList(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun rebuildNearbyListsFromCache() {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val nowMs = System.currentTimeMillis()
        val cutoffMs = nowMs - 24 * 60 * 60 * 1000L
        val settings = _settingsLive.value ?: CrewLayoverSettings()

        val myRole = settings.role
        val myBase = settings.baseCountryCode.trim()

        val visibleUsers = mutableListOf<NearbyCrewUser>()
        usersCache.forEach { (otherUid, dict) ->
            if (otherUid == uid) return@forEach

            val nickname = dict["nickname"] as? String ?: "Crew"
            val company = dict["companyName"] as? String ?: ""
            val baseCode = dict["baseCountryCode"] as? String ?: ""
            val phone = dict["phoneNumber"] as? String
            val roleRaw = dict["role"] as? String
            val role = CrewRole.fromRaw(roleRaw)
            val isOnline = dict["isOnline"] as? Boolean ?: false
            val lat = (dict["lat"] as? Number)?.toDouble() ?: 0.0
            val lon = (dict["lon"] as? Number)?.toDouble() ?: 0.0
            val lastSeenMs = (dict["lastSeenMs"] as? Number)?.toLong() ?: 0L
            val visRaw = dict["visibilityMode"] as? String
            val visibility = CrewVisibilityMode.fromRaw(visRaw)
            val excluded = (dict["excludedBaseCodes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val photoB64 = dict["photoB64"] as? String

            if (!viewerCanSeeOther(
                    viewerRole = myRole,
                    viewerBase = myBase,
                    otherRole = role,
                    otherBase = baseCode.trim(),
                    otherVisibility = visibility,
                    otherExcludedBases = excluded
                )) {
                return@forEach
            }

            val dist = distanceKm(lastLocation, lat, lon)
            if (!distanceUnlimited && dist >= 0 && dist > distanceMaxKm) {
                return@forEach
            }
            val user = NearbyCrewUser(
                userId = otherUid,
                nickname = nickname,
                companyName = if (company.isBlank()) null else company,
                baseCountryCode = baseCode,
                phoneNumber = phone,
                role = role,
                visibilityMode = visibility,
                excludedBaseCodes = excluded,
                isOnline = isOnline,
                lastSeenMs = lastSeenMs,
                lat = lat,
                lon = lon,
                distanceKm = dist,
                photoB64 = photoB64
            )
            visibleUsers.add(user)
        }

        visibleUsers.sortBy { it.distanceKm }
        val online = visibleUsers.filter { it.isOnline }
        val last24 = visibleUsers.filter { !it.isOnline && it.lastSeenMs >= cutoffMs }

        _onlineNow.postValue(online)
        _activeLast24h.postValue(last24)
        _onlineNearbyCount.postValue(online.size)
    }

    private fun rebuildEventsList(snapshot: DataSnapshot) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val nowMs = System.currentTimeMillis()

        root.child("event_hidden").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(hiddenSnap: DataSnapshot) {
                val hiddenIds = hiddenSnap.children.mapNotNull { it.key }.toSet()

                root.child("event_members").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(membersSnap: DataSnapshot) {
                        val countsByEvent = mutableMapOf<String, Int>()
                        val joinedIds = mutableSetOf<String>()
                        membersSnap.children.forEach { cs ->
                            val eventId = cs.key ?: return@forEach
                            countsByEvent[eventId] = cs.childrenCount.toInt()
                            if (cs.child(uid).exists()) joinedIds.add(eventId)
                        }

                        val events = mutableListOf<CrewLayoverEvent>()
                        snapshot.children.forEach { cs ->
                            val eventId = cs.key ?: return@forEach
                            if (hiddenIds.contains(eventId)) return@forEach
                            val dictAny = cs.value as? Map<*, *> ?: return@forEach
                            val dict = dictAny.entries
                                .mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                                .toMap()

                            val expiresAtMs = (dict["expiresAtMs"] as? Number)?.toLong() ?: 0L
                            if (expiresAtMs in 1..<nowMs) {
                                cleanupExpiredEvent(eventId)
                                return@forEach
                            }

                            val meetingRaw = dict["meetingTypeRaw"] as? String ?: "other"
                            val whereText = dict["whereText"] as? String ?: ""
                            val dateTimeMs = (dict["dateTimeMs"] as? Number)?.toLong() ?: 0L
                            val createdAtMs = (dict["createdAtMs"] as? Number)?.toLong() ?: 0L
                            val creatorUid = dict["creatorUid"] as? String ?: ""
                            val lat = (dict["lat"] as? Number)?.toDouble() ?: 0.0
                            val lon = (dict["lon"] as? Number)?.toDouble() ?: 0.0
                            val radiusKm = (dict["radiusKm"] as? Number)?.toDouble() ?: 0.0
                            val accepted = countsByEvent[eventId] ?: 0
                            val isClosed = dict["isClosed"] as? Boolean ?: false
                            val sendToAllNearby = dict["sendToAllNearby"] as? Boolean ?: true

                            val e = CrewLayoverEvent(
                                id = eventId,
                                meetingTypeRaw = meetingRaw,
                                whereText = whereText,
                                creatorUid = creatorUid,
                                creatorNickname = "",
                                creatorCompany = null,
                                createdAtMs = createdAtMs,
                                eventAtMs = dateTimeMs,
                                expiresAtMs = expiresAtMs,
                                sendToAllNearby = sendToAllNearby,
                                isClosed = isClosed,
                                lat = lat,
                                lon = lon,
                                radiusKm = radiusKm,
                                acceptedCount = accepted
                            )
                            events.add(e)
                        }
                        events.sortBy { it.eventAtMs }
                        _activeEvents.postValue(events)
                        _joinedEventIds.postValue(joinedIds)
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun upsertMyProfile(uid: String) {
        val settings = _settingsLive.value ?: CrewLayoverSettings()
        val loc = lastLocation

        val payload = mutableMapOf<String, Any?>()
        payload["nickname"] = settings.nickname.trim().ifEmpty { "Crew" }
        payload["companyName"] = settings.companyName?.trim()?.ifEmpty { null }
        payload["baseCountryCode"] = settings.baseCountryCode.trim()
        payload["phoneNumber"] = settings.phoneNumber?.trim()?.ifEmpty { null }
        payload["role"] = settings.role.raw
        payload["visibilityMode"] = settings.visibilityMode.raw
        payload["excludedBaseCodes"] = settings.excludedBaseCodes
        payload["isEnabled"] = settings.isEnabled
        payload["lat"] = loc?.latitude ?: 0.0
        payload["lon"] = loc?.longitude ?: 0.0
        payload["lastSeenMs"] = ServerValue.TIMESTAMP

        root.child("crew_users").child(uid).updateChildren(payload)
    }

    private fun cleanupExpiredEvent(eventId: String) {
        cancelEventReminder(eventId)
        cancelEventExpiry(eventId)
        root.child("events").child(eventId).removeValue()
        root.child("event_members").child(eventId).removeValue()
        root.child("event_messages").child(eventId).removeValue()

        root.child("user_event_invites").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(invitesSnap: DataSnapshot) {
                invitesSnap.children.forEach { userSnap ->
                    userSnap.ref.child(eventId).removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        root.child("event_hidden").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(hiddenSnap: DataSnapshot) {
                hiddenSnap.children.forEach { userSnap ->
                    userSnap.ref.child(eventId).removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateMyPresence(uid: String) {
        val loc = lastLocation
        val update = mutableMapOf<String, Any?>()
        update["lat"] = loc?.latitude ?: 0.0
        update["lon"] = loc?.longitude ?: 0.0
        update["lastSeenMs"] = ServerValue.TIMESTAMP
        root.child("crew_users").child(uid).updateChildren(update)
    }

    private fun viewerCanSeeOther(
        viewerRole: CrewRole,
        viewerBase: String,
        otherRole: CrewRole,
        otherBase: String,
        otherVisibility: CrewVisibilityMode,
        otherExcludedBases: List<String>
    ): Boolean {
        val vBase = viewerBase.trim()
        val oBase = otherBase.trim()

        if (vBase.isNotEmpty() && otherExcludedBases.any { it.equals(vBase, true) }) {
            return false
        }

        val sameBase = vBase.isNotEmpty() && oBase.isNotEmpty() && vBase.equals(oBase, true)

        return when (otherVisibility) {
            CrewVisibilityMode.EVERYONE -> true
            CrewVisibilityMode.SAME_ROLE_ONLY -> viewerRole == otherRole
            CrewVisibilityMode.SAME_BASE_ONLY -> sameBase
            CrewVisibilityMode.SAME_COUNTRY_CODE_ONLY -> sameBase
            CrewVisibilityMode.CABIN_CREW_ALL -> viewerRole == CrewRole.CABIN_CREW
            CrewVisibilityMode.FLIGHT_DECK_ALL -> viewerRole == CrewRole.FLIGHT_DECK
            CrewVisibilityMode.CABIN_CREW_NOT_BASE -> viewerRole == CrewRole.CABIN_CREW && !sameBase
            CrewVisibilityMode.FLIGHT_DECK_NOT_BASE -> viewerRole == CrewRole.FLIGHT_DECK && !sameBase
        }
    }

    private fun distanceKm(loc: Location?, lat: Double, lon: Double): Double {
        if (loc == null) return -1.0
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return -1.0
        if (lat == 0.0 && lon == 0.0) return -1.0
        if (loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) return -1.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return -1.0
        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
        return abs(results[0]) / 1000.0
    }

    private fun decodeEventMessage(msgId: String, eventId: String, dict: Map<*, *>): CrewEventMessage {
        val sender = dict["senderUid"] as? String ?: ""
        val text = dict["text"] as? String ?: ""
        val imageBase64 = dict["imageBase64"] as? String
        val imageExpiresAtMs = readMs(dict["imageExpiresAtMs"])
        val createdAtMs = (dict["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        return CrewEventMessage(
            id = msgId,
            eventId = eventId,
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
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val bytes = baos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private val _eventDraft = CrewLayoverEventDraft()

    fun eventDraft(): CrewLayoverEventDraft = _eventDraft

    fun updateEventDraft(update: (CrewLayoverEventDraft) -> CrewLayoverEventDraft) {
        val next = update(_eventDraft)
        _eventDraft.isEnabled = next.isEnabled
        _eventDraft.dateTime = next.dateTime
        _eventDraft.whereText = next.whereText
        _eventDraft.meetingType = next.meetingType
        _eventDraft.expirationDateTime = next.expirationDateTime
        _eventDraft.alarmOption = next.alarmOption
        _eventDraft.sendToAllNearby = next.sendToAllNearby
    }

    private fun prefs(): android.content.SharedPreferences? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadSettings() {
        val p = prefs() ?: return
        val s = CrewLayoverSettings(
            nickname = p.getString(KEY_NICK, "") ?: "",
            companyName = p.getString(KEY_COMPANY, null),
            baseCountryCode = p.getString(KEY_BASE, "") ?: "",
            phoneNumber = p.getString(KEY_PHONE, null),
            role = CrewRole.fromRaw(p.getString(KEY_ROLE, CrewRole.CABIN_CREW.raw)),
            visibilityMode = CrewVisibilityMode.fromRaw(p.getString(KEY_VIS, CrewVisibilityMode.EVERYONE.raw)),
            excludedBaseCodes = p.getString(KEY_EXCLUDED, "")?.split(",")?.mapNotNull { it.trim().ifEmpty { null } }?.toMutableList()
                ?: mutableListOf(),
            isEnabled = p.getBoolean(KEY_ENABLED, true),
            eventRemindersEnabled = p.getBoolean(KEY_EVENT_REMINDERS, true)
        )
        distanceUnlimited = p.getBoolean(distanceUnlimitedKey, true)
        distanceMaxKm = p.getFloat(distanceMaxKmKey, 50f).toDouble().coerceIn(0.0, 5000.0)
        _settingsLive.postValue(s)
    }

    private fun saveSettings(settings: CrewLayoverSettings) {
        prefs()?.edit {
            putString(KEY_NICK, settings.nickname)
            putString(KEY_COMPANY, settings.companyName)
            putString(KEY_BASE, settings.baseCountryCode)
            putString(KEY_PHONE, settings.phoneNumber)
            putString(KEY_ROLE, settings.role.raw)
            putString(KEY_VIS, settings.visibilityMode.raw)
            putString(KEY_EXCLUDED, settings.excludedBaseCodes.joinToString(","))
            putBoolean(KEY_ENABLED, settings.isEnabled)
            putBoolean(KEY_EVENT_REMINDERS, settings.eventRemindersEnabled)
        }
    }

    private fun hasSetRole(): Boolean {
        return prefs()?.getBoolean(roleSetKey, false) ?: false
    }

    fun markRoleSet() {
        prefs()?.edit { putBoolean(roleSetKey, true) }
    }

    private fun isProfileComplete(): Boolean {
        val s = _settingsLive.value ?: CrewLayoverSettings()
        val nicknameOk = s.nickname.trim().isNotEmpty()
        val photoOk = CrewPhotoLoader.shared.myLocalProfileImage() != null
        val roleOk = hasSetRole()
        return nicknameOk && photoOk && roleOk
    }

    private fun maybeSendProfileReminder() {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (isProfileComplete()) return

        val today = (System.currentTimeMillis() / 86400000L).toInt()
        val ref = root.child("crew_user_meta").child(uid).child("profileReminderDay")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lastDay = (snapshot.value as? Number)?.toInt() ?: 0
                if (lastDay >= today) return
                sendProfileReminderMessage(uid)
                ref.setValue(today)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun sendProfileReminderMessage(uid: String) {
        val ctx = appContext ?: return
        val message = ctx.getString(R.string.cl_profile_incomplete_chat_message)
        val threadId = "thread_system_$uid"
        val now = System.currentTimeMillis()

        val msgRef = root.child("chatMessages").child(threadId).push()
        val msgId = msgRef.key ?: java.util.UUID.randomUUID().toString()
        val msgPayload = mapOf(
            "senderUid" to "system",
            "text" to message,
            "createdAt" to ServerValue.TIMESTAMP
        )

        val threadPayload = mapOf(
            "peerId" to "system",
            "peerNickname" to "FlightTimeApp",
            "peerCompany" to "",
            "createdAt" to now,
            "lastMessageAt" to now,
            "lastMessageText" to message,
            "lastMessageSender" to "system",
            "lastReadAt" to 0L,
            "members" to listOf(uid, "system")
        )

        val updates = hashMapOf<String, Any>(
            "/chatMessages/$threadId/$msgId" to msgPayload,
            "/userThreads/$uid/$threadId" to threadPayload,
            "/threadMeta/$threadId/members" to listOf(uid, "system")
        )
        root.updateChildren(updates)
    }

    fun cancelEventRemindersForActiveEvents() {
        val events = _activeEvents.value ?: emptyList()
        events.forEach { cancelEventReminder(it.id) }
    }

    private fun scheduleEventReminder(
        eventId: String,
        eventTitle: String,
        eventAtMs: Long,
        alarmOption: AlarmOption
    ) {
        val minutes = alarmOption.minutes ?: return
        val triggerAt = eventAtMs - minutes * 60_000L
        if (triggerAt <= System.currentTimeMillis()) return
        val ctx = appContext ?: return
        val intent = Intent(ctx, EventAlarmReceiver::class.java).apply {
            putExtra(EventAlarmReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(EventAlarmReceiver.EXTRA_EVENT_TITLE, eventTitle)
            putExtra(EventAlarmReceiver.EXTRA_EVENT_AT_MS, eventAtMs)
        }
        val pending = PendingIntent.getBroadcast(
            ctx,
            alarmRequestCode(eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun scheduleEventExpiry(eventId: String, expiresAtMs: Long) {
        if (expiresAtMs <= 0L) return
        if (expiresAtMs <= System.currentTimeMillis()) {
            cleanupExpiredEvent(eventId)
            return
        }
        val ctx = appContext ?: return
        val intent = Intent(ctx, EventExpiryReceiver::class.java).apply {
            putExtra(EventExpiryReceiver.EXTRA_EVENT_ID, eventId)
        }
        val pending = PendingIntent.getBroadcast(
            ctx,
            expiryRequestCode(eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiresAtMs, pending)
    }

    private fun cancelEventReminder(eventId: String) {
        val ctx = appContext ?: return
        val intent = Intent(ctx, EventAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            ctx,
            alarmRequestCode(eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pending)
    }

    private fun cancelEventExpiry(eventId: String) {
        val ctx = appContext ?: return
        val intent = Intent(ctx, EventExpiryReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            ctx,
            expiryRequestCode(eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pending)
    }

    private fun alarmRequestCode(eventId: String): Int = eventId.hashCode()

    private fun expiryRequestCode(eventId: String): Int = eventId.hashCode() xor 0x5f3759df

    companion object {
        val shared = CrewLayoverStore()

        private const val PREFS_NAME = "crew_layover_settings"
        private const val KEY_NICK = "nick"
        private const val KEY_COMPANY = "company"
        private const val KEY_BASE = "base"
        private const val KEY_PHONE = "phone"
        private const val KEY_ROLE = "role"
        private const val KEY_VIS = "visibility"
        private const val KEY_EXCLUDED = "excluded"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EVENT_REMINDERS = "event_reminders"
    }
}
