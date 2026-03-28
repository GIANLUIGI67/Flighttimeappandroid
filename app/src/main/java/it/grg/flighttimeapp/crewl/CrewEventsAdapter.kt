package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import java.util.Date
import java.text.DateFormat

class CrewEventsAdapter(
    private var items: List<CrewLayoverEvent>,
    private var joinedIds: Set<String>,
    private val onJoin: (CrewLayoverEvent) -> Unit,
    private val onLeave: (CrewLayoverEvent) -> Unit,
    private val onOpenChat: (CrewLayoverEvent) -> Unit
) : RecyclerView.Adapter<CrewEventsAdapter.EventVH>() {

    private val df: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    fun submit(newItems: List<CrewLayoverEvent>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setJoinedIds(ids: Set<String>) {
        joinedIds = ids
        notifyDataSetChanged()
    }

    fun getItem(position: Int): CrewLayoverEvent? {
        return if (position in items.indices) items[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crew_event, parent, false)
        return EventVH(v, onJoin, onLeave, onOpenChat, df)
    }

    override fun onBindViewHolder(holder: EventVH, position: Int) {
        holder.bind(items[position], joinedIds.contains(items[position].id))
    }

    override fun getItemCount(): Int = items.size

    class EventVH(
        itemView: View,
        private val onJoin: (CrewLayoverEvent) -> Unit,
        private val onLeave: (CrewLayoverEvent) -> Unit,
        private val onOpenChat: (CrewLayoverEvent) -> Unit,
        private val df: DateFormat
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.eventTitle)
        private val whereText: TextView = itemView.findViewById(R.id.eventWhere)
        private val dateText: TextView = itemView.findViewById(R.id.eventDate)
        private val acceptedCount: TextView = itemView.findViewById(R.id.acceptedCount)
        private val joinedBadge: TextView = itemView.findViewById(R.id.joinedBadge)
        private val joinBtn: Button = itemView.findViewById(R.id.eventJoinBtn)

        fun bind(event: CrewLayoverEvent, isJoined: Boolean) {
            val whenText = df.format(Date(event.eventAtMs))
            val mt = MeetingType.fromRaw(event.meetingTypeRaw)
            title.text = itemView.context.getString(mt.labelResId)
            whereText.text = event.whereText
            dateText.text = whenText
            acceptedCount.text = event.acceptedCount.toString()
            joinedBadge.visibility = if (isJoined) View.VISIBLE else View.GONE

            joinBtn.text = if (isJoined) itemView.context.getString(R.string.cl_leave) else itemView.context.getString(R.string.cl_join)
            joinBtn.setOnClickListener {
                if (isJoined) onLeave(event) else onJoin(event)
            }
            itemView.setOnClickListener { onOpenChat(event) }
        }
    }

}
