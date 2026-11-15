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
    /** When true (RL consent Yes), show the continue-hint until a rating is chosen. */
    var feedbackRequired: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

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

    companion object {
        /**
         * Label = the engine that REALLY produced the reply (PR-5): Aletheia online,
         * Aletheia 1.5B on the phone (verified or not), or fixed checks without a model.
         * Only a reply without engine information falls back to the channel badge.
         */
        fun badgeTextRes(msg: AletheiaChatMessage): Int? {
            val label = msg.engineLabel
            if (label != null) {
                return when (label) {
                    AletheiaEngineLabel.ONLINE -> R.string.aletheia_engine_online
                    AletheiaEngineLabel.LOCAL_VERIFIED -> R.string.aletheia_engine_local
                    AletheiaEngineLabel.LOCAL_UNVERIFIED -> R.string.aletheia_engine_local_unverified
                    AletheiaEngineLabel.DETERMINISTIC ->
                        if (msg.source == AletheiaSource.MANUALS) R.string.aletheia_engine_fixed_manuals
                        else R.string.aletheia_engine_fixed
                }
            }
            return when (msg.source) {
                AletheiaSource.ONLINE -> R.string.aletheia_badge_online
                AletheiaSource.OFFLINE -> R.string.aletheia_badge_offline
                AletheiaSource.MANUALS -> R.string.aletheia_badge_manuals
                null -> null
            }
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val badge: TextView = itemView.findViewById(R.id.aletheiaSourceBadge)
        private val text: TextView = itemView.findViewById(R.id.aletheiaMessageText)
        private val feedbackRow: LinearLayout = itemView.findViewById(R.id.aletheiaFeedbackRow)
        private val continueHint: TextView = itemView.findViewById(R.id.aletheiaFeedbackContinueHint)
        private val good: ImageButton = itemView.findViewById(R.id.aletheiaFbGood)
        private val partial: ImageButton = itemView.findViewById(R.id.aletheiaFbPartial)
        private val bad: ImageButton = itemView.findViewById(R.id.aletheiaFbBad)

        fun bind(msg: AletheiaChatMessage) {
            text.text = msg.text
            val isUser = msg.role == AletheiaChatMessage.Role.USER
            text.gravity = if (isUser) Gravity.END else Gravity.START

            val label = if (isUser) null else badgeTextRes(msg)
            if (label != null) {
                badge.visibility = View.VISIBLE
                badge.text = itemView.context.getString(label)
            } else {
                badge.visibility = View.GONE
            }

            val showFb = !isUser && !msg.pending && !msg.feedbackSent &&
                msg.role == AletheiaChatMessage.Role.ASSISTANT
            feedbackRow.visibility = if (showFb) View.VISIBLE else View.GONE
            // Instructional line only while RL consent Yes and rating still required.
            continueHint.visibility =
                if (showFb && feedbackRequired) View.VISIBLE else View.GONE
            good.setOnClickListener { onFeedback(msg, AletheiaFeedbackRating.GOOD) }
            partial.setOnClickListener { onFeedback(msg, AletheiaFeedbackRating.PARTIAL) }
            bad.setOnClickListener { onFeedback(msg, AletheiaFeedbackRating.BAD) }
        }
    }
}
