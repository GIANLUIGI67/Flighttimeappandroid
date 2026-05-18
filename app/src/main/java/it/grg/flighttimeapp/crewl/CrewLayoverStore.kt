package it.grg.flighttimeapp.crewl

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.location.Location
import android.util.Log
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException
import it.grg.flighttimeapp.R
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import it.grg.flighttimeapp.CLog

class CrewLayoverStore private constructor() {

    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference

    private var isStarted = false
    private var isStarting = false
    private var appContext: Context? = null

    private var myUserId: String? = null
    private var lastLocation: Location? = null

    private var usersChildHandle: ChildEventListener? = null
    private var usersQueryRef: Query? = null
    private var eventsHandle: ValueEventListener? = null
    private var eventsQueryRef: Query? = null
    private var eventMembersHandle: ValueEventListener? = null
    private var invitesHandle: ValueEventListener? = null

    private var cachedMemberCountsByEvent: Map<String, Int> = emptyMap()
    private var cachedJoinedEventIds: Set<String> = emptySet()
    private var cachedEventMembers: Map<String, List<String>> = emptyMap()

    private var eventMessagesHandle: ChildEventListener? = null
    private var eventMessagesRef: DatabaseReference? = null

    private val usersCache: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var nearbyRebuildRunnable: Runnable? = null
    private var staleSweepRunnable: Runnable? = null

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

    private val _sendToAllNearbyCooldownEndsAtMs = MutableLiveData<Long>(0L)
    val sendToAllNearbyCooldownEndsAtMs: LiveData<Long> = _sendToAllNearbyCooldownEndsAtMs

    private var isCreatingEvent = false
    private var isUploadingPhotos = false
    private val roleSetKey = "role_set_v1"
    private val distanceUnlimitedKey = "distance_unlimited_v1"
    private val distanceMaxKmKey = "distance_max_km_v1"
    private val deviceIdKey = "crew_device_id_v1"
    private val lastUidKey  = "crew_last_uid_v1"  // tracks previous UID for direct cleanup

    private var distanceUnlimited: Boolean = true
    private var distanceMaxKm: Double = 50.0

    data class CrewUserSummary(
        val userId: String,
        val nickname: String,
        val companyName: String?,
        val photoB64: String?,
        val bio: String?,
        val photosB64: List<String>,
        val photoUrl: String? = null,
        val photosUrls: List<String> = emptyList()
    ) {
        /** Returns a unified ordered list of photo refs (Storage URLs preferred, b64 fallback). */
        fun photoRefs(): List<String> {
            return when {
                photosUrls.isNotEmpty() -> photosUrls
                !photoUrl.isNullOrBlank() -> listOf(photoUrl)
                photosB64.isNotEmpty() -> photosB64
                !photoB64.isNullOrBlank() -> listOf(photoB64)
                else -> emptyList()
            }
        }
        fun primaryRef(): String? = photoRefs().firstOrNull()
        fun hasAnyPhoto(): Boolean = photoRefs().isNotEmpty()
    }

    fun getUserSummary(uid: String): CrewUserSummary? {
        val dict = usersCache[uid] ?: return null
        val nickname = (dict["nickname"] as? String)?.ifBlank { "Crew" } ?: "Crew"
        val company = (dict["companyName"] as? String)?.ifBlank { null }
        val photoB64 = dict["photoB64"] as? String
        val bio = (dict["bio"] as? String)?.ifBlank { null }
        val photosB64 = firebaseListStrings(dict["photosB64"])
        val photoUrl = (dict["photoUrl"] as? String)?.ifBlank { null }
        val photosUrls = firebaseListStrings(dict["photosUrls"])
        return CrewUserSummary(uid, nickname, company, photoB64, bio, photosB64, photoUrl, photosUrls)
    }

    fun fetchUserOnce(uid: String, onDone: (() -> Unit)? = null) {
        root.child("crew_users").child(uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val dictAny = snapshot.value as? Map<*, *> ?: return@addOnSuccessListener
                usersCache[uid] = buildUserDict(uid, dictAny)
                onDone?.invoke()
            }
    }

    fun isUserProfileComplete(userId: String): Boolean {
        val dict = usersCache[userId] ?: return false
        val nickname = (dict["nickname"] as? String)?.trim().orEmpty()
        val roleRaw = dict["role"] as? String
        val nicknameOk = nickname.isNotEmpty()
        val roleOk = roleRaw != null && CrewRole.fromRaw(roleRaw).raw == roleRaw
        // Accept a Storage URL, or a loader-cached bitmap (covers b64 users whose photoB64
        // field was stripped from the dict by buildUserDict to avoid OOM).
        val photoOk = hasPhoto(userId, dict)
        return nicknameOk && roleOk && photoOk
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            CrewPhotoLoader.init(appContext!!)
            loadSettings()
            restoreSendToAllNearbyCooldown()
        }
        startIfPossible()
    }

    private fun restoreSendToAllNearbyCooldown() {
        val ctx = appContext ?: return
        val lastMs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SEND_TO_ALL_NEARBY_MS, 0L)
        if (lastMs <= 0L) return
        val endsAtMs = lastMs + SEND_TO_ALL_NEARBY_COOLDOWN_MS
        if (endsAtMs > System.currentTimeMillis()) {
            _sendToAllNearbyCooldownEndsAtMs.postValue(endsAtMs)
        }
    }

    fun isSendToAllNearbyCoolingDown(): Boolean {
        val endsAt = _sendToAllNearbyCooldownEndsAtMs.value ?: 0L
        return endsAt > System.currentTimeMillis()
    }

    private fun startIfPossible() {
        if (isStarted || isStarting) return
        isStarting = true
        CLog.d(TAG, "startIfPossible begin")
        CrewAuthManager.ensureSignedIn { uid ->
            isStarting = false
            CLog.d(TAG, "startIfPossible uid=${uid ?: "null"}")
            if (uid.isNullOrBlank()) return@ensureSignedIn
            CrewPresenceService.shared.start(uid)
            start(uid)
        }
    }

    fun start(userId: String) {
        val uid = userId.trim()
        if (uid.isEmpty()) return
        if (isStarted) {
            if (myUserId == uid) return
            stop()
        }
        isStarted = true
        myUserId = uid

        CLog.d(TAG, "start store uid=$uid")

        val deviceId = getDeviceId()

        // Capture & update stored previous UID for direct cleanup below.
        // This handles old profiles that predate deviceId tracking (no deviceId in Firebase).
        val prevUid = prefs()?.getString(lastUidKey, null)?.trim()
        prefs()?.edit { putString(lastUidKey, uid) }

        // ✅ FIX: write this UID's deviceId to Firebase FIRST and wait for the
        // server acknowledgement BEFORE running cleanup.  The Firebase rule that
        // allows one device to delete another UID's entry reads
        //   root.child('crew_users').child(auth.uid).child('deviceId')
        // so that value must already be in the database when the delete lands,
        // otherwise the rule evaluates to null → false → delete is silently rejected.
        root.child("crew_users/$uid").updateChildren(
            mapOf(
                "deviceId" to (deviceId ?: ""),
                "isOnline" to true,
                "lastSeenMs" to ServerValue.TIMESTAMP
            )
        ).addOnCompleteListener {
            // Fast-path: delete prev UID stored in SharedPreferences (handles
            // same-installation UID rotation without a Firebase round-trip).
            if (!prevUid.isNullOrEmpty() && prevUid != uid) {
                deleteAllDataForOldUid(prevUid, deviceId ?: "")
            }
            // O(1) device→uid map: find any stale UID from a previous installation
            // on this exact device and delete it before it appears as a ghost user.
            if (deviceId != null) {
                root.child("device_uid_map/$deviceId")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val mappedUid = snapshot.getValue(String::class.java)?.trim()
                            if (!mappedUid.isNullOrEmpty() && mappedUid != uid) {
                                deleteAllDataForOldUid(mappedUid, deviceId)
                            }
                            root.child("device_uid_map/$deviceId").setValue(uid)
                        }
                        override fun onCancelled(error: DatabaseError) {
                            CLog.e(TAG, "device_uid_map read failed: ${error.message}")
                            root.child("device_uid_map/$deviceId").setValue(uid)
                        }
                    })
            }
        }

        refreshNow()
        maybeSendProfileReminder()
        startUsersObserver()
        startEventsObserver()
        startEventMembersObserver()
        startInvitesObserver()
        startStaleSweepTimer()
    }

    fun stop() {
        stopAllObservers()
        stopStaleSweepTimer()
        CrewPresenceService.shared.stop()
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
        CLog.d(TAG, "refreshNow uid=$uid")
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
        // Use setValue (synchronous) so refreshNow() below reads the new settings, not the old ones.
        // updateSettings() is always called from UI (main thread).
        _settingsLive.value = next
        saveSettings(next)
        refreshNow()
    }

    // MARK: - Photo (Firebase Storage)

    /**
     * Uploads all local profile images to Firebase Storage for this uid.
     * Calls [onComplete] with (primaryUrl, allUrls) on the main thread.
     */
    private fun uploadPhotosToStorage(uid: String, onComplete: (photoUrl: String, photosUrls: List<String>) -> Unit) {
        if (isUploadingPhotos) {
            onComplete("", emptyList())
            return
        }
        val ctx = appContext ?: return
        val loader = CrewPhotoLoader.get(ctx)
        val images = buildList<Bitmap> {
            loader.myLocalProfileImage()?.let { add(it) }
            addAll(loader.localProfileExtraImages())
        }.take(5)

        if (images.isEmpty()) {
            onComplete("", emptyList())
            return
        }

        isUploadingPhotos = true
        val storageRef = FirebaseStorage.getInstance().reference.child("crew_photos/$uid")

        // Delete existing files first so orphaned photos from a previous larger set don't accumulate.
        // Ignore 404 — a concurrent upload may have already deleted the file.
        storageRef.listAll().addOnSuccessListener { result ->
            result.items.forEach { ref -> ref.delete().addOnFailureListener { } }
        }

        val urls = mutableListOf<String>()
        var remaining = images.size

        images.forEachIndexed { index, bitmap ->
            val bytes = BitmapUtils.toJpeg(bitmap, 82) ?: run {
                remaining--
                if (remaining == 0) onComplete(urls.firstOrNull() ?: "", urls)
                return@forEachIndexed
            }
            val ref = storageRef.child("photo_$index.jpg")
            val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
            ref.putBytes(bytes, metadata)
                .continueWithTask { task ->
                    if (!task.isSuccessful) throw task.exception!!
                    ref.downloadUrl
                }
                .addOnSuccessListener { uri ->
                    synchronized(urls) { urls.add(uri.toString()) }
                    remaining--
                    if (remaining == 0) {
                        isUploadingPhotos = false
                        onComplete(urls.firstOrNull() ?: "", urls)
                    }
                }
                .addOnFailureListener { e ->
                    CLog.e(TAG, "Storage upload failed for photo $index: ${e.message}")
                    remaining--
                    if (remaining == 0) {
                        isUploadingPhotos = false
                        onComplete(urls.firstOrNull() ?: "", urls)
                    }
                }
        }
    }

    /**
     * Keeps only one RTDB record per physical device.
     * Preference order:
     * 1) the current auth UID if it is present for this device
     * 2) otherwise the record with the latest lastSeenMs
     */
    private fun cleanupDuplicateDeviceEntries(currentUid: String, deviceId: String) {
        root.child("crew_users")
            .orderByChild("deviceId")
            .equalTo(deviceId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val candidates = snapshot.children.mapNotNull { child ->
                        val uid = child.key ?: return@mapNotNull null
                        val lastSeenMs = parseLongValue(child.child("lastSeenMs").value)
                        uid to lastSeenMs
                    }

                    if (candidates.size <= 1) {
                        CLog.d(TAG, "Device cleanup skipped for $deviceId: candidates=${candidates.size}")
                        return
                    }

                    val keepUid = candidates.maxWithOrNull(
                        compareBy<Pair<String, Long>> { it.second }.thenBy { it.first }
                    )?.first ?: return

                    val removedUids = candidates.map { it.first }.filterNot { it == keepUid }
                    CLog.d(
                        TAG,
                        "Device cleanup for $deviceId keep=$keepUid remove=${removedUids.joinToString(",")}"
                    )

                    val deletions = mutableMapOf<String, Any?>()
                    candidates.forEach { (uid, _) ->
                        if (uid == keepUid) return@forEach
                        deletions["crew_users/$uid"] = null
                        deletions["crew_user_meta/$uid"] = null
                        deletions["userTokens/$uid"] = null
                        deletions["user_event_invites/$uid"] = null
                        deletions["event_hidden/$uid"] = null
                        deletions["userThreads/$uid"] = null
                    }

                    if (deletions.isNotEmpty()) {
                        root.updateChildren(deletions)
                            .addOnSuccessListener {
                                CLog.d(TAG, "Device cleanup applied for $deviceId keep=$keepUid removed=${removedUids.size}")
                            }
                            .addOnFailureListener { e ->
                                CLog.e(TAG, "Failed cleaning duplicate device entries for $deviceId: ${e.message}")
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    CLog.e(TAG, "cleanupDuplicateDeviceEntries failed: ${error.message}")
                }
            })
    }

    /**
     * Secondary cleanup for legacy Android duplicates that do not carry a usable deviceId.
     * We collapse records by compulsory profile signature and keep only the newest copy.
     */
    private fun cleanupDuplicateProfileEntries(currentUid: String) {
        root.child("crew_users")
            .orderByChild("lastSeenMs")
            .limitToLast(250)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val groups = mutableMapOf<String, MutableList<Pair<String, Long>>>()
                    snapshot.children.forEach { child ->
                        val uid = child.key ?: return@forEach
                        val dict = child.value as? Map<*, *> ?: return@forEach
                        val key = stableProfileKey(dict) ?: return@forEach
                        val lastSeenMs = parseLongValue(child.child("lastSeenMs").value)
                        groups.getOrPut(key) { mutableListOf() }.add(uid to lastSeenMs)
                    }

                    val deletions = mutableMapOf<String, Any?>()
                    groups.forEach { (_, items) ->
                        if (items.size <= 1) return@forEach
                        val keepUid = items.maxWithOrNull(
                            compareBy<Pair<String, Long>> { it.second }.thenBy { it.first }
                        )?.first ?: return@forEach
                        val removed = items.filterNot { it.first == keepUid }
                        if (removed.isEmpty()) return@forEach
                        CLog.d(
                            TAG,
                            "Profile cleanup keep=$keepUid remove=${removed.joinToString(",") { it.first }}"
                        )
                        removed.forEach { (uid, _) ->
                            if (uid != currentUid) {
                                deletions["crew_users/$uid"] = null
                                deletions["crew_user_meta/$uid"] = null
                                deletions["userTokens/$uid"] = null
                                deletions["user_event_invites/$uid"] = null
                                deletions["event_hidden/$uid"] = null
                                deletions["userThreads/$uid"] = null
                            }
                        }
                    }

                    if (deletions.isNotEmpty()) {
                        root.updateChildren(deletions)
                            .addOnSuccessListener {
                                CLog.d(TAG, "Profile cleanup applied removed=${deletions.size}")
                            }
                            .addOnFailureListener { e ->
                                CLog.e(TAG, "Failed profile cleanup: ${e.message}")
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    CLog.e(TAG, "cleanupDuplicateProfileEntries failed: ${error.message}")
                }
            })
    }

    private fun parseLongValue(value: Any?): Long {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun stableProfileKey(dict: Map<*, *>): String? {
        val nickname = (dict["nickname"] as? String)?.trim()?.lowercase()?.ifBlank { null } ?: return null
        val role = (dict["role"] as? String)?.trim()?.lowercase()?.ifBlank { null } ?: return null
        val countryCode = (dict["baseCountryCode"] as? String)?.trim()?.lowercase()?.ifBlank { null } ?: return null
        val photo = photoSignature(dict) ?: return null
        return "$photo|$nickname|$countryCode|$role"
    }

    private fun photoSignature(dict: Map<*, *>): String? {
        val photosUrls = firebaseListStrings(dict["photosUrls"])
        if (photosUrls.isNotEmpty()) return photosUrls.first().trim().lowercase().ifBlank { null }
        (dict["photoUrl"] as? String)?.trim()?.lowercase()?.ifBlank { null }?.let { return it }
        val photosB64 = firebaseListStrings(dict["photosB64"])
        if (photosB64.isNotEmpty()) return photosB64.first().trim().ifBlank { null }
        return (dict["photoB64"] as? String)?.trim()?.ifBlank { null }
    }

    private fun prefetchNearbyPhoto(user: NearbyCrewUser) {
        val primaryUrl = user.photosUrls.firstOrNull() ?: user.photoUrl
        if (!primaryUrl.isNullOrBlank()) {
            CrewPhotoLoader.shared.prefetchFromUrl(primaryUrl, user.userId)
            return
        }
        val primaryB64 = if (user.photosB64.isNotEmpty()) user.photosB64.first() else user.photoB64
        if (!primaryB64.isNullOrBlank()) {
            CrewPhotoLoader.shared.prefetchFromBase64(user.userId, primaryB64)
        }
    }

    /**
     * Saves [bitmap] as the local primary profile photo, then uploads to Storage
     * and writes the URL to RTDB. Replaces the old updateMyPhotoBase64 path.
     */
    fun uploadMyPhoto(bitmap: Bitmap) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ctx = appContext ?: return
        val loader = CrewPhotoLoader.get(ctx)
        loader.setLocalProfileImage(bitmap)
        loader.invalidate(uid)

        uploadPhotosToStorage(uid) { photoUrl, photosUrls ->
            val ref = root.child("crew_users").child(uid)
            if (photoUrl.isEmpty()) {
                ref.child("photoUrl").removeValue()
                ref.child("photosUrls").removeValue()
                writeUserMeta(uid, _settingsLive.value ?: CrewLayoverSettings(), lastLocation, null, emptyList())
            } else {
                ref.updateChildren(mapOf("photoUrl" to photoUrl, "photosUrls" to photosUrls))
                loader.loadFromUrl(photoUrl, uid)
                writeUserMeta(uid, _settingsLive.value ?: CrewLayoverSettings(), lastLocation, photoUrl, photosUrls)
            }
        }
    }

    /** Legacy shim — decodes base64 into a Bitmap and delegates to uploadMyPhoto. */
    fun updateMyPhotoBase64(b64: String) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val trimmed = b64.trim()
        if (trimmed.isEmpty()) {
            root.child("crew_users").child(uid).child("photoUrl").removeValue()
            root.child("crew_users").child(uid).child("photosUrls").removeValue()
            appContext?.let { CrewPhotoLoader.get(it).invalidate(uid) }
            deleteStoragePhotos(uid)
            writeUserMeta(uid, _settingsLive.value ?: CrewLayoverSettings(), lastLocation, null, emptyList())
        } else {
            val bitmap = appContext?.let { CrewPhotoLoader.get(it).decodeBase64ToBitmap(trimmed) }
            if (bitmap != null) uploadMyPhoto(bitmap)
        }
    }

    /** Deletes all Storage photos for [uid] under crew_photos/{uid}/. */
    private fun deleteStoragePhotos(uid: String) {
        val folder = com.google.firebase.storage.FirebaseStorage.getInstance()
            .reference.child("crew_photos/$uid")
        folder.listAll()
            .addOnSuccessListener { result ->
                result.items.forEach { ref -> ref.delete() }
            }
            .addOnFailureListener { e ->
                if (e is StorageException && e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                    return@addOnFailureListener
                }
                CLog.w(TAG, "deleteStoragePhotos: listAll failed for $uid: ${e.message}")
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

        if (draft.sendToAllNearby && !isSendToAllNearbyCoolingDown()) {
            // Use the already-populated in-memory cache to avoid a full crew_users download
            // (a full snapshot fetch would OOM on devices that still have legacy base64 photos)
            val ctx = appContext
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit { putLong(KEY_LAST_SEND_TO_ALL_NEARBY_MS, System.currentTimeMillis()) }
                _sendToAllNearbyCooldownEndsAtMs.postValue(System.currentTimeMillis() + SEND_TO_ALL_NEARBY_COOLDOWN_MS)
            }
            usersCache.keys.forEach { otherUid ->
                if (otherUid != uid) {
                    root.child("user_event_invites").child(otherUid).child(eventId).setValue(true)
                }
            }
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

                cachedEventMembers[eventId]?.forEach { memberUid ->
                    root.child("user_event_invites").child(memberUid).child(eventId).removeValue()
                }

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

    /**
     * Converts a raw Firebase snapshot value (Map<*, *>) into a memory-safe dict for usersCache.
     * Base64 photo fields are stripped to avoid OOM, but before stripping we decode and cache
     * the bitmap in CrewPhotoLoader so that (a) the photo can still be displayed and (b)
     * hasPhoto() can detect the user has a photo by checking the loader cache.
     * URL-based photos are kept in the dict for lazy async loading at display time.
     *
     * Note: Firebase RTDB returns arrays as ArrayList<*> when keys are sequential integers
     * starting from 0, but may return Map<*,*> in edge cases (single-element arrays on older
     * SDK, or sparse arrays). We handle both forms for robustness.
     */
    private fun buildUserDict(uid: String, rawMap: Map<*, *>): Map<String, Any?> {
        val hasUrlPhoto = !(rawMap["photoUrl"] as? String).isNullOrBlank()
                       || firebaseListStrings(rawMap["photosUrls"]).isNotEmpty()
        if (!hasUrlPhoto) {
            // Synchronously decode the first available b64 photo into the loader cache (512 px).
            // Must be sync: rebuildNearbyListsFromCache runs immediately after buildUserDict and
            // calls hasPhoto() which reads the loader cache. An async decode would always miss
            // the first rebuild window. In practice only a small fraction of the 250-user window
            // has b64-only photos (everyone else has a Storage URL), so the main-thread cost is
            // a few milliseconds total.
            val b64 = firebaseListStrings(rawMap["photosB64"]).firstOrNull()
                   ?: rawMap["photoB64"] as? String
            if (!b64.isNullOrBlank()) {
                appContext?.let { ctx -> CrewPhotoLoader.get(ctx).upsertFromBase64(uid, b64, 512) }
            }
        }
        return rawMap.entries
            .mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
            .filter { (k, _) -> k != "photoB64" && k != "photosB64" }
            .toMap()
    }

    /**
     * Safely extracts a list of strings from a Firebase value that may be either an ArrayList
     * (normal case) or a Map with numeric string keys (Firebase edge case for arrays).
     */
    private fun firebaseListStrings(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { it as? String }
            is Map<*, *> -> value.values.mapNotNull { it as? String }
            else -> emptyList()
        }
    }

    private fun hasPhoto(uid: String, dict: Map<String, Any?>): Boolean {
        val hasUrlPhoto = !(dict["photoUrl"] as? String).isNullOrBlank()
                || firebaseListStrings(dict["photosUrls"]).isNotEmpty()
        if (hasUrlPhoto) return true
        return appContext?.let { CrewPhotoLoader.get(it).image(uid) != null } == true
    }

    private fun startUsersObserver() {
        if (usersChildHandle != null) return
        val ref = root.child("crew_users")
        // keepSynced(true) is intentionally NOT set: it would eagerly cache the entire
        // crew_users tree to disk (including legacy base64 photos) and OOM.

        val query = ref.orderByChild("lastSeenMs").limitToLast(250)

        query.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                CLog.d(TAG, "crew_users initial snapshot count=${snapshot.childrenCount}")
                usersCache.clear()
                snapshot.children.forEach { c ->
                    val key = c.key ?: return@forEach
                    val dictAny = c.value as? Map<*, *> ?: return@forEach
                    usersCache[key] = buildUserDict(key, dictAny)
                }
                CLog.d(TAG, "crew_users cache size=${usersCache.size} (initial)")
                rebuildNearbyListsFromCache()
            }

            override fun onCancelled(error: DatabaseError) {
                CLog.e(TAG, "crew_users initial onCancelled: ${error.message}")
            }
        })

        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val dictAny = snapshot.value as? Map<*, *> ?: return
                val uid = snapshot.key ?: return
                usersCache[uid] = buildUserDict(uid, dictAny)
                CLog.d(TAG, "crew_user_meta childAdded uid=$uid cacheSize=${usersCache.size}")
                scheduleNearbyRebuild()
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val dictAny = snapshot.value as? Map<*, *> ?: return
                val uid = snapshot.key ?: return
                usersCache[uid] = buildUserDict(uid, dictAny)
                CLog.d(TAG, "crew_user_meta childChanged uid=$uid cacheSize=${usersCache.size}")
                scheduleNearbyRebuild()
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                usersCache.remove(snapshot.key)
                CLog.d(TAG, "crew_user_meta childRemoved uid=${snapshot.key} cacheSize=${usersCache.size}")
                scheduleNearbyRebuild()
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                CLog.e(TAG, "crew_user_meta child onCancelled: ${error.message}")
            }
        }
        query.addChildEventListener(childListener)
        usersQueryRef = query
        usersChildHandle = childListener
    }

    private fun startEventsObserver() {
        val nowMs = System.currentTimeMillis()
        val query = root.child("events")
            .orderByChild("expiresAtMs")
            .startAt(nowMs.toDouble())
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rebuildEventsList(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        query.addValueEventListener(listener)
        eventsQueryRef = query
        eventsHandle = listener
    }

    private fun startEventMembersObserver() {
        val ref = root.child("event_members")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val myUid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid
                val counts = mutableMapOf<String, Int>()
                val joined = mutableSetOf<String>()
                val members = mutableMapOf<String, List<String>>()
                snapshot.children.forEach { cs ->
                    val eventId = cs.key ?: return@forEach
                    val uids = cs.children.mapNotNull { it.key }
                    members[eventId] = uids
                    counts[eventId] = uids.size
                    if (myUid != null && cs.child(myUid).exists()) joined.add(eventId)
                }
                cachedMemberCountsByEvent = counts
                cachedJoinedEventIds = joined
                cachedEventMembers = members
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
        usersChildHandle?.let { usersQueryRef?.removeEventListener(it) }
        usersQueryRef = null
        eventsHandle?.let { eventsQueryRef?.removeEventListener(it) }
        eventsQueryRef = null
        eventMembersHandle?.let { root.child("event_members").removeEventListener(it) }
        val uid = myUserId
        if (uid != null) {
            invitesHandle?.let { root.child("user_event_invites").child(uid).removeEventListener(it) }
        }
        usersChildHandle = null
        eventsHandle = null
        eventMembersHandle = null
        invitesHandle = null
        closeEventChat()
    }

    private fun refreshEventsFromDb() {
        val nowMs = System.currentTimeMillis()
        root.child("events")
            .orderByChild("expiresAtMs")
            .startAt(nowMs.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
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

        CLog.d(TAG, "Nearby rebuild start uid=$uid cache=${usersCache.size}")
        val visibleUsers = mutableListOf<NearbyCrewUser>()
        usersCache.forEach { (otherUid, dict) ->
            if (otherUid == uid) return@forEach
            val nickname = (dict["nickname"] as? String)?.trim().orEmpty()
            val company = dict["companyName"] as? String ?: ""
            val baseCode = dict["baseCountryCode"] as? String ?: ""
            val phone = dict["phoneNumber"] as? String
            val bio = dict["bio"] as? String
            val roleRaw = dict["role"] as? String
            val role = CrewRole.fromRaw(roleRaw)
            val isOnline = parseBool(dict["isOnline"])
            val lat = (dict["lat"] as? Number)?.toDouble() ?: 0.0
            val lon = (dict["lon"] as? Number)?.toDouble() ?: 0.0
            val lastSeenMs = (dict["lastSeenMs"] as? Number)?.toLong() ?: 0L
            val visRaw = dict["visibilityMode"] as? String
            val visibility = CrewVisibilityMode.fromRaw(visRaw)
            val excluded = firebaseListStrings(dict["excludedBaseCodes"])
            val stableKey = stableProfileKey(dict)
            val photoKey = photoSignature(dict)

            if (otherUid == uid || nickname.equals("Assma", ignoreCase = true)) {
                CLog.d(
                    TAG,
                    "Nearby identity uid=$otherUid nick=$nickname role=${role.raw} base=$baseCode device=${dict["deviceId"]} online=$isOnline lastSeen=$lastSeenMs photoKey=$photoKey stableKey=$stableKey"
                )
            }

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

            if (!hasPhoto(otherUid, dict)) {
                if (otherUid == uid || nickname.equals("Assma", ignoreCase = true)) {
                    CLog.d(
                        TAG,
                        "Nearby skip no-photo uid=$otherUid nick=$nickname role=${role.raw} base=$baseCode device=${dict["deviceId"]}"
                    )
                }
                return@forEach
            }

            val user = NearbyCrewUser(
                userId = otherUid,
                nickname = nickname,
                companyName = if (company.isBlank()) null else company,
                baseCountryCode = baseCode,
                phoneNumber = phone,
                deviceId = (dict["deviceId"] as? String)?.trim()?.ifEmpty { null },
                role = role,
                bio = bio,
                visibilityMode = visibility,
                excludedBaseCodes = excluded,
                isOnline = isOnline,
                lastSeenMs = lastSeenMs,
                lat = lat,
                lon = lon,
                distanceKm = dist,
                photoB64 = dict["photoB64"] as? String,
                photosB64 = firebaseListStrings(dict["photosB64"]),
                photoUrl = (dict["photoUrl"] as? String)?.takeIf { it.isNotBlank() },
                photosUrls = firebaseListStrings(dict["photosUrls"])
            )

            visibleUsers.add(user)
        }
        visibleUsers.forEach { prefetchNearbyPhoto(it) }
        val online = visibleUsers.filter { it.isOnline }
        val last24 = visibleUsers.filter { !it.isOnline && it.lastSeenMs >= cutoffMs }

        CLog.d(TAG, "Nearby rebuild iOS-style uid=$uid visible=${visibleUsers.size} online=${online.size} last24=${last24.size}")

        _onlineNow.postValue(online)
        _activeLast24h.postValue(last24)
        _onlineNearbyCount.postValue(online.size)
    }

    private fun scheduleNearbyRebuild() {
        nearbyRebuildRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { rebuildNearbyListsFromCache() }
        nearbyRebuildRunnable = r
        mainHandler.postDelayed(r, 150L)
    }

    /**
     * Re-evaluates the online list from the local cache every 60 s using the current clock.
     * No Firebase read — purely local staleness check.
     * Removes users whose lastSeenMs is older than 2 min even when Firebase has not yet
     * sent a childChanged event (slow onDisconnect after app deletion/kill).
     */
    private fun startStaleSweepTimer() {
        stopStaleSweepTimer()
        val r = object : Runnable {
            override fun run() {
                rebuildNearbyListsFromCache()
                mainHandler.postDelayed(this, 60_000L)
            }
        }
        staleSweepRunnable = r
        mainHandler.postDelayed(r, 60_000L)
    }

    private fun stopStaleSweepTimer() {
        staleSweepRunnable?.let { mainHandler.removeCallbacks(it) }
        staleSweepRunnable = null
    }

    private fun rebuildEventsList(snapshot: DataSnapshot) {
        val uid = myUserId ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        val nowMs = System.currentTimeMillis()

        root.child("event_hidden").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(hiddenSnap: DataSnapshot) {
                val hiddenIds = hiddenSnap.children.mapNotNull { it.key }.toSet()
                val countsByEvent = cachedMemberCountsByEvent
                val joinedIds = cachedJoinedEventIds

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



    /**
     * Removes all RTDB nodes owned by an old anonymous UID on the same device.
     * We skip reading userThreads/{oldUid} first because the current auth UID differs
     * from oldUid and the read rule requires auth.uid == $uid — attempting it would
     * produce a "Permission denied" error in Logcat. Chat messages for orphaned threads
     * are not cleaned up here (acceptable trade-off); the write to userThreads/{oldUid}
     * is allowed by the matching-deviceId write rule.
     */
    private fun deleteAllDataForOldUid(oldUid: String, deviceId: String) {
        val deletions = hashMapOf<String, Any?>(
            "crew_users/$oldUid"         to null,
            "crew_user_meta/$oldUid"     to null,
            "userTokens/$oldUid"         to null,
            "user_event_invites/$oldUid" to null,
            "event_hidden/$oldUid"       to null,
            "userThreads/$oldUid"        to null
        )
        root.updateChildren(deletions)
            .addOnSuccessListener {
                CLog.d(TAG, "Deleted all data for stale UID $oldUid (deviceId=$deviceId)")
            }
            .addOnFailureListener { e ->
                CLog.e(TAG, "Failed to delete stale UID $oldUid: ${e.message}")
            }
    }

    private fun upsertMyProfile(uid: String) {
        val settings = _settingsLive.value ?: CrewLayoverSettings()
        val loc = lastLocation

        CLog.d(
            TAG,
            "upsertMyProfile uid=$uid nick=${settings.nickname.trim()} role=${settings.role.raw} base=${settings.baseCountryCode.trim()} device=${getDeviceId()} hasLoc=${loc != null}"
        )

        // Use a non-nullable map: Firebase SDK may reject or silently swallow null values
        // in updateChildren(), causing the entire write to fail without an error callback.
        // Match iOS behavior: optional strings fall back to "" (empty string) rather than null.
        val payload = mutableMapOf<String, Any>(
            "nickname"          to settings.nickname.trim(),
            "companyName"       to (settings.companyName?.trim() ?: ""),
            "baseCountryCode"   to settings.baseCountryCode.trim(),
            "phoneNumber"       to (settings.phoneNumber?.trim() ?: ""),
            "role"              to settings.role.raw,
            "visibilityMode"    to settings.visibilityMode.raw,
            "excludedBaseCodes" to settings.excludedBaseCodes,
            "isEnabled"         to settings.isEnabled,
            "isOnline"          to true,
            "lat"               to (loc?.latitude ?: 0.0),
            "lon"               to (loc?.longitude ?: 0.0),
            "lastSeenMs"        to ServerValue.TIMESTAMP
        )

        settings.bio?.trim()?.ifEmpty { null }?.let { payload["bio"] = it }
        getDeviceId()?.let { payload["deviceId"] = it }

        // Write base profile fields immediately
        val userRef = root.child("crew_users").child(uid)
        userRef.updateChildren(payload)
        writeUserMeta(uid, settings, loc, null, null)

        // Upload photos to Firebase Storage and write URLs asynchronously (like iOS).
        // IMPORTANT: only remove legacy base64 fields AFTER a successful Storage upload.
        // iOS filters hasPhoto() before showing any user in the nearby list — removing b64
        // before Storage URLs exist would make this user invisible to iOS devices.
        uploadPhotosToStorage(uid) { photoUrl, photosUrls ->
            if (photoUrl.isNotEmpty()) {
                // Migration complete: write Storage URLs and erase the bulky b64 fields.
                userRef.updateChildren(mapOf("photoUrl" to photoUrl, "photosUrls" to photosUrls))
                userRef.child("photoB64").removeValue()
                userRef.child("photosB64").removeValue()
                appContext?.let { CrewPhotoLoader.get(it).loadFromUrl(photoUrl, uid) }
                writeUserMeta(uid, settings, loc, photoUrl, photosUrls)
            }
            // If upload returns empty (no local photo or upload failed), keep existing
            // b64 fields intact so iOS continues to see a valid photo reference.
        }
    }

    private fun cleanupExpiredEvent(eventId: String) {
        cancelEventReminder(eventId)
        cancelEventExpiry(eventId)
        root.child("events").child(eventId).removeValue()
        root.child("event_members").child(eventId).removeValue()
        root.child("event_messages").child(eventId).removeValue()

        cachedEventMembers[eventId]?.forEach { memberUid ->
            root.child("user_event_invites").child(memberUid).child(eventId).removeValue()
        }
    }

    private fun updateMyPresence(uid: String) {
        val loc = lastLocation
        val lat = loc?.latitude ?: 0.0
        val lon = loc?.longitude ?: 0.0

        CLog.d(
            TAG,
            "updateMyPresence uid=$uid nick=${(_settingsLive.value ?: CrewLayoverSettings()).nickname.trim()} role=${(_settingsLive.value ?: CrewLayoverSettings()).role.raw} base=${(_settingsLive.value ?: CrewLayoverSettings()).baseCountryCode.trim()} device=${getDeviceId()} hasLoc=${loc != null} lat=$lat lon=$lon"
        )

        if (lat != 0.0 && lon != 0.0) {
            CrewPresenceService.shared.updateLocation(lat, lon)
        } else {
            CLog.w(TAG, "⚠️ No valid location to update")
        }

        // Non-nullable map: avoids silent Firebase write failures on null values.
        val update = mutableMapOf<String, Any>(
            "isOnline"    to true,
            "lat"         to lat,
            "lon"         to lon,
            "lastSeenMs"  to ServerValue.TIMESTAMP
        )
        getDeviceId()?.let { update["deviceId"] = it }
        root.child("crew_users").child(uid).updateChildren(update)
        writeUserMeta(uid, _settingsLive.value ?: CrewLayoverSettings(), loc, null, null)
    }

    private fun writeUserMeta(
        uid: String,
        settings: CrewLayoverSettings,
        loc: Location?,
        photoUrl: String?,
        photosUrls: List<String>?
    ) {
        val meta = mutableMapOf<String, Any>(
            "nickname" to settings.nickname.trim(),
            "companyName" to (settings.companyName?.trim() ?: ""),
            "baseCountryCode" to settings.baseCountryCode.trim(),
            "phoneNumber" to (settings.phoneNumber?.trim() ?: ""),
            "bio" to (settings.bio?.trim() ?: ""),
            "role" to settings.role.raw,
            "visibilityMode" to settings.visibilityMode.raw,
            "excludedBaseCodes" to settings.excludedBaseCodes,
            "isEnabled" to settings.isEnabled,
            "isOnline" to true,
            "lat" to (loc?.latitude ?: 0.0),
            "lon" to (loc?.longitude ?: 0.0),
            "lastSeenMs" to ServerValue.TIMESTAMP
        )
        getDeviceId()?.let { meta["deviceId"] = it }
        if (!photoUrl.isNullOrBlank()) meta["photoUrl"] = photoUrl
        if (photosUrls != null) meta["photosUrls"] = photosUrls
        root.child("crew_user_meta").child(uid).updateChildren(meta)
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

    /**
     * Robust boolean parser — Firebase can return booleans as Boolean, Long (0/1), or String
     * depending on SDK version and cache state. Mirrors iOS parseBool helper.
     */
    private fun parseBool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number  -> value.toInt() != 0
        is String  -> value.trim().lowercase(java.util.Locale.ROOT) == "true" || value.trim() == "1"
        else       -> false
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

    private fun getDeviceId(): String? {
        val ctx = appContext ?: return null
        val p = prefs() ?: return null
        val cached = p.getString(deviceIdKey, null)
        if (!cached.isNullOrBlank()) return cached
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        val newId = androidId?.trim().takeUnless { it.isNullOrEmpty() } ?: UUID.randomUUID().toString()
        p.edit { putString(deviceIdKey, newId) }
        return newId
    }

    private fun loadSettings() {
        val p = prefs() ?: return
        val s = CrewLayoverSettings(
            nickname = p.getString(KEY_NICK, "") ?: "",
            companyName = p.getString(KEY_COMPANY, null),
            baseCountryCode = p.getString(KEY_BASE, "") ?: "",
            phoneNumber = p.getString(KEY_PHONE, null),
            bio = p.getString(KEY_BIO, null),
            role = CrewRole.fromRaw(p.getString(KEY_ROLE, CrewRole.CABIN_CREW.raw)),
            visibilityMode = CrewVisibilityMode.fromRaw(p.getString(KEY_VIS, CrewVisibilityMode.EVERYONE.raw)),
            excludedBaseCodes = p.getString(KEY_EXCLUDED, "")?.split(",")?.mapNotNull { it.trim().ifEmpty { null } }?.toMutableList()
                ?: mutableListOf(),
            isEnabled = p.getBoolean(KEY_ENABLED, true),
            eventRemindersEnabled = p.getBoolean(KEY_EVENT_REMINDERS, true)
        )
        distanceUnlimited = p.getBoolean(distanceUnlimitedKey, true)
        distanceMaxKm = p.getFloat(distanceMaxKmKey, 50f).toDouble().coerceIn(0.0, 5000.0)
        // Use setValue (synchronous) not postValue (async) so _settingsLive.value is
        // immediately correct when refreshNow() is called in the same call-stack frame.
        // loadSettings() is always invoked from init(context) on the main thread.
        _settingsLive.value = s
    }

    private fun saveSettings(settings: CrewLayoverSettings) {
        prefs()?.edit {
            putString(KEY_NICK, settings.nickname)
            putString(KEY_COMPANY, settings.companyName)
            putString(KEY_BASE, settings.baseCountryCode)
            putString(KEY_PHONE, settings.phoneNumber)
            putString(KEY_BIO, settings.bio)
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
        val msgId = msgRef.key ?: UUID.randomUUID().toString()
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

    @SuppressLint("MissingPermission")
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

        private const val TAG = "CrewLayover"
        private const val PREFS_NAME = "crew_layover_settings"
        private const val KEY_NICK = "nick"
        private const val KEY_COMPANY = "company"
        private const val KEY_BASE = "base"
        private const val KEY_PHONE = "phone"
        private const val KEY_BIO = "bio"
        private const val KEY_ROLE = "role"
        private const val KEY_VIS = "visibility"
        private const val KEY_EXCLUDED = "excluded"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EVENT_REMINDERS = "event_reminders"
        private const val KEY_LAST_SEND_TO_ALL_NEARBY_MS = "crew_last_sendToAllNearby_ms"
        private const val SEND_TO_ALL_NEARBY_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes
    }
}
