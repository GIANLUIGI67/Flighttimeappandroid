package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class LayoverInfoItemAdapter(
    private var items: List<LayoverInfoItem>,
    private val canDelete: (LayoverInfoItem) -> Boolean,
    private val onDelete: (LayoverInfoItem) -> Unit,
    private val onEdit: (LayoverInfoItem) -> Unit
) : RecyclerView.Adapter<LayoverInfoItemAdapter.VH>() {

    fun submit(newItems: List<LayoverInfoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layover_info, parent, false)
        return VH(view, canDelete, onDelete, onEdit)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(
        itemView: View,
        private val canDelete: (LayoverInfoItem) -> Boolean,
        private val onDelete: (LayoverInfoItem) -> Unit,
        private val onEdit: (LayoverInfoItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.layoverInfoTitle)
        private val location: TextView = itemView.findViewById(R.id.layoverInfoLocation)
        private val details: TextView = itemView.findViewById(R.id.layoverInfoDetails)
        private val editBtn: ImageView = itemView.findViewById(R.id.layoverInfoEdit)
        private val deleteBtn: ImageView = itemView.findViewById(R.id.layoverInfoDelete)

        fun bind(item: LayoverInfoItem) {
            title.text = item.title
            if (item.location.isBlank()) {
                location.visibility = View.GONE
            } else {
                location.visibility = View.VISIBLE
                location.text = item.location
            }
            if (item.details.isBlank()) {
                details.visibility = View.GONE
            } else {
                details.visibility = View.VISIBLE
                details.text = item.details
            }

            val allowed = canDelete(item)
            editBtn.visibility = if (allowed) View.VISIBLE else View.INVISIBLE
            deleteBtn.visibility = if (allowed) View.VISIBLE else View.INVISIBLE
            editBtn.setOnClickListener {
                if (allowed) onEdit(item)
            }
            deleteBtn.setOnClickListener {
                if (allowed) onDelete(item)
            }
            itemView.setOnClickListener {
                if (allowed) onEdit(item)
            }
        }
    }
}
