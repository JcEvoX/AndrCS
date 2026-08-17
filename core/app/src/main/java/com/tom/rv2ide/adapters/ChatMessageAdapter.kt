/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R

/**
 * 多 view-type 聊天消息 adapter。每种 [ChatMessage.Type] 对应一个 item layout。
 *
 * 流式追加通过 [appendTextToLast] / [appendReasoningToLast] / [setToolResult]
 * 原地修改 + `notifyItemChanged(position)` 实现 —— 不插入新行,而是更新同一行。
 */
class ChatMessageAdapter :
    ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val TYPE_USER = 1
        const val TYPE_ASSISTANT = 2
        const val TYPE_REASONING = 3
        const val TYPE_TOOL_CALL = 4
        const val TYPE_ERROR = 5

        val DIFF =
            object : DiffUtil.ItemCallback<ChatMessage>() {
                override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
                    old.id == new.id

                override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean =
                    old == new
            }
    }

    /** 添加一条新消息,返回新位置。 */
    fun add(message: ChatMessage): Int {
        val list = currentList.toMutableList()
        list.add(message)
        submitList(list)
        return list.size - 1
    }

    /** 给最后一条 ASSISTANT 消息追加文本,返回位置(未找到返回 -1)。 */
    fun appendTextToLast(delta: String): Int {
        val list = currentList.toMutableList()
        for (i in list.indices.reversed()) {
            val m = list[i]
            if (m.type == ChatMessage.Type.ASSISTANT) {
                m.appendText(delta)
                notifyItemChanged(i)
                return i
            }
        }
        return -1
    }

    /** 给最后一条 REASONING 消息追加内容,返回位置(未找到返回 -1)。 */
    fun appendReasoningToLast(delta: String): Int {
        val list = currentList.toMutableList()
        for (i in list.indices.reversed()) {
            val m = list[i]
            if (m.type == ChatMessage.Type.REASONING) {
                m.appendReasoning(delta)
                notifyItemChanged(i)
                return i
            }
        }
        return -1
    }

    /** 设置最后一条 TOOL_CALL 消息的执行结果,返回位置(未找到返回 -1)。 */
    fun setToolResult(callId: String, output: String, successful: Boolean): Int {
        val list = currentList.toMutableList()
        for (i in list.indices.reversed()) {
            val m = list[i]
            if (m.type == ChatMessage.Type.TOOL_CALL && m.id == callId) {
                m.setResult(output, successful)
                notifyItemChanged(i)
                return i
            }
        }
        return -1
    }

    fun clear() {
        submitList(emptyList())
    }

    override fun getItemViewType(position: Int): Int =
        when (currentList[position].type) {
            ChatMessage.Type.USER -> TYPE_USER
            ChatMessage.Type.ASSISTANT -> TYPE_ASSISTANT
            ChatMessage.Type.REASONING -> TYPE_REASONING
            ChatMessage.Type.TOOL_CALL -> TYPE_TOOL_CALL
            ChatMessage.Type.ERROR -> TYPE_ERROR
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_chat_message_user, parent, false))
            TYPE_ASSISTANT -> AssistantVH(inflater.inflate(R.layout.item_chat_message_assistant, parent, false))
            TYPE_REASONING -> ReasoningVH(inflater.inflate(R.layout.item_chat_reasoning, parent, false))
            TYPE_TOOL_CALL -> ToolCallVH(inflater.inflate(R.layout.item_chat_tool_call, parent, false))
            TYPE_ERROR -> ErrorVH(inflater.inflate(R.layout.item_chat_error, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = currentList[position]
        when (holder) {
            is UserVH -> holder.bind(msg)
            is AssistantVH -> holder.bind(msg)
            is ReasoningVH -> holder.bind(msg)
            is ToolCallVH -> holder.bind(msg)
            is ErrorVH -> holder.bind(msg)
        }
    }

    class UserVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: MaterialTextView = itemView.findViewById(R.id.chatUserText)
        fun bind(msg: ChatMessage) {
            text.text = msg.text
        }
    }

    class AssistantVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: MaterialTextView = itemView.findViewById(R.id.chatAssistantText)
        fun bind(msg: ChatMessage) {
            // 空文本时显示流式光标占位,让用户看到"正在生成"。
            text.text = if (msg.text.isBlank()) "▍" else msg.text
        }
    }

    class ReasoningVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val header: MaterialTextView = itemView.findViewById(R.id.chatReasoningHeader)
        private val body: MaterialTextView = itemView.findViewById(R.id.chatReasoningBody)
        private val toggle: MaterialButton = itemView.findViewById(R.id.chatReasoningToggle)
        fun bind(msg: ChatMessage) {
            header.text = if (msg.reasoning.isBlank()) "Thinking…" else "Thinking (${msg.reasoning.length} chars)"
            body.text = msg.reasoning
            body.visibility = if (msg.reasoningExpanded) View.VISIBLE else View.GONE
            toggle.text = if (msg.reasoningExpanded) "Collapse" else "Expand"
            toggle.setOnClickListener {
                msg.reasoningExpanded = !msg.reasoningExpanded
                body.visibility = if (msg.reasoningExpanded) View.VISIBLE else View.GONE
                toggle.text = if (msg.reasoningExpanded) "Collapse" else "Expand"
            }
        }
    }

    class ToolCallVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: MaterialTextView = itemView.findViewById(R.id.chatToolName)
        private val args: MaterialTextView = itemView.findViewById(R.id.chatToolArgs)
        private val argsContainer: ViewGroup = itemView.findViewById(R.id.chatToolArgsContainer)
        private val toggle: MaterialButton = itemView.findViewById(R.id.chatToolToggle)
        private val result: MaterialTextView = itemView.findViewById(R.id.chatToolResult)
        fun bind(msg: ChatMessage) {
            name.text = "🔧 ${msg.toolName}"
            args.text = msg.toolArguments
            result.text =
                when (msg.toolSuccessful) {
                    null -> "Running…"
                    true -> "✅ ${msg.toolResult}"
                    false -> "❌ ${msg.toolResult}"
                }
            argsContainer.visibility = if (msg.toolExpanded) View.VISIBLE else View.GONE
            toggle.text = if (msg.toolExpanded) "Hide args" else "Show args"
            toggle.setOnClickListener {
                msg.toolExpanded = !msg.toolExpanded
                argsContainer.visibility = if (msg.toolExpanded) View.VISIBLE else View.GONE
                toggle.text = if (msg.toolExpanded) "Hide args" else "Show args"
            }
        }
    }

    class ErrorVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: MaterialTextView = itemView.findViewById(R.id.chatErrorText)
        fun bind(msg: ChatMessage) {
            text.text = msg.text
        }
    }
}
