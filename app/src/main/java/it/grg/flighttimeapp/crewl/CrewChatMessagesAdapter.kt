package it.grg.flighttimeapp.crewl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import it.grg.flighttimeapp.R

class CrewChatMessagesAdapter(
    private var items: List<CrewChatMessage>
) : RecyclerView.Adapter<CrewChatMessagesAdapter.MsgVH>() {

    private val myUid: String? = FirebaseAuth.getInstance().currentUser?.uid
    private var myName: String? = null
    private var myPhoto: Bitmap? = null
    private val userCache: MutableMap<String, CrewUserInfo> = mutableMapOf()
    private val expandedIds: MutableSet<String> = mutableSetOf()

    fun submit(newItems: List<CrewChatMessage>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setMyProfile(name: String?, photo: Bitmap?) {
        myName = name
        myPhoto = photo
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MsgVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MsgVH, position: Int) {
        holder.bind(items[position], myUid, myName, myPhoto, userCache, expandedIds) { uid ->
            if (userCache.containsKey(uid)) return@bind
            fetchUser(uid)
        }
    }

    private fun fetchUser(uid: String) {
        FirebaseDatabase.getInstance().reference.child("crew_users").child(uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val dict = snapshot.value as? Map<*, *> ?: return@addOnSuccessListener
                val nickname = dict["nickname"] as? String
                val photoB64 = dict["photoB64"] as? String
                userCache[uid] = CrewUserInfo(nickname, photoB64)
                notifyDataSetChanged()
            }
    }

    data class CrewUserInfo(val nickname: String?, val photoB64: String?)

    class MsgVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: LinearLayout = itemView.findViewById(R.id.messageRow)
        private val avatar: ImageView = itemView.findViewById(R.id.messageAvatar)
        private val name: TextView = itemView.findViewById(R.id.messageName)
        private val tv: TextView = itemView.findViewById(R.id.messageText)
        private val image: ImageView = itemView.findViewById(R.id.messageImage)
        private val content: LinearLayout = itemView.findViewById(R.id.messageContent)

        fun bind(
            msg: CrewChatMessage,
            myUid: String?,
            myName: String?,
            myPhoto: Bitmap?,
            cache: Map<String, CrewUserInfo>,
            expanded: MutableSet<String>,
            onNeedUser: (String) -> Unit
        ) {
            val isMe = msg.senderUid == myUid
            val hasImage = !msg.imageBase64.isNullOrBlank()
            val now = System.currentTimeMillis()
            val isExpired = hasImage && msg.imageExpiresAtMs > 0L && now > msg.imageExpiresAtMs

            if (hasImage && !isExpired) {
                tv.visibility = View.GONE
                image.visibility = View.VISIBLE
                image.setImageBitmap(decodeBase64(msg.imageBase64!!))
            } else {
                image.visibility = View.GONE
                tv.visibility = View.VISIBLE
                tv.text = if (hasImage && isExpired) {
                    tv.context.getString(R.string.cl_photo_expired)
                } else {
                    msg.text
                }
            }

            if (isMe) {
                tv.setTextColor(tv.context.getColor(R.color.white))
                tv.setBackgroundResource(R.drawable.bg_ios_btn_blue)
                name.text = myName ?: tv.context.getString(R.string.cl_chat)
                name.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            } else {
                tv.setTextColor(tv.context.getColor(R.color.iosText))
                tv.setBackgroundResource(R.drawable.bg_ios_pill_light)
                if (msg.senderUid == "system") {
                    name.text = tv.context.getString(R.string.cl_system_sender_name)
                } else {
                    val info = cache[msg.senderUid]
                    name.text = info?.nickname ?: tv.context.getString(R.string.cl_chat)
                }
                name.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                val info = cache[msg.senderUid]
                if (msg.senderUid != "system" && info == null) {
                    onNeedUser(msg.senderUid)
                }
            }

            val rowLp = row.layoutParams as FrameLayout.LayoutParams
            rowLp.gravity = if (isMe) android.view.Gravity.END else android.view.Gravity.START
            row.layoutParams = rowLp
            row.layoutDirection = if (isMe) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            content.layoutDirection = View.LAYOUT_DIRECTION_LTR

            if (isMe) {
                if (myPhoto != null) {
                    avatar.setImageBitmap(myPhoto)
                } else {
                    avatar.setImageDrawable(null)
                }
            } else {
                val info = cache[msg.senderUid]
                val bmp = info?.photoB64?.let { CrewPhotoLoader.shared.getBitmap(msg.senderUid, it) }
                if (bmp != null) {
                    avatar.setImageBitmap(bmp)
                } else {
                    avatar.setImageDrawable(null)
                }
            }

            val expandedNow = expanded.contains(msg.id)
            val size = dpToPx(if (expandedNow) 120 else 32, avatar)
            val lp = avatar.layoutParams
            lp.width = size
            lp.height = size
            avatar.layoutParams = lp

            avatar.setOnClickListener {
                if (expanded.contains(msg.id)) expanded.remove(msg.id) else expanded.add(msg.id)
                val newSize = dpToPx(if (expanded.contains(msg.id)) 120 else 32, avatar)
                val newLp = avatar.layoutParams
                newLp.width = newSize
                newLp.height = newSize
                avatar.layoutParams = newLp
            }
        }

        private fun dpToPx(dp: Int, v: View): Int {
            return (dp * v.resources.displayMetrics.density).toInt()
        }

        private fun decodeBase64(b64: String): Bitmap? {
            return try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }
}
