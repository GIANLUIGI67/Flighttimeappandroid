package it.grg.flighttimeapp.crewl

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import it.grg.flighttimeapp.R

class CrewChatMessagesAdapter(
    private var items: List<CrewChatMessage>,
    private val onImageClick: ((CrewChatMessage) -> Unit)? = null,
    private val onAvatarClick: ((CrewChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<CrewChatMessagesAdapter.MsgVH>() {

    private val myUid: String? = FirebaseAuth.getInstance().currentUser?.uid
    private var myName: String? = null
    private var myPhoto: Bitmap? = null
    private val userCache: MutableMap<String, CrewUserInfo> = mutableMapOf()
    private val fetchingUsers: MutableSet<String> = mutableSetOf()

    fun submit(newItems: List<CrewChatMessage>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos] == newItems[newPos]
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    fun setMyProfile(name: String?, photo: Bitmap?) {
        myName = name
        myPhoto = photo
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MsgVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MsgVH, position: Int) {
        holder.bind(items[position], myUid, myName, myPhoto, userCache, onImageClick, onAvatarClick) { uid ->
            if (fetchingUsers.contains(uid) ||
                userCache.containsKey(uid) ||
                CrewLayoverStore.shared.getUserSummary(uid) != null
            ) {
                return@bind
            }
            fetchUser(uid)
        }
    }

    private fun fetchUser(uid: String) {
        if (uid.isBlank() || fetchingUsers.contains(uid)) return
        fetchingUsers.add(uid)
        FirebaseDatabase.getInstance().reference.child("crew_users").child(uid)
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
                userCache[uid] = CrewUserInfo(nickname, null, photoUrl, photosUrls)
                
                // Notify only items from this user
                items.forEachIndexed { index, msg ->
                    if (msg.senderUid == uid) {
                        notifyItemChanged(index)
                    }
                }
            }
            .addOnCompleteListener {
                fetchingUsers.remove(uid)
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

    class MsgVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: LinearLayout = itemView.findViewById(R.id.messageRow)
        private val avatar: ImageView = itemView.findViewById(R.id.messageAvatar)
        private val name: TextView = itemView.findViewById(R.id.messageName)
        private val tv: TextView = itemView.findViewById(R.id.messageText)
        private val image: ImageView = itemView.findViewById(R.id.messageImage)
        private val content: LinearLayout = itemView.findViewById(R.id.messageContent)

        init {
            avatar.clipToOutline = true
        }

        fun bind(
            msg: CrewChatMessage,
            myUid: String?,
            myName: String?,
            myPhoto: Bitmap?,
            cache: Map<String, CrewUserInfo>,
            onImageClick: ((CrewChatMessage) -> Unit)?,
            onAvatarClick: ((CrewChatMessage) -> Unit)?,
            onNeedUser: (String) -> Unit
        ) {
            val isMe = msg.senderUid == myUid
            val summary = CrewLayoverStore.shared.getUserSummary(msg.senderUid)
            val info = cache[msg.senderUid]
            if (msg.senderUid != "system" && info == null && summary == null) {
                onNeedUser(msg.senderUid)
            }
            val hasImage = !msg.imageBase64.isNullOrBlank() && !msg.isE2EEncrypted
            val now = System.currentTimeMillis()
            val isExpired = hasImage && msg.imageExpiresAtMs > 0L && now > msg.imageExpiresAtMs

            if (hasImage && !isExpired) {
                tv.visibility = View.GONE
                image.visibility = View.VISIBLE
                image.setImageBitmap(CrewPhotoLoader.shared.decodeBase64ToBitmap(msg.imageBase64))
                image.setOnClickListener { onImageClick?.invoke(msg) }
            } else {
                image.visibility = View.GONE
                tv.visibility = View.VISIBLE
                tv.text = when {
                    msg.isE2EEncrypted -> tv.context.getString(R.string.cl_e2e_message_preview)
                    hasImage -> tv.context.getString(R.string.cl_photo_expired)
                    else -> displayMessageText(msg.text)
                }
                image.setOnClickListener(null)
            }

            if (isMe) {
                tv.setTextColor(tv.context.getColor(R.color.white))
                tv.setBackgroundResource(R.drawable.bg_ios_btn_blue)
                name.text = myName?.takeIf { it.isNotBlank() }
                    ?: summary?.nickname?.takeIf { it.isNotBlank() }
                    ?: tv.context.getString(R.string.cl_chat)
                name.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            } else {
                tv.setTextColor(tv.context.getColor(R.color.iosText))
                tv.setBackgroundResource(R.drawable.bg_ios_pill_light)
                if (msg.senderUid == "system") {
                    name.text = tv.context.getString(R.string.cl_system_sender_name)
                } else {
                    name.text = info?.nickname?.takeIf { it.isNotBlank() }
                        ?: summary?.nickname?.takeIf { it.isNotBlank() }
                        ?: tv.context.getString(R.string.cl_chat)
                }
                name.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }

            val rowLp = row.layoutParams as FrameLayout.LayoutParams
            rowLp.gravity = if (isMe) android.view.Gravity.END else android.view.Gravity.START
            row.layoutParams = rowLp
            row.layoutDirection = if (isMe) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            content.layoutDirection = View.LAYOUT_DIRECTION_LTR

            val primaryRef = info?.primaryRef() ?: summary?.primaryRef()
            bindAvatar(msg.senderUid, if (isMe) myPhoto else null, primaryRef)

            avatar.setOnClickListener {
                onAvatarClick?.invoke(msg)
            }
        }

        private fun bindAvatar(uid: String, localBitmap: Bitmap?, primaryRef: String?) {
            avatar.tag = uid
            if (localBitmap != null) {
                avatar.setImageBitmap(localBitmap)
                return
            }

            CrewPhotoLoader.shared.memoryImage(uid)?.let {
                avatar.setImageBitmap(it)
                return
            }

            if (primaryRef.isNullOrBlank()) {
                avatar.setImageDrawable(null)
                return
            }

            if (primaryRef.startsWith("http")) {
                avatar.setImageDrawable(null)
                CrewPhotoLoader.shared.loadFromUrl(primaryRef, uid) { loaded ->
                    if (loaded != null && avatar.tag == uid) avatar.setImageBitmap(loaded)
                }
            } else {
                val bmp = CrewPhotoLoader.shared.getBitmap(uid, primaryRef)
                if (bmp != null) avatar.setImageBitmap(bmp) else avatar.setImageDrawable(null)
            }
        }

        private fun displayMessageText(raw: String): String {
            val value = raw.trim()
            return when {
                value == "cl_e2e_message_preview" || value.startsWith("cl_e2e_") ->
                    tv.context.getString(R.string.cl_e2e_message_preview)
                value == "cl_photo_message_preview" ->
                    tv.context.getString(R.string.cl_photo_message_preview)
                else -> raw
            }
        }
    }
}
