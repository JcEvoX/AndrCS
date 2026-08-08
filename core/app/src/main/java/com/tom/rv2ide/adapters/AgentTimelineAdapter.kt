package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R

/** Presents the visible audit trail of an agent request and its workspace operations. */
class AgentTimelineAdapter : RecyclerView.Adapter<AgentTimelineAdapter.ViewHolder>() {

  enum class Kind {
    USER,
    PLAN,
    TOOL,
    RESULT,
    ERROR,
  }

  data class Event(val kind: Kind, val title: String, val detail: String)

  private val events = mutableListOf<Event>()

  class ViewHolder(
      val card: MaterialCardView,
      val title: MaterialTextView,
      val detail: MaterialTextView,
  ) : RecyclerView.ViewHolder(card)

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val view =
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_agent_timeline_event, parent, false) as MaterialCardView
    return ViewHolder(
        view,
        view.findViewById(R.id.timelineEventTitle),
        view.findViewById(R.id.timelineEventDetail),
    )
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val event = events[position]
    holder.title.text = event.title
    holder.detail.text = event.detail

    val color =
        when (event.kind) {
          Kind.USER -> ContextCompat.getColor(holder.card.context, R.color.primary)
          Kind.PLAN -> ContextCompat.getColor(holder.card.context, R.color.change_modified)
          Kind.TOOL -> ContextCompat.getColor(holder.card.context, R.color.change_added)
          Kind.RESULT -> ContextCompat.getColor(holder.card.context, R.color.success)
          Kind.ERROR -> ContextCompat.getColor(holder.card.context, R.color.error)
        }
    holder.card.strokeColor = color
  }

  override fun getItemCount(): Int = events.size

  fun add(event: Event) {
    events += event
    notifyItemInserted(events.lastIndex)
  }

  fun clear() {
    events.clear()
    notifyDataSetChanged()
  }
}
