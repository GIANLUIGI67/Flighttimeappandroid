package it.grg.flighttimeapp.crewl

import android.app.Dialog
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import it.grg.flighttimeapp.R

class CrewPhotoPreviewDialog : DialogFragment() {

    private lateinit var pager: ViewPager2
    private lateinit var indicator: TextView
    private lateinit var avatar: ImageView
    private lateinit var heartIcon: ImageView
    private lateinit var likeIcon: ImageView
    private lateinit var heartCount: TextView
    private lateinit var likeCount: TextView

    private var ownerUid: String = ""
    private var photosB64: List<String> = emptyList()
    private var initialIndex: Int = 0
    private var threadId: String? = null
    private var messageId: String? = null
    private var avatarB64: String? = null
    private var peerName: String = ""
    private var peerCompany: String = ""

    private var reactionsObserver: FirebaseReactionsObserver? = null
    private var latestHeartUsers: List<String> = emptyList()
    private var latestLikeUsers: List<String> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_photo_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ownerUid = requireArguments().getString(ARG_OWNER_UID).orEmpty()
        photosB64 = requireArguments().getStringArrayList(ARG_PHOTOS) ?: emptyList()
        initialIndex = requireArguments().getInt(ARG_INDEX, 0)
        threadId = requireArguments().getString(ARG_THREAD_ID)
        messageId = requireArguments().getString(ARG_MESSAGE_ID)
        avatarB64 = requireArguments().getString(ARG_AVATAR_B64)
        peerName = requireArguments().getString(ARG_PEER_NAME).orEmpty()
        peerCompany = requireArguments().getString(ARG_PEER_COMPANY).orEmpty()

        pager = view.findViewById(R.id.photoPager)
        indicator = view.findViewById(R.id.photoIndicator)
        avatar = view.findViewById<ImageView>(R.id.photoAvatar).also { it.clipToOutline = true }
        heartIcon = view.findViewById(R.id.heartIcon)
        likeIcon = view.findViewById(R.id.likeIcon)
        heartCount = view.findViewById(R.id.heartCount)
        likeCount = view.findViewById(R.id.likeCount)
        val heartContainer: android.view.ViewGroup = view.findViewById(R.id.heartContainer)
        val likeContainer: android.view.ViewGroup = view.findViewById(R.id.likeContainer)

        pager.adapter = PhotoPagerAdapter(photosB64)
        if (photosB64.isNotEmpty()) {
            pager.setCurrentItem(initialIndex.coerceIn(0, photosB64.lastIndex), false)
        }
        updateIndicator(pager.currentItem)

