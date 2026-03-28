@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import java.time.format.DateTimeFormatter

class FlightLogAdapter(
    private var items: List<FlightLog>,
    private val onClick: (FlightLog) -> Unit
) : RecyclerView.Adapter<FlightLogAdapter.VH>() {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val route: TextView = itemView.findViewById(R.id.flightRoute)
        val time: TextView = itemView.findViewById(R.id.flightTime)
        val date: TextView = itemView.findViewById(R.id.flightDate)
        val base: TextView = itemView.findViewById(R.id.flightBase)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_flight_log, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.route.text = if (f.route.isBlank()) "—" else f.route
        holder.time.text = SalaryCalculatorEngine.hhmm(f.minutes)
        holder.date.text = f.date.format(formatter)
        holder.base.text = if (f.isScheduled) holder.itemView.context.getString(R.string.salary_block_base_scheduled)
        else holder.itemView.context.getString(R.string.salary_block_base_actual)
        holder.itemView.setOnClickListener { onClick(f) }
    }

    fun update(newItems: List<FlightLog>) {
        items = newItems
        notifyDataSetChanged()
    }
}
