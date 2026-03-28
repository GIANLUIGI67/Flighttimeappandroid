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
    private val onClick: (NearbyCrewUser) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val expandedIds: MutableSet<String> = mutableSetOf()

    sealed class NearbyItem {
        data class Header(val title: String) : NearbyItem()
        data class User(val user: NearbyCrewUser) : NearbyItem()
    }

    fun submit(newItems: List<NearbyItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NearbyItem.Header -> 0
            is NearbyItem.User -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_simple_row, parent, false)
            HeaderVH(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crew_nearby, parent, false)
            UserVH(v, onClick, expandedIds)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NearbyItem.Header -> (holder as HeaderVH).bind(item)
            is NearbyItem.User -> (holder as UserVH).bind(item.user)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        fun bind(item: NearbyItem.Header) {
            title.text = item.title
        }
    }

    class UserVH(
        itemView: View,
        private val onClick: (NearbyCrewUser) -> Unit,
        private val expanded: MutableSet<String>
    ) : RecyclerView.ViewHolder(itemView) {
        private val photo: ImageView = itemView.findViewById(R.id.crewPhoto)
        private val name: TextView = itemView.findViewById(R.id.crewName)
        private val subtitle: TextView = itemView.findViewById(R.id.crewSubtitle)
        private val distance: TextView = itemView.findViewById(R.id.crewDistance)

        fun bind(user: NearbyCrewUser) {
            name.text = user.nickname
            val company = user.companyName ?: ""
            val base = user.baseCountryCode
            val role = if (user.role == CrewRole.CABIN_CREW) "Cabin" else "Flight deck"
            val parts = listOf(company, base, role).filter { it.isNotBlank() }
            subtitle.text = parts.joinToString(" • ")
            if (user.distanceKm < 0) {
                distance.text = ""
                distance.visibility = View.GONE
            } else {
                distance.visibility = View.VISIBLE
                distance.text = String.format(Locale.US, "%.0f km", user.distanceKm)
            }

            val bmp = CrewPhotoLoader.shared.getBitmap(user.userId, user.photoB64)
            if (bmp != null) {
                photo.setImageBitmap(bmp)
            } else {
                photo.setImageResource(R.drawable.bg_ios_pill_light)
            }

            val expandedNow = expanded.contains(user.userId)
            val size = dpToPx(if (expandedNow) 120 else 44, photo)
            val lp = photo.layoutParams
            lp.width = size
            lp.height = size
            photo.layoutParams = lp

            photo.setOnClickListener {
                if (expanded.contains(user.userId)) expanded.remove(user.userId) else expanded.add(user.userId)
                val newSize = dpToPx(if (expanded.contains(user.userId)) 120 else 44, photo)
                val newLp = photo.layoutParams
                newLp.width = newSize
                newLp.height = newSize
                photo.layoutParams = newLp
            }

            itemView.setOnClickListener { onClick(user) }
        }

        private fun dpToPx(dp: Int, v: View): Int {
            return (dp * v.resources.displayMetrics.density).toInt()
        }
    }
}