        val avatarSource = avatarB64 ?: photosB64.firstOrNull()
        fun bindAvatar(src: String?) {
            if (src == null) { avatar.visibility = View.GONE; return }
            if (src.startsWith("http")) {
                val cached = CrewPhotoLoader.shared.image(ownerUid)
                if (cached != null) {
                    avatar.setImageBitmap(cached); avatar.visibility = View.VISIBLE
                } else {
                    avatar.visibility = View.GONE
                    CrewPhotoLoader.shared.loadFromUrl(src, ownerUid) { bmp ->
                        if (bmp != null) { avatar.setImageBitmap(bmp); avatar.visibility = View.VISIBLE }
                    }
                }
            } else {
                val bmp = CrewPhotoLoader.shared.getBitmap("avatar_$ownerUid", src)
                if (bmp != null) { avatar.setImageBitmap(bmp); avatar.visibility = View.VISIBLE }
                else { avatar.visibility = View.GONE }
            }
        }
        bindAvatar(avatarSource)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                if (threadId == null || messageId == null) {
                    startReactionsObserver(position)
                }
            }
        })

        // Toggle reaction on the whole container (larger tap target than the 22dp icon)
        heartContainer.setOnClickListener {
            reactionsObserver?.toggleHeart()
            animatePulse(heartIcon)
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        likeContainer.setOnClickListener {
            reactionsObserver?.toggleLike()
            animatePulse(likeIcon)
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // Tap on the count label shows who reacted
        heartCount.setOnClickListener {
            ReactionListBottomSheet.show(
                fragmentManager = parentFragmentManager,
                title = getString(R.string.cl_hearts),
                userIds = ArrayList(latestHeartUsers)
            ) { uid, nickname, company ->
                openChat(uid, nickname, company)
            }
        }
        likeCount.setOnClickListener {
            ReactionListBottomSheet.show(
                fragmentManager = parentFragmentManager,
                title = getString(R.string.cl_likes),
                userIds = ArrayList(latestLikeUsers)
            ) { uid, nickname, company ->
                openChat(uid, nickname, company)
            }
        }

        if (threadId != null && messageId != null) {
            startChatImageReactionsObserver()
        } else {
            startReactionsObserver(pager.currentItem)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reactionsObserver?.stop()
        reactionsObserver = null
    }

    private fun updateIndicator(position: Int) {
        if (photosB64.isEmpty()) {
            indicator.text = "0/0"
        } else {
            indicator.text = "${position + 1}/${photosB64.size}"
        }
    }

    private fun startReactionsObserver(photoIndex: Int) {
        reactionsObserver?.stop()
        val ref = FirebaseDatabase.getInstance().reference
            .child("crew_photo_reactions")
            .child(ownerUid)
            .child(photoIndex.toString())
        reactionsObserver = FirebaseReactionsObserver(ref) { state ->
            updateReactionUi(state)
        }
        reactionsObserver?.start()
    }

    private fun startChatImageReactionsObserver() {
        reactionsObserver?.stop()
        val ref = FirebaseDatabase.getInstance().reference
            .child("crew_chat_image_reactions")
            .child(threadId!!)
            .child(messageId!!)
        reactionsObserver = FirebaseReactionsObserver(ref) { state ->
            updateReactionUi(state)
        }
        reactionsObserver?.start()
    }

    private fun updateReactionUi(state: FirebaseReactionsObserver.State) {
        latestHeartUsers = state.heartUserIds
        latestLikeUsers = state.likeUserIds
        heartCount.text = state.heartCount.toString()
        likeCount.text = state.likeCount.toString()
        heartIcon.setImageResource(if (state.hearted) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        likeIcon.setImageResource(if (state.liked) R.drawable.ic_like_filled else R.drawable.ic_like_outline)
    }

    private fun openChat(uid: String, nickname: String, company: String?) {
        val ctx = requireContext()
        val intent = android.content.Intent(ctx, CrewChatActivity::class.java).apply {
            putExtra(CrewChatActivity.EXTRA_PEER_ID, uid)
            putExtra(CrewChatActivity.EXTRA_PEER_NAME, nickname)
            putExtra(CrewChatActivity.EXTRA_PEER_COMPANY, company ?: "")
        }
        startActivity(intent)
    }

    private fun animatePulse(v: View) {
        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).withEndAction {
            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        }.start()
    }

    companion object {
        private const val ARG_OWNER_UID = "owner_uid"
        private const val ARG_PHOTOS = "photos"
        private const val ARG_INDEX = "index"
        private const val ARG_THREAD_ID = "thread_id"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_AVATAR_B64 = "avatar_b64"
        private const val ARG_PEER_NAME = "peer_name"
        private const val ARG_PEER_COMPANY = "peer_company"

        fun show(
            fragmentManager: FragmentManager,
            ownerUid: String,
            photosB64: ArrayList<String>,
            initialIndex: Int,
            threadId: String?,
            messageId: String?,
            avatarB64: String?,
            peerName: String,
            peerCompany: String
        ) {
            val frag = CrewPhotoPreviewDialog()
            frag.arguments = bundleOf(
                ARG_OWNER_UID to ownerUid,
                ARG_PHOTOS to photosB64,
                ARG_INDEX to initialIndex,
                ARG_THREAD_ID to threadId,
                ARG_MESSAGE_ID to messageId,
                ARG_AVATAR_B64 to avatarB64,
                ARG_PEER_NAME to peerName,
                ARG_PEER_COMPANY to peerCompany
            )
            frag.show(fragmentManager, "photo_preview")
        }
    }
}

private class PhotoPagerAdapter(
    private val photos: List<String>
) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_pager, parent, false)
        return PhotoVH(v)
    }

    override fun getItemCount(): Int = photos.size

    override fun onBindViewHolder(holder: PhotoVH, position: Int) {
        holder.bind(photos[position])
    }

    class PhotoVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.pagerImage)

        fun bind(photoRef: String) {
            image.setImageDrawable(null)
            if (photoRef.startsWith("http")) {
                // Storage URL — load asynchronously, cache by URL hash
                val cacheKey = "pager_url_${photoRef.hashCode()}"
                val cached = CrewPhotoLoader.shared.image(cacheKey)
                if (cached != null) {
                    image.setImageBitmap(cached)
                } else {
                    CrewPhotoLoader.shared.loadFromUrl(photoRef, cacheKey) { bmp ->
                        if (bmp != null) image.setImageBitmap(bmp)
                    }
                }
            } else {
                // Legacy base64
                val bmp = CrewPhotoLoader.shared.getBitmap("pager_${photoRef.hashCode()}", photoRef)
                if (bmp != null) image.setImageBitmap(bmp)
            }
        }
    }
}

