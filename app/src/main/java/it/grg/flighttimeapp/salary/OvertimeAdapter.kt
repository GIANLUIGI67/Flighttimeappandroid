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

class OvertimeAdapter(
    private var items: List<OvertimeLog>,
    private val onClick: (OvertimeLog) -> Unit
) : RecyclerView.Adapter<OvertimeAdapter.VH>() {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.rowTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = items[position]
        holder.title.text = log.date.format(formatter)
        holder.itemView.setOnClickListener { onClick(log) }
    }

    fun update(newItems: List<OvertimeLog>) {
        items = newItems
        notifyDataSetChanged()
    }
}
