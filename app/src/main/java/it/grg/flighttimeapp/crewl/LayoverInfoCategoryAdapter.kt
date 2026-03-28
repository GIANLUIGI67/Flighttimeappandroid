package it.grg.flighttimeapp.crewl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class LayoverInfoCategoryAdapter(
    private var items: List<LayoverInfoCategory>,
    private val onClick: (LayoverInfoCategory) -> Unit
) : RecyclerView.Adapter<LayoverInfoCategoryAdapter.VH>() {

    private var counts: Map<String, Int> = emptyMap()

    fun submit(newItems: List<LayoverInfoCategory>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun submitCounts(newCounts: Map<String, Int>) {
        counts = newCounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layover_info_category, parent, false)
        return VH(view, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val count = counts[item.key] ?: 0
        holder.bind(item, count)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onClick: (LayoverInfoCategory) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.layoverCategoryIcon)
        private val title: TextView = itemView.findViewById(R.id.layoverCategoryTitle)
        private val countText: TextView = itemView.findViewById(R.id.layoverCategoryCount)

        fun bind(item: LayoverInfoCategory, count: Int) {
            icon.setImageResource(item.iconResId)
            title.setText(item.labelResId)
            if (count > 0) {
                countText.visibility = View.VISIBLE
                countText.text = count.toString()
            } else {
                countText.visibility = View.GONE
                countText.text = ""
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