private class FirebaseReactionsObserver(
    private val reactionsRef: DatabaseReference,
    private val onUpdate: (State) -> Unit
) {
    data class State(
        val likeCount: Int,
        val heartCount: Int,
        val liked: Boolean,
        val hearted: Boolean,
        val likeUserIds: List<String>,
        val heartUserIds: List<String>
    )

    private var likesListener: ValueEventListener? = null
    private var heartsListener: ValueEventListener? = null

    private var likeIds: List<String> = emptyList()
    private var heartIds: List<String> = emptyList()

    fun start() {
        val likesRef = reactionsRef.child("likes")
        val heartsRef = reactionsRef.child("hearts")

        likesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                likeIds = snapshot.children.mapNotNull { it.key }
                notifyState()
            }

            override fun onCancelled(error: DatabaseError) {
                android.util.Log.e("Reactions", "likes observe cancelled: ${error.message} code=${error.code}")
            }
        }

        heartsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                heartIds = snapshot.children.mapNotNull { it.key }
                notifyState()
            }

            override fun onCancelled(error: DatabaseError) {
                android.util.Log.e("Reactions", "hearts observe cancelled: ${error.message} code=${error.code}")
            }
        }

        likesRef.addValueEventListener(likesListener as ValueEventListener)
        heartsRef.addValueEventListener(heartsListener as ValueEventListener)
    }

    fun stop() {
        likesListener?.let { reactionsRef.child("likes").removeEventListener(it) }
        heartsListener?.let { reactionsRef.child("hearts").removeEventListener(it) }
        likesListener = null
        heartsListener = null
    }

    fun toggleLike() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = reactionsRef.child("likes").child(uid)
        if (likeIds.contains(uid)) {
            // Optimistic remove — mirrors iOS setLikeLocal(false)
            likeIds = likeIds.filter { it != uid }
            notifyState()
            ref.removeValue { error, _ ->
                if (error != null) {
                    android.util.Log.e("Reactions", "removeValue likes failed: ${error.message} code=${error.code}")
                    likeIds = likeIds + uid
                    notifyState()
                }
            }
        } else {
            // Optimistic add — mirrors iOS setLikeLocal(true)
            likeIds = likeIds + uid
            notifyState()
            ref.setValue(true) { error, _ ->
                if (error != null) {
                    android.util.Log.e("Reactions", "setValue likes failed: ${error.message} code=${error.code}")
                    likeIds = likeIds.filter { it != uid }
                    notifyState()
                }
            }
        }
    }

    fun toggleHeart() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = reactionsRef.child("hearts").child(uid)
        if (heartIds.contains(uid)) {
            // Optimistic remove — mirrors iOS setHeartLocal(false)
            heartIds = heartIds.filter { it != uid }
            notifyState()
            ref.removeValue { error, _ ->
                if (error != null) {
                    android.util.Log.e("Reactions", "removeValue hearts failed: ${error.message} code=${error.code}")
                    heartIds = heartIds + uid
                    notifyState()
                }
            }
        } else {
            // Optimistic add — mirrors iOS setHeartLocal(true)
            heartIds = heartIds + uid
            notifyState()
            ref.setValue(true) { error, _ ->
                if (error != null) {
                    android.util.Log.e("Reactions", "setValue hearts failed: ${error.message} code=${error.code}")
                    heartIds = heartIds.filter { it != uid }
                    notifyState()
                }
            }
        }
    }

    private fun notifyState() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val state = State(
            likeCount = likeIds.size,
            heartCount = heartIds.size,
            liked = likeIds.contains(uid),
            hearted = heartIds.contains(uid),
            likeUserIds = likeIds,
            heartUserIds = heartIds
        )
        onUpdate(state)
    }
}

