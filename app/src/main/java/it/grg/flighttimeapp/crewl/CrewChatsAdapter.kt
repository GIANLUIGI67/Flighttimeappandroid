package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
        holder.bind(thread, unread.contains(thread.id))
    }

    override fun getItemCount(): Int = items.size

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

        fun bind(thread: CrewChatThread, isUnread: Boolean) {
            name.text = thread.peerNickname.ifBlank { thread.peerId }
            val msg = thread.lastMessageText ?: ""
            val time = df.format(thread.lastMessageAt)
            subtitle.text = if (msg.isNotBlank()) "$msg • $time" else time
            unreadDot.visibility = if (isUnread) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(thread) }

            // Load peer photo. Check loader cache first — b64 photos are stripped from usersCache
            // to save memory but are pre-decoded into CrewPhotoLoader during snapshot processing.
            val pid = thread.peerId
            val cachedBitmap = CrewPhotoLoader.shared.image(pid)
            val summary = CrewLayoverStore.shared.getUserSummary(pid)
            val primaryRef = summary?.primaryRef()
            if (cachedBitmap != null) {
                photo.visibility = View.VISIBLE
                photo.setImageBitmap(cachedBitmap)
                photo.setOnClickListener { onPhotoClick?.invoke(pid) }
            } else if (primaryRef != null) {
                photo.visibility = View.VISIBLE
                if (primaryRef.startsWith("http")) {
                    photo.setImageDrawable(null)
                    CrewPhotoLoader.shared.loadFromUrl(primaryRef, pid) { bmp ->
                        if (bmp != null) photo.setImageBitmap(bmp)
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
    }
}
