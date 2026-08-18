package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R

/**
 * Multi-type RecyclerView adapter for agent chat bubbles.
 *
 * View types:
 *   0 — user message (right-aligned, filled)
 *   1 — agent message (left-aligned, with thinking + tool-call panels + typing indicator)
 *   2 — system message (centered, muted)
 */
class ChatMessageAdapter(
    private val onRetry: ((ChatMessage) -> Unit)? = null,
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) =
            old.text.toString() == new.text.toString() &&
                old.thinkingText.toString() == new.thinkingText.toString() &&
                old.toolCalls == new.toolCalls &&
                old.isComplete == new.isComplete &&
                old.isError == new.isError
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position).role) {
        ChatMessage.Role.USER -> 0
        ChatMessage.Role.AGENT -> 1
        ChatMessage.Role.SYSTEM -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            1 -> AgentHolder(inflater.inflate(R.layout.item_chat_agent, parent, false))
            else -> SystemHolder(inflater.inflate(R.layout.item_chat_system, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserHolder -> holder.bind(msg)
            is AgentHolder -> holder.bind(msg, onRetry)
            is SystemHolder -> holder.bind(msg)
        }
    }
}

// ── ViewHolders ──────────────────────────────────────────────

class UserHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val text: TextView = itemView.findViewById(R.id.userText)
    fun bind(msg: ChatMessage) { text.text = msg.text }
}

class AgentHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val agentLabel: TextView = itemView.findViewById(R.id.agentLabel)
    private val thinkingHeader: TextView = itemView.findViewById(R.id.thinkingHeader)
    private val thinkingText: TextView = itemView.findViewById(R.id.thinkingText)
    private val thinkingPanel: LinearLayout = itemView.findViewById(R.id.thinkingPanel)
    private val typingIndicator: TextView = itemView.findViewById(R.id.typingIndicator)
    private val bodyText: TextView = itemView.findViewById(R.id.agentText)
    private val toolsContainer: LinearLayout = itemView.findViewById(R.id.toolsContainer)
    private val errorHint: TextView = itemView.findViewById(R.id.errorHint)
    private val retryBtn: TextView = itemView.findViewById(R.id.retryBtn)

    private var thinkingExpanded = false

    fun bind(msg: ChatMessage, onRetry: ((ChatMessage) -> Unit)?) {
        // Agent label
        agentLabel.text = if (msg.isError) "Agent · Error" else "Agent"

        // Body text
        val hasText = msg.text.isNotEmpty()
        bodyText.text = if (hasText) msg.text else ""
        bodyText.isVisible = hasText

        // Typing indicator: show when agent is still streaming and no text yet
        typingIndicator.isVisible = !msg.isComplete && !msg.isError && !hasText

        // Thinking panel
        if (msg.thinkingText.isNotEmpty()) {
            thinkingText.text = msg.thinkingText
            thinkingPanel.isVisible = thinkingExpanded
            thinkingHeader.apply {
                text = if (thinkingExpanded) "▾ Thinking" else "▸ Thinking"
                setOnClickListener {
                    thinkingExpanded = !thinkingExpanded
                    thinkingPanel.isVisible = thinkingExpanded
                    text = if (thinkingExpanded) "▾ Thinking" else "▸ Thinking"
                }
            }
            thinkingHeader.isVisible = true
        } else {
            thinkingHeader.isVisible = false
            thinkingPanel.isVisible = false
        }

        // Tool calls
        toolsContainer.removeAllViews()
        msg.toolCalls.forEach { tc ->
            val chip = LayoutInflater.from(itemView.context)
                .inflate(R.layout.item_tool_chip, toolsContainer, false)
            val iconView: TextView = chip.findViewById(R.id.toolIcon)
            val nameView: TextView = chip.findViewById(R.id.toolName)
            val resultView: TextView = chip.findViewById(R.id.toolResult)

            val done = tc.result.isNotEmpty()
            iconView.text = if (tc.success) "✓" else if (done) "✗" else "⟳"
            val iconColor = if (tc.success) android.R.color.holo_green_dark
                else if (done) android.R.color.holo_red_dark
                else android.R.color.holo_blue_dark
            iconView.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, iconColor))
            nameView.text = tc.name
            resultView.text = tc.result
            resultView.isVisible = done
            toolsContainer.addView(chip)
        }
        toolsContainer.isVisible = msg.toolCalls.isNotEmpty()

        // Error state
        errorHint.isVisible = msg.isError
        retryBtn.isVisible = msg.isError
        retryBtn.setOnClickListener { onRetry?.invoke(msg) }
    }
}

class SystemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val text: TextView = itemView.findViewById(R.id.systemText)
    fun bind(msg: ChatMessage) { text.text = msg.text }
}