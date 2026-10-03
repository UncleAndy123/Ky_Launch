package com.flipos.launcher.ui

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.R
import com.flipos.launcher.util.accentColor

/**
 * A single list entry. Can be a normal row (leading icon, title, optional
 * subtitle, and one trailing affordance) or a non-focusable [section] header.
 *
 * Trailing affordance precedence: [toggle] (On/Off pill) > [chevron] (opens a
 * sub-screen) > [trailing] (plain value text). At most one is shown so the row
 * reads unambiguously.
 */
data class Row(
    val title: String,
    val subtitle: String? = null,
    val trailing: String? = null,
    val icon: Drawable? = null,
    val iconRes: Int? = null,
    /** Non-null renders an On/Off switch pill; use for inline toggles. */
    val toggle: Boolean? = null,
    /** Shows a chevron to signal the row opens another screen/chooser. */
    val chevron: Boolean = false,
    // The default title color selector darkens on focus to stay legible
    // against the accent highlight (Options, Settings, etc). The app drawer's
    // list view wants its title to read the same focused or not, like the
    // grid's labels do, so it opts out of that swap.
    val keepWhiteTitle: Boolean = false,
    // Notification dot color shown over the leading icon, or null for none.
    // Only set by the app drawer's list view; every other screen leaves it null.
    val badgeColor: Int? = null,
    /** True for the single row currently picked up by the app drawer's Move gesture; shows an accent-colored dot in place of [badgeColor]. */
    val isMoving: Boolean = false,
    /** Renders as a group header instead of a selectable row. */
    val isSection: Boolean = false,
    /** Stable identity for diffing; defaults to the title. */
    val id: String = title,
) {
    companion object {
        /** A non-focusable header used to group the rows beneath it. */
        fun section(title: String): Row = Row(title = title, isSection = true, id = "section:$title")
    }
}

/**
 * Generic vertical-list adapter shared by the settings, Hide Apps, Shortcuts and
 * App Picker screens. Clicks are reported by position so each screen owns its
 * own behavior. Supports section headers, subtitles and toggle/chevron trailing
 * affordances.
 */
class ListRowAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Int) -> Unit)? = null,
    private val onFocusChanged: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Row>()

    fun submit(list: List<Row>) {
        submitWithDiff(rows, list) { a, b -> a.id == b.id }
    }

    fun rowAt(position: Int): Row? = rows.getOrNull(position)

    fun updateRow(position: Int, row: Row) {
        if (position in rows.indices) {
            rows[position] = row
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position].isSection) TYPE_SECTION else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionVH(inflater.inflate(R.layout.item_list_section, parent, false))
        } else {
            RowVH(inflater.inflate(R.layout.item_list_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is SectionVH) holder.bind(row) else (holder as RowVH).bind(row)
    }

    class SectionVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.section_title)
        fun bind(row: Row) { title.text = row.title }
    }

    inner class RowVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconWrap: View = itemView.findViewById(R.id.row_icon_wrap)
        private val icon: ImageView = itemView.findViewById(R.id.row_icon)
        private val notifDot: View = itemView.findViewById(R.id.row_notif_dot)
        private val title: TextView = itemView.findViewById(R.id.row_title)
        private val subtitle: TextView = itemView.findViewById(R.id.row_subtitle)
        private val trailing: TextView = itemView.findViewById(R.id.row_trailing)
        private val toggle: TextView = itemView.findViewById(R.id.row_toggle)
        private val chevron: ImageView = itemView.findViewById(R.id.row_chevron)

        fun bind(row: Row) {
            when {
                row.icon != null -> {
                    icon.setImageDrawable(row.icon)
                    iconWrap.visibility = View.VISIBLE
                }
                row.iconRes != null -> {
                    icon.setImageResource(row.iconRes)
                    iconWrap.visibility = View.VISIBLE
                }
                else -> iconWrap.visibility = View.GONE
            }
            when {
                row.isMoving -> {
                    notifDot.visibility = View.VISIBLE
                    notifDot.backgroundTintList = ColorStateList.valueOf(itemView.context.accentColor())
                }
                row.badgeColor != null -> {
                    notifDot.visibility = View.VISIBLE
                    notifDot.backgroundTintList = ColorStateList.valueOf(row.badgeColor)
                }
                else -> notifDot.visibility = View.GONE
            }

            title.text = row.title
            title.setTextColor(
                if (row.keepWhiteTitle) {
                    ContextCompat.getColorStateList(itemView.context, R.color.kai_text)
                } else {
                    ContextCompat.getColorStateList(itemView.context, R.color.text_on_item)
                },
            )

            if (row.subtitle.isNullOrEmpty()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.text = row.subtitle
                subtitle.visibility = View.VISIBLE
            }

            // Trailing affordance: toggle pill, else chevron, else value text.
            when {
                row.toggle != null -> {
                    trailing.visibility = View.GONE
                    chevron.visibility = View.GONE
                    toggle.visibility = View.VISIBLE
                    val ctx = itemView.context
                    if (row.toggle) {
                        toggle.text = ctx.getString(R.string.settings_toggle_on)
                        toggle.setBackgroundResource(R.drawable.bg_toggle_on)
                        toggle.setTextColor(ContextCompat.getColorStateList(ctx, R.color.text_on_toggle_on))
                    } else {
                        toggle.text = ctx.getString(R.string.settings_toggle_off)
                        toggle.setBackgroundResource(R.drawable.bg_toggle_off)
                        toggle.setTextColor(ContextCompat.getColorStateList(ctx, R.color.text_secondary_on_item))
                    }
                }
                else -> {
                    toggle.visibility = View.GONE
                    trailing.text = row.trailing ?: ""
                    trailing.visibility = if (row.trailing.isNullOrEmpty()) View.GONE else View.VISIBLE
                    if (row.chevron) {
                        chevron.visibility = View.VISIBLE
                        chevron.imageTintList =
                            ContextCompat.getColorStateList(itemView.context, R.color.text_secondary_on_item)
                    } else {
                        chevron.visibility = View.GONE
                    }
                }
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick?.invoke(pos)
                onLongClick != null
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onFocusChanged?.invoke(pos)
                }
            }
        }
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_SECTION = 1
    }
}
