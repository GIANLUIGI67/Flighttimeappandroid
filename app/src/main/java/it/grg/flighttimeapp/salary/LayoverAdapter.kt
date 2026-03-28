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

class LayoverAdapter(
    private var items: List<LayoverLog>,
    private val onClick: (LayoverLog) -> Unit
) : RecyclerView.Adapter<LayoverAdapter.VH>() {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.rowTitle)
        val subtitle: TextView = itemView.findViewById(R.id.rowSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = items[position]
        holder.title.text = log.date.format(formatter)
        holder.subtitle.text = if (log.location.isBlank()) "—" else log.location
        holder.subtitle.visibility = View.VISIBLE
        holder.itemView.setOnClickListener { onClick(log) }
    }

    fun update(newItems: List<LayoverLog>) {
        items = newItems
        notifyDataSetChanged()
    }
}
