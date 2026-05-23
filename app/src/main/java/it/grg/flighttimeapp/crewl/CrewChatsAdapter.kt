package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import it.grg.flighttimeapp.R
import java.text.SimpleDateFormat
import java.util.Locale

class CrewChatsAdapter(
    private var items: List<CrewChatThread>,
    private var unread: Set<String>,
    private val onClick: (CrewChatThread) -> Unit,
    private val onPhotoClick: ((peerId: String) -> Unit)? = null
) : RecyclerView.Adapter<CrewChatsAdapter.ThreadVH>() {

    private val df = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val userCache: MutableMap<String, CrewUserInfo> = mutableMapOf()
    private val fetchingUsers: MutableSet<String> = mutableSetOf()

    fun submit(newItems: List<CrewChatThread>, unreadSet: Set<String>) {
        items = newItems
        unread = unreadSet
        notifyDataSetChanged()
    }

    fun getItem(position: Int): CrewChatThread? {
        return if (position in items.indices) items[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_thread, parent, false)
        return ThreadVH(v, onClick, df, onPhotoClick)
    }

    override fun onBindViewHolder(holder: ThreadVH, position: Int) {
        val thread = items[position]
        holder.bind(thread, unread.contains(thread.id), userCache, onPhotoClick)
        ensureUserLoaded(thread.peerId)
    }

    override fun getItemCount(): Int = items.size

    private fun ensureUserLoaded(peerId: String) {
        if (peerId.isBlank() || userCache.containsKey(peerId) || fetchingUsers.contains(peerId)) return
        fetchingUsers.add(peerId)
        FirebaseDatabase.getInstance().reference.child("crew_users").child(peerId)
            .get()
            .addOnSuccessListener { snapshot ->
                val dict = snapshot.value as? Map<*, *> ?: return@addOnSuccessListener
                val nickname = dict["nickname"] as? String
                val photoUrl = dict["photoUrl"] as? String
                val photosUrls = when (val raw = dict["photosUrls"]) {
                    is List<*> -> raw.mapNotNull { it as? String }
                    is Map<*, *> -> raw.values.mapNotNull { it as? String }
                    else -> emptyList()
                }
                userCache[peerId] = CrewUserInfo(nickname, null, photoUrl, photosUrls)
                val index = items.indexOfFirst { it.peerId == peerId }
                if (index >= 0) notifyItemChanged(index)
            }
            .addOnCompleteListener {
                fetchingUsers.remove(peerId)
            }
    }

    data class CrewUserInfo(
        val nickname: String?,
        val photoB64: String?,
        val photoUrl: String?,
        val photosUrls: List<String>
    ) {
        fun primaryRef(): String? = when {
            photosUrls.isNotEmpty() -> photosUrls.first()
            !photoUrl.isNullOrBlank() -> photoUrl
            else -> null
        }
    }

    class ThreadVH(
        itemView: View,
        private val onClick: (CrewChatThread) -> Unit,
        private val df: SimpleDateFormat,
        private val onPhotoClick: ((peerId: String) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val photo: ImageView = itemView.findViewById<ImageView>(R.id.threadPhoto).also { it.clipToOutline = true }
        private val name: TextView = itemView.findViewById(R.id.threadName)
        private val subtitle: TextView = itemView.findViewById(R.id.threadSubtitle)
        private val unreadDot: View = itemView.findViewById(R.id.threadUnreadDot)

        fun bind(
            thread: CrewChatThread,
            isUnread: Boolean,
            cache: Map<String, CrewUserInfo>,
            onPhotoClick: ((peerId: String) -> Unit)?
        ) {
            val pid = thread.peerId
            val summary = CrewLayoverStore.shared.getUserSummary(pid)
            val cachedInfo = cache[pid]
            name.text = cachedInfo?.nickname?.takeIf { it.isNotBlank() }
                ?: summary?.nickname?.takeIf { it.isNotBlank() }
                ?: thread.peerNickname.ifBlank { thread.peerId }

            val msg = displayPreview(thread.lastMessageText)
            val time = df.format(thread.lastMessageAt)
            subtitle.text = if (msg.isNotBlank()) "$msg • $time" else time
            unreadDot.visibility = if (isUnread) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(thread) }

            // Load peer photo. Check loader cache first — b64 photos are stripped from usersCache
            // to save memory but are pre-decoded into CrewPhotoLoader during snapshot processing.
            photo.tag = pid
            val cachedBitmap = CrewPhotoLoader.shared.memoryImage(pid)
            val primaryRef = cachedInfo?.primaryRef() ?: summary?.primaryRef()
            if (cachedBitmap != null) {
                photo.visibility = View.VISIBLE
                photo.setImageBitmap(cachedBitmap)
                photo.setOnClickListener { onPhotoClick?.invoke(pid) }
            } else if (primaryRef != null) {
                photo.visibility = View.VISIBLE
                if (primaryRef.startsWith("http")) {
                    photo.setImageDrawable(null)
                    CrewPhotoLoader.shared.loadFromUrl(primaryRef, pid) { bmp ->
                        if (bmp != null && photo.tag == pid) photo.setImageBitmap(bmp)
                    }
                } else {
                    val bmp = CrewPhotoLoader.shared.getBitmap(pid, primaryRef)
                    if (bmp != null) photo.setImageBitmap(bmp) else photo.setImageDrawable(null)
                }
                photo.setOnClickListener { onPhotoClick?.invoke(pid) }
            } else {
                photo.setImageDrawable(null)
                photo.visibility = View.GONE
                photo.setOnClickListener(null)
            }
        }

        private fun displayPreview(raw: String?): String {
            val value = raw?.trim().orEmpty()
            return when {
                value == "cl_e2e_message_preview" || value.startsWith("cl_e2e_") ->
                    subtitle.context.getString(R.string.cl_e2e_message_preview)
                value == "cl_photo_message_preview" ->
                    subtitle.context.getString(R.string.cl_photo_message_preview)
                else -> value
            }
        }
    }
}
