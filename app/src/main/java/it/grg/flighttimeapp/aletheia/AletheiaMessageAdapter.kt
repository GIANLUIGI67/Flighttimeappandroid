package it.grg.flighttimeapp.aletheia

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R

class AletheiaMessageAdapter(
    private val onFeedback: (AletheiaChatMessage, AletheiaFeedbackRating) -> Unit
) : RecyclerView.Adapter<AletheiaMessageAdapter.VH>() {

    private val items = mutableListOf<AletheiaChatMessage>()

    fun submit(list: List<AletheiaChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_aletheia_message, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val badge: TextView = itemView.findViewById(R.id.aletheiaSourceBadge)
        private val text: TextView = itemView.findViewById(R.id.aletheiaMessageText)
        private val feedbackRow: LinearLayout = itemView.findViewById(R.id.aletheiaFeedbackRow)
        private val good: ImageButton = itemView.findViewById(R.id.aletheiaFbGood)
        private val partial: ImageButton = itemView.findViewById(R.id.aletheiaFbPartial)
        private val bad: ImageButton = itemView.findViewById(R.id.aletheiaFbBad)

        fun bind(msg: AletheiaChatMessage) {
            text.text = msg.text
            val isUser = msg.role == AletheiaChatMessage.Role.USER
            text.gravity = if (isUser) Gravity.END else Gravity.START

            val source = msg.source
            if (!isUser && source != null) {
                badge.visibility = View.VISIBLE
                badge.text = when (source) {
                    AletheiaSource.ONLINE -> itemView.context.getString(R.string.aletheia_badge_online)
                    AletheiaSource.OFFLINE -> itemView.context.getString(R.string.aletheia_badge_offline)
                    AletheiaSource.MANUALS -> itemView.context.getString(R.string.aletheia_badge_manuals)
                }
            } else {
                badge.visibility = View.GONE
            }

            val showFb = !isUser && !msg.pending && !msg.feedbackSent &&
                msg.role == AletheiaChatMessage.Role.ASSISTANT
            feedbackRow.visibility = if (showFb) View.VISIBLE else View.GONE
            good.setOnClickListener { onFeedback(msg, AletheiaFeedbackRating.GOOD) }
            partial.setOnClickListener { onFeedback(msg, AletheiaFeedbackRating.PARTIAL) }
            bad.setOnClickListener { onFeedback(msg, AletheiaFeedbackRating.BAD) }
        }
    }
}
