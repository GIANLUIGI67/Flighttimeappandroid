package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import java.util.Locale

class CrewNearbyAdapter(
    private var items: List<NearbyItem>,
    private val onClick: (NearbyCrewUser) -> Unit,
    private val onPhotoClick: (NearbyCrewUser, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class NearbyItem {
        data class Header(val title: String, val dotColorRes: Int) : NearbyItem()
        data class MosaicUser(val user: NearbyCrewUser) : NearbyItem()
        data class User(val user: NearbyCrewUser) : NearbyItem()
    }

    fun submit(newItems: List<NearbyItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NearbyItem.Header -> 0
            is NearbyItem.MosaicUser -> 2
            is NearbyItem.User -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_simple_row, parent, false)
            HeaderVH(v)
        } else if (viewType == 2) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crew_nearby_mosaic, parent, false)
            MosaicVH(v, onClick, onPhotoClick)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crew_nearby, parent, false)
            UserVH(v, onClick, onPhotoClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NearbyItem.Header -> (holder as HeaderVH).bind(item)
            is NearbyItem.MosaicUser -> (holder as MosaicVH).bind(item.user)
            is NearbyItem.User -> (holder as UserVH).bind(item.user)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        private val dot: View = itemView.findViewById(R.id.rowDot)
        fun bind(item: NearbyItem.Header) {
            title.text = item.title
            dot.background?.setTint(itemView.context.getColor(item.dotColorRes))
        }
    }

    class UserVH(
        itemView: View,
        private val onClick: (NearbyCrewUser) -> Unit,
        private val onPhotoClick: (NearbyCrewUser, Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val photo: ImageView = itemView.findViewById(R.id.crewPhoto)
        private val name: TextView = itemView.findViewById(R.id.crewName)
        private val subtitle: TextView = itemView.findViewById(R.id.crewSubtitle)
        private val bio: TextView = itemView.findViewById(R.id.crewBio)
        private val onlineChip: TextView = itemView.findViewById(R.id.crewOnlineChip)

        fun bind(user: NearbyCrewUser) {
            name.text = user.nickname
            val role = if (user.role == CrewRole.CABIN_CREW) "Cabin Crew" else "Flight Deck"
            subtitle.text = role
            val bioText = user.bio?.trim().orEmpty()
            if (bioText.isNotEmpty()) {
                bio.visibility = View.VISIBLE
                bio.text = bioText
            } else {
                bio.visibility = View.GONE
                bio.text = ""
            }
            onlineChip.visibility = if (user.isOnline) View.VISIBLE else View.GONE

            val primaryB64 = if (user.photosB64.isNotEmpty()) user.photosB64.first() else user.photoB64
            val bmp = CrewPhotoLoader.shared.getBitmap(user.userId, primaryB64)
            if (bmp != null) {
                photo.setImageBitmap(bmp)
            } else {
                photo.setImageResource(R.drawable.bg_ios_pill_light)
            }

            val size = dpToPx(44, photo)
            val lp = photo.layoutParams
            lp.width = size
            lp.height = size
            photo.layoutParams = lp

            photo.setOnClickListener {
                onPhotoClick(user, 0)
            }

            itemView.setOnClickListener { onClick(user) }
        }

        private fun dpToPx(dp: Int, v: View): Int {
            return (dp * v.resources.displayMetrics.density).toInt()
        }
    }

    class MosaicVH(
        itemView: View,
        private val onClick: (NearbyCrewUser) -> Unit,
        private val onPhotoClick: (NearbyCrewUser, Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val photo: ImageView = itemView.findViewById(R.id.mosaicPhoto)
        private val onlineDot: View = itemView.findViewById(R.id.mosaicOnlineDot)
        private val name: TextView = itemView.findViewById(R.id.mosaicName)
        private val subtitle: TextView = itemView.findViewById(R.id.mosaicSubtitle)
        private val bio: TextView = itemView.findViewById(R.id.mosaicBio)
        private val distance: TextView = itemView.findViewById(R.id.mosaicDistance)
        private val openChat: TextView = itemView.findViewById(R.id.mosaicOpenChat)

        fun bind(user: NearbyCrewUser) {
            name.text = user.nickname
            val role = if (user.role == CrewRole.CABIN_CREW) "Cabin Crew" else "Flight Deck"
            subtitle.text = role
            val bioText = user.bio?.trim().orEmpty()
            if (bioText.isNotEmpty()) {
                bio.visibility = View.VISIBLE
                bio.text = bioText
            } else {
                bio.visibility = View.GONE
                bio.text = ""
            }

            if (user.distanceKm < 0) {
                distance.text = ""
                distance.visibility = View.GONE
            } else {
                distance.visibility = View.VISIBLE
                distance.text = String.format(Locale.US, "%.0f km", user.distanceKm)
            }

            onlineDot.visibility = if (user.isOnline) View.VISIBLE else View.GONE
            onlineDot.background?.setTint(itemView.context.getColor(R.color.green_ok))

            val primaryB64 = if (user.photosB64.isNotEmpty()) user.photosB64.first() else user.photoB64
            val bmp = CrewPhotoLoader.shared.getBitmap(user.userId, primaryB64)
            if (bmp != null) {
                photo.setImageBitmap(bmp)
            } else {
                photo.setImageDrawable(null)
            }

            photo.setOnClickListener { onPhotoClick(user, 0) }
            openChat.setOnClickListener { onClick(user) }
            itemView.setOnClickListener { onClick(user) }
        }
    }
}