private class ReactionListBottomSheet : BottomSheetDialogFragment() {
    private var title: String = ""
    private var userIds: List<String> = emptyList()
    private var onUserClick: ((String, String, String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = requireArguments().getString(ARG_TITLE).orEmpty()
        userIds = requireArguments().getStringArrayList(ARG_USERS) ?: emptyList()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_reaction_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val titleView = view.findViewById<TextView>(R.id.reactionSheetTitle)
        val recycler = view.findViewById<RecyclerView>(R.id.reactionSheetRecycler)
        titleView.text = title
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = ReactionUserAdapter(userIds, CrewLayoverStore.shared) { uid, nickname, company ->
            dismiss()
            onUserClick?.invoke(uid, nickname, company)
        }
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_USERS = "users"

        fun show(
            fragmentManager: FragmentManager,
            title: String,
            userIds: ArrayList<String>,
            onUserClick: (String, String, String?) -> Unit
        ) {
            val sheet = ReactionListBottomSheet()
            sheet.onUserClick = onUserClick
            sheet.arguments = bundleOf(
                ARG_TITLE to title,
                ARG_USERS to userIds
            )
            sheet.show(fragmentManager, "reaction_list")
        }
    }
}

private class ReactionUserAdapter(
    private val userIds: List<String>,
    private val store: CrewLayoverStore,
    private val onClick: (String, String, String?) -> Unit
) : RecyclerView.Adapter<ReactionUserAdapter.UserVH>() {

    private val requested: MutableSet<String> = mutableSetOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reaction_user, parent, false)
        return UserVH(v)
    }

    override fun getItemCount(): Int = userIds.size

    override fun onBindViewHolder(holder: UserVH, position: Int) {
        val uid = userIds[position]
        val summary = store.getUserSummary(uid)
        if (summary == null && !requested.contains(uid)) {
            requested.add(uid)
            store.fetchUserOnce(uid) { notifyDataSetChanged() }
        }
        holder.bind(uid, summary, onClick)
    }

    class UserVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val photo: ImageView = itemView.findViewById<ImageView>(R.id.reactionUserPhoto).also { it.clipToOutline = true }
        private val name: TextView = itemView.findViewById(R.id.reactionUserName)

        fun bind(uid: String, summary: CrewLayoverStore.CrewUserSummary?, onClick: (String, String, String?) -> Unit) {
            val nickname = summary?.nickname ?: "Crew"
            name.text = nickname
            val primaryRef = summary?.primaryRef()
            if (primaryRef != null) {
                if (primaryRef.startsWith("http")) {
                    val cached = CrewPhotoLoader.shared.image(uid)
                    if (cached != null) photo.setImageBitmap(cached)
                    else { photo.setImageDrawable(null); CrewPhotoLoader.shared.loadFromUrl(primaryRef, uid) { bmp -> if (bmp != null) photo.setImageBitmap(bmp) } }
                } else {
                    val bmp = CrewPhotoLoader.shared.getBitmap(uid, primaryRef)
                    if (bmp != null) photo.setImageBitmap(bmp) else photo.setImageDrawable(null)
                }
            } else {
                photo.setImageDrawable(null)
            }
            itemView.setOnClickListener { onClick(uid, nickname, summary?.companyName) }
        }
    }
}
