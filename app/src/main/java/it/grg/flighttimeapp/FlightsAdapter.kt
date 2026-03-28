package it.grg.flighttimeapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

class FlightsAdapter(
    private val flights: List<FlightEntry>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<FlightsAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val timeText: TextView = itemView.findViewById(R.id.timeText)
        val noteText: TextView = itemView.findViewById(R.id.noteText)
        val deleteIcon: ImageView = itemView.findViewById(R.id.deleteIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_flight, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = flights.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = flights[position]

        val sign = if (entry.minutes >= 0) "+" else "−"
        val absMin = abs(entry.minutes)
        val h = absMin / 60
        val m = absMin % 60

        // ✅ no string literal formatting in setText: use resources
        holder.timeText.text = holder.itemView.context.getString(
            R.string.entry_time_format,
            sign, h, m
        )

        // note è dinamico: ok
        holder.noteText.text = entry.note

        holder.deleteIcon.setOnClickListener { onDelete(position) }
    }
}