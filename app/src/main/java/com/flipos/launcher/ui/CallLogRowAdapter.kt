package com.flipos.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.provider.CallLog
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.R

/** One `CallLog.Calls` row, resolved for display. */
data class CallLogItem(
    val id: Long,
    val number: String,
    val displayName: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
)

/** Rows for the Recent Calls screen: reuses the Notices row shape (icon, title, subtitle, relative time). */
class CallLogRowAdapter(
    private val onClick: (CallLogItem) -> Unit,
    private val onFocusChanged: (CallLogItem) -> Unit = {},
) : RecyclerView.Adapter<CallLogRowAdapter.VH>() {

    private val items = ArrayList<CallLogItem>()

    fun submit(list: List<CallLogItem>) {
        submitWithDiff(items, list) { a, b -> a.id == b.id }
    }

    fun itemAt(position: Int): CallLogItem? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notice_row, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.notice_icon)
        private val title: TextView = itemView.findViewById(R.id.notice_title)
        private val text: TextView = itemView.findViewById(R.id.notice_text)
        private val time: TextView = itemView.findViewById(R.id.notice_time)

        fun bind(item: CallLogItem) {
            val ctx = itemView.context
            icon.setImageResource(R.drawable.ic_call)
            icon.imageTintList = if (isMissed(item.type)) {
                ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.kai_accent_red))
            } else {
                null
            }
            title.text = item.displayName?.takeIf { it.isNotBlank() } ?: item.number
            text.text = callTypeLabel(ctx, item)
            time.text = DateUtils.getRelativeTimeSpanString(
                item.date,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocusChanged(item) }
        }
    }

    companion object {
        private fun isMissed(type: Int): Boolean =
            type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE

        private fun callTypeLabel(ctx: Context, item: CallLogItem): String {
            val direction = when (item.type) {
                CallLog.Calls.INCOMING_TYPE -> ctx.getString(R.string.calllog_incoming)
                CallLog.Calls.OUTGOING_TYPE -> ctx.getString(R.string.calllog_outgoing)
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> ctx.getString(R.string.calllog_missed)
                else -> ctx.getString(R.string.calllog_other)
            }
            return if (item.duration > 0) {
                ctx.getString(R.string.calllog_duration_format, direction, formatDuration(item.duration))
            } else {
                direction
            }
        }

        private fun formatDuration(seconds: Long): String {
            val m = seconds / 60
            val s = seconds % 60
            return if (m > 0) "${m}m ${s}s" else "${s}s"
        }
    }
}
