package com.tom.rv2ide.adapters

/**
 * UI model for a single chat bubble in the agent conversation.
 */
class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val role: Role,
    val text: StringBuilder = StringBuilder(),
    val thinkingText: StringBuilder = StringBuilder(),
    val toolCalls: MutableList<ToolCallItem> = mutableListOf(),
    val isComplete: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Role { USER, AGENT, SYSTEM }

    fun appendText(delta: String): ChatMessage {
        text.append(delta)
        return this
    }

    fun appendThinking(delta: String): ChatMessage {
        thinkingText.append(delta)
        return this
    }

    fun addToolCall(item: ToolCallItem): ChatMessage {
        toolCalls.add(item)
        return this
    }

    fun updateToolResult(id: String, result: String, success: Boolean): ChatMessage {
        toolCalls.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { idx ->
            toolCalls[idx] = toolCalls[idx].copy(result = result, success = success)
        }
        return this
    }

    fun markComplete(): ChatMessage = copy(isComplete = true)
    fun markError(): ChatMessage = copy(isError = true)

    private fun copy(
        id: Long = this.id,
        isUser: Boolean = this.isUser,
        role: Role = this.role,
        text: StringBuilder = this.text,
        thinkingText: StringBuilder = this.thinkingText,
        toolCalls: MutableList<ToolCallItem> = this.toolCalls,
        isComplete: Boolean = this.isComplete,
        isError: Boolean = this.isError,
        timestamp: Long = this.timestamp,
    ): ChatMessage = ChatMessage(
        id = id, isUser = isUser, role = role,
        text = text, thinkingText = thinkingText,
        toolCalls = toolCalls, isComplete = isComplete,
        isError = isError, timestamp = timestamp,
    )
}

data class ToolCallItem(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String = "",
    val success: Boolean = false,
)