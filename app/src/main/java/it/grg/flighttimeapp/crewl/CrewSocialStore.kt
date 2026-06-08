package it.grg.flighttimeapp.crewl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class CrewSocialStore private constructor() {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _posts = MutableLiveData<List<CrewSocialPost>>(emptyList())
    val posts: LiveData<List<CrewSocialPost>> = _posts

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    fun clearError() { _error.postValue(null) }

    fun loadPosts(refresh: Boolean = true) {
        _isLoading.postValue(true)
        db.collection("crew_posts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(80)
            .get()
            .addOnSuccessListener { snapshot ->
                val posts = snapshot.documents.map { doc ->
                    val data = doc.data.orEmpty()
                    CrewSocialPost(
                        id = doc.id,
                        uid = data["uid"] as? String ?: "",
                        nickname = data["nickname"] as? String ?: "Crew",
                        role = CrewRole.fromRaw(data["role"] as? String),
                        airline = data["airline"] as? String,
                        text = data["text"] as? String ?: "",
                        photoStorageUrl = data["photoStorageUrl"] as? String,
                        videoStorageUrl = data["videoStorageUrl"] as? String,
                        profilePhotoUrl = data["profilePhotoUrl"] as? String,
                        layoverLocation = data["layoverLocation"] as? String,
                        likesCount = intValue(data["likesCount"]),
                        thumbUpCount = intValue(data["thumbUpCount"]),
                        thumbDownCount = intValue(data["thumbDownCount"]),
                        commentsCount = intValue(data["commentsCount"]),
                        createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()
                    )
                }.toMutableList()
                fillPostReactionStates(posts) { filled ->
                    _posts.postValue(filled)
                    _isLoading.postValue(false)
                }
            }
            .addOnFailureListener { e ->
                _error.postValue(e.localizedMessage)
                _isLoading.postValue(false)
            }
    }

    private fun fillPostReactionStates(posts: MutableList<CrewSocialPost>, onDone: (List<CrewSocialPost>) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || posts.isEmpty()) {
            onDone(posts)
            return
        }
        val pending = AtomicInteger(posts.size * 3)
        fun oneDone() {
            if (pending.decrementAndGet() == 0) onDone(posts)
        }
        posts.forEachIndexed { index, post ->
            val ref = db.collection("crew_posts").document(post.id)
            ref.collection("likes").document(uid).get()
                .addOnSuccessListener { posts[index].isLikedByMe = it.exists() }
                .addOnCompleteListener { oneDone() }
            ref.collection("thumbUps").document(uid).get()
                .addOnSuccessListener { posts[index].isThumbedUpByMe = it.exists() }
                .addOnCompleteListener { oneDone() }
            ref.collection("thumbDowns").document(uid).get()
                .addOnSuccessListener { posts[index].isThumbedDownByMe = it.exists() }
                .addOnCompleteListener { oneDone() }
        }
    }

    fun submitPost(
        context: Context,
        text: String,
        imageUri: Uri?,
        videoUri: Uri?,
        location: String?,
        onDone: (Boolean) -> Unit
    ) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            _error.postValue(context.getString(it.grg.flighttimeapp.R.string.cl_social_auth_error_missing_user))
            onDone(false)
            return
        }
        val trimmedText = text.trim().take(280)
        if (trimmedText.isBlank() && imageUri == null && videoUri == null) {
            onDone(false)
            return
        }
        val postRef = db.collection("crew_posts").document()
        val postId = postRef.id

        fun commit(photoUrl: String?, videoUrl: String?) {
            val settings = CrewLayoverStore.shared.settingsLive.value ?: CrewLayoverSettings()
            val nickname = settings.nickname.trim().ifBlank { "Crew" }
            val airline = settings.companyName?.trim()?.ifBlank { null }
            val profilePhotoUrl = CrewLayoverStore.shared.getUserSummary(uid)?.primaryRef()
            val payload = mutableMapOf<String, Any>(
                "uid" to uid,
                "nickname" to nickname,
                "role" to settings.role.raw,
                "text" to trimmedText,
                "likesCount" to 0,
                "thumbUpCount" to 0,
                "thumbDownCount" to 0,
                "commentsCount" to 0,
                "createdAt" to Timestamp(Date())
            )
            if (!airline.isNullOrBlank()) payload["airline"] = airline
            if (!photoUrl.isNullOrBlank()) {
                payload["photoStorageUrl"] = photoUrl
                payload["mediaType"] = "image"
            }
            if (!videoUrl.isNullOrBlank()) {
                payload["videoStorageUrl"] = videoUrl
                payload["mediaType"] = "video"
            }
            if (!profilePhotoUrl.isNullOrBlank()) payload["profilePhotoUrl"] = profilePhotoUrl
            val trimmedLocation = location?.trim().orEmpty()
            if (trimmedLocation.isNotBlank()) payload["layoverLocation"] = trimmedLocation

            postRef.set(payload)
                .addOnSuccessListener {
                    loadPosts(true)
                    onDone(true)
                }
                .addOnFailureListener { e ->
                    _error.postValue(e.localizedMessage)
                    onDone(false)
                }
        }

        fun uploadVideoThen(photoUrl: String?) {
            if (videoUri == null) {
                commit(photoUrl, null)
                return
            }
            val ext = extensionFor(context, videoUri, "mov")
            val ref = storage.reference.child("crew_post_media/$postId.$ext")
            val metadata = com.google.firebase.storage.StorageMetadata.Builder()
                .setContentType(context.contentResolver.getType(videoUri) ?: "video/quicktime")
                .build()
            ref.putFile(videoUri, metadata)
                .continueWithTask { ref.downloadUrl }
                .addOnSuccessListener { commit(photoUrl, it.toString()) }
                .addOnFailureListener { e ->
                    _error.postValue(e.localizedMessage)
                    onDone(false)
                }
        }

        if (imageUri != null) {
            val bytes = compressedImageBytes(context, imageUri)
            if (bytes == null) {
                _error.postValue(context.getString(it.grg.flighttimeapp.R.string.cl_social_post_failed))
                onDone(false)
                return
            }
            val ref = storage.reference.child("crew_post_media/$postId.jpg")
            val metadata = com.google.firebase.storage.StorageMetadata.Builder().setContentType("image/jpeg").build()
            ref.putBytes(bytes, metadata)
                .continueWithTask { ref.downloadUrl }
                .addOnSuccessListener { uploadVideoThen(it.toString()) }
                .addOnFailureListener { e ->
                    _error.postValue(e.localizedMessage)
                    onDone(false)
                }
        } else {
            uploadVideoThen(null)
        }
    }

    fun updatePost(postId: String, text: String, location: String?, onDone: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onDone(false)
        val post = _posts.value?.firstOrNull { it.id == postId && it.uid == uid } ?: return onDone(false)
        val trimmed = text.trim().take(280)
        if (trimmed.isBlank()) return onDone(false)
        val payload = mutableMapOf<String, Any>("text" to trimmed, "updatedAt" to Timestamp(Date()))
        val loc = location?.trim().orEmpty()
        payload["layoverLocation"] = if (loc.isBlank()) FieldValue.delete() else loc
        db.collection("crew_posts").document(postId).update(payload)
            .addOnSuccessListener {
                _posts.postValue(_posts.value.orEmpty().map { if (it.id == post.id) it.copy(text = trimmed, layoverLocation = loc.ifBlank { null }) else it })
                onDone(true)
            }
            .addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(false) }
    }

    fun deletePost(postId: String, onDone: (Boolean) -> Unit = {}) {
        val uid = auth.currentUser?.uid ?: return onDone(false)
        val post = _posts.value?.firstOrNull { it.id == postId && it.uid == uid } ?: return onDone(false)
        db.collection("crew_posts").document(post.id).delete()
            .addOnSuccessListener {
                _posts.postValue(_posts.value.orEmpty().filterNot { it.id == post.id })
                onDone(true)
            }
            .addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(false) }
    }

    fun togglePostReaction(postId: String, reaction: CrewSocialReactionKind) {
        val uid = auth.currentUser?.uid ?: return
        val postRef = db.collection("crew_posts").document(postId)
        toggleReaction(uid, postRef, reaction) { delta ->
            val next = _posts.value.orEmpty().map { post ->
                if (post.id != postId) post else applyPostDelta(post, delta)
            }
            _posts.postValue(next)
        }
    }

    fun loadComments(postId: String, onDone: (List<CrewSocialComment>) -> Unit) {
        db.collection("crew_posts").document(postId).collection("comments")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(80)
            .get()
            .addOnSuccessListener { snapshot ->
                val comments = snapshot.documents.map { doc ->
                    val data = doc.data.orEmpty()
                    CrewSocialComment(
                        id = doc.id,
                        uid = data["uid"] as? String ?: "",
                        nickname = data["nickname"] as? String ?: "Crew",
                        profilePhotoUrl = data["profilePhotoUrl"] as? String,
                        text = data["text"] as? String ?: "",
                        likesCount = intValue(data["likesCount"]),
                        thumbUpCount = intValue(data["thumbUpCount"]),
                        thumbDownCount = intValue(data["thumbDownCount"]),
                        createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()
                    )
                }.toMutableList()
                fillCommentReactionStates(postId, comments, onDone)
            }
            .addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(emptyList()) }
    }

    private fun fillCommentReactionStates(postId: String, comments: MutableList<CrewSocialComment>, onDone: (List<CrewSocialComment>) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || comments.isEmpty()) return onDone(comments)
        val pending = AtomicInteger(comments.size * 3)
        fun oneDone() { if (pending.decrementAndGet() == 0) onDone(comments) }
        comments.forEachIndexed { index, comment ->
            val ref = db.collection("crew_posts").document(postId).collection("comments").document(comment.id)
            ref.collection("likes").document(uid).get().addOnSuccessListener { comments[index].isLikedByMe = it.exists() }.addOnCompleteListener { oneDone() }
            ref.collection("thumbUps").document(uid).get().addOnSuccessListener { comments[index].isThumbedUpByMe = it.exists() }.addOnCompleteListener { oneDone() }
            ref.collection("thumbDowns").document(uid).get().addOnSuccessListener { comments[index].isThumbedDownByMe = it.exists() }.addOnCompleteListener { oneDone() }
        }
    }

    fun submitComment(postId: String, text: String, onDone: (CrewSocialComment?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onDone(null)
        val trimmed = text.trim().take(280)
        if (trimmed.isBlank()) return onDone(null)
        val postRef = db.collection("crew_posts").document(postId)
        val commentRef = postRef.collection("comments").document()
        val settings = CrewLayoverStore.shared.settingsLive.value ?: CrewLayoverSettings()
        val profilePhotoUrl = CrewLayoverStore.shared.getUserSummary(uid)?.primaryRef()
        val now = Date()
        val payload = mutableMapOf<String, Any>(
            "uid" to uid,
            "nickname" to settings.nickname.trim().ifBlank { "Crew" },
            "text" to trimmed,
            "likesCount" to 0,
            "thumbUpCount" to 0,
            "thumbDownCount" to 0,
            "createdAt" to Timestamp(now)
        )
        if (!profilePhotoUrl.isNullOrBlank()) payload["profilePhotoUrl"] = profilePhotoUrl
        val batch = db.batch()
        batch.set(commentRef, payload)
        batch.update(postRef, "commentsCount", FieldValue.increment(1))
        batch.commit()
            .addOnSuccessListener {
                _posts.postValue(_posts.value.orEmpty().map { if (it.id == postId) it.copy(commentsCount = it.commentsCount + 1) else it })
                onDone(CrewSocialComment(commentRef.id, uid, payload["nickname"] as String, profilePhotoUrl, trimmed, 0, 0, 0, now))
            }
            .addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(null) }
    }

    fun updateComment(postId: String, commentId: String, text: String, onDone: (String?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onDone(null)
        val trimmed = text.trim().take(280)
        if (trimmed.isBlank()) return onDone(null)
        val ref = db.collection("crew_posts").document(postId).collection("comments").document(commentId)
        ref.get().addOnSuccessListener { snap ->
            if (snap.getString("uid") != uid) return@addOnSuccessListener onDone(null)
            ref.update(mapOf("text" to trimmed, "updatedAt" to Timestamp(Date())))
                .addOnSuccessListener { onDone(trimmed) }
                .addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(null) }
        }.addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(null) }
    }

    fun deleteComment(postId: String, commentId: String, onDone: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onDone(false)
        val postRef = db.collection("crew_posts").document(postId)
        val ref = postRef.collection("comments").document(commentId)
        ref.get().addOnSuccessListener { snap ->
            if (snap.getString("uid") != uid) return@addOnSuccessListener onDone(false)
            val batch = db.batch()
            batch.delete(ref)
            batch.update(postRef, "commentsCount", FieldValue.increment(-1))
            batch.commit()
                .addOnSuccessListener {
                    _posts.postValue(_posts.value.orEmpty().map { if (it.id == postId) it.copy(commentsCount = (it.commentsCount - 1).coerceAtLeast(0)) else it })
                    onDone(true)
                }
                .addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(false) }
        }.addOnFailureListener { e -> _error.postValue(e.localizedMessage); onDone(false) }
    }

    fun toggleCommentReaction(postId: String, commentId: String, reaction: CrewSocialReactionKind, onDone: (Map<String, Int>?) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onDone(null)
        val ref = db.collection("crew_posts").document(postId).collection("comments").document(commentId)
        toggleReaction(uid, ref, reaction, onDone)
    }

    private fun toggleReaction(uid: String, targetRef: DocumentReference, reaction: CrewSocialReactionKind, onDone: (Map<String, Int>) -> Unit) {
        val heartRef = targetRef.collection("likes").document(uid)
        val upRef = targetRef.collection("thumbUps").document(uid)
        val downRef = targetRef.collection("thumbDowns").document(uid)
        val data = mapOf("uid" to uid, "createdAt" to Timestamp(Date()))
        when (reaction) {
            CrewSocialReactionKind.HEART -> {
                heartRef.get().addOnSuccessListener { heart ->
                    val batch = db.batch()
                    val delta = if (heart.exists()) {
                        batch.delete(heartRef); batch.update(targetRef, "likesCount", FieldValue.increment(-1)); mapOf("likesCount" to -1, "heartOn" to 0)
                    } else {
                        batch.set(heartRef, data); batch.update(targetRef, "likesCount", FieldValue.increment(1)); mapOf("likesCount" to 1, "heartOn" to 1)
                    }
                    batch.commit().addOnSuccessListener { onDone(delta) }.addOnFailureListener { _error.postValue(it.localizedMessage) }
                }
            }
            CrewSocialReactionKind.THUMB_UP -> {
                upRef.get().addOnSuccessListener { up ->
                    downRef.get().addOnSuccessListener { down ->
                        val batch = db.batch()
                        val delta = mutableMapOf<String, Int>()
                        if (up.exists()) {
                            batch.delete(upRef); batch.update(targetRef, "thumbUpCount", FieldValue.increment(-1)); delta["thumbUpCount"] = -1; delta["thumbUpOn"] = 0
                        } else {
                            batch.set(upRef, data); delta["thumbUpCount"] = 1; delta["thumbUpOn"] = 1; delta["thumbDownOn"] = 0
                            val updates = mutableMapOf<String, Any>("thumbUpCount" to FieldValue.increment(1))
                            if (down.exists()) { batch.delete(downRef); updates["thumbDownCount"] = FieldValue.increment(-1); delta["thumbDownCount"] = -1 }
                            batch.update(targetRef, updates)
                        }
                        batch.commit().addOnSuccessListener { onDone(delta) }.addOnFailureListener { _error.postValue(it.localizedMessage) }
                    }
                }
            }
            CrewSocialReactionKind.THUMB_DOWN -> {
                downRef.get().addOnSuccessListener { down ->
                    upRef.get().addOnSuccessListener { up ->
                        val batch = db.batch()
                        val delta = mutableMapOf<String, Int>()
                        if (down.exists()) {
                            batch.delete(downRef); batch.update(targetRef, "thumbDownCount", FieldValue.increment(-1)); delta["thumbDownCount"] = -1; delta["thumbDownOn"] = 0
                        } else {
                            batch.set(downRef, data); delta["thumbDownCount"] = 1; delta["thumbDownOn"] = 1; delta["thumbUpOn"] = 0
                            val updates = mutableMapOf<String, Any>("thumbDownCount" to FieldValue.increment(1))
                            if (up.exists()) { batch.delete(upRef); updates["thumbUpCount"] = FieldValue.increment(-1); delta["thumbUpCount"] = -1 }
                            batch.update(targetRef, updates)
                        }
                        batch.commit().addOnSuccessListener { onDone(delta) }.addOnFailureListener { _error.postValue(it.localizedMessage) }
                    }
                }
            }
        }
    }

    private fun applyPostDelta(post: CrewSocialPost, delta: Map<String, Int>): CrewSocialPost {
        val updated = post.copy(
            likesCount = (post.likesCount + (delta["likesCount"] ?: 0)).coerceAtLeast(0),
            thumbUpCount = (post.thumbUpCount + (delta["thumbUpCount"] ?: 0)).coerceAtLeast(0),
            thumbDownCount = (post.thumbDownCount + (delta["thumbDownCount"] ?: 0)).coerceAtLeast(0)
        )
        delta["heartOn"]?.let { updated.isLikedByMe = it == 1 }
        delta["thumbUpOn"]?.let { updated.isThumbedUpByMe = it == 1 }
        delta["thumbDownOn"]?.let { updated.isThumbedDownByMe = it == 1 }
        return updated
    }

    private fun compressedImageBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val source = input.use { BitmapFactory.decodeStream(it) } ?: return null
            val max = 1080f
            val ratio = minOf(1f, max / maxOf(source.width, source.height).toFloat())
            val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true) else source
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun extensionFor(context: Context, uri: Uri, fallback: String): String {
        val type = context.contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(type)?.lowercase(Locale.ROOT)
            ?: uri.lastPathSegment?.substringAfterLast('.', fallback)?.lowercase(Locale.ROOT)
            ?: fallback
    }

    private fun intValue(value: Any?): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is Number -> value.toInt()
        else -> 0
    }

    companion object {
        val shared = CrewSocialStore()
    }
}
