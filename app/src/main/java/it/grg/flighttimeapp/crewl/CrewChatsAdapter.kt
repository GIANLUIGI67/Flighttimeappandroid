package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import java.text.SimpleDateFormat
import java.util.Locale

class CrewChatsAdapter(
    private var items: List<CrewChatThread>,
    private var unread: Set<String>,
    private val onClick: (CrewChatThread) -> Unit
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
        return ThreadVH(v, onClick, df)
    }

    override fun onBindViewHolder(holder: ThreadVH, position: Int) {
        val thread = items[position]
        holder.bind(thread, unread.contains(thread.id))
    }

    override fun getItemCount(): Int = items.size

    class ThreadVH(
        itemView: View,
        private val onClick: (CrewChatThread) -> Unit,
        private val df: SimpleDateFormat
    ) : RecyclerView.ViewHolder(itemView) {
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
        }
    }
}
