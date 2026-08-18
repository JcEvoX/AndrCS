package com.tom.rv2ide.artificial.agents

/** Typed events emitted by an AI agent during a streaming chat session. */
sealed interface AgentEvent {
    /** Agent is reasoning / planning — display in a collapsible panel. */
    data class Thinking(val text: String) : AgentEvent

    /** Incremental text delta from the agent's response. */
    data class TextDelta(val text: String) : AgentEvent

    /** Agent is about to invoke a tool. */
    data class ToolCall(val call: com.tom.rv2ide.artificial.tools.ToolCall) : AgentEvent

    /** Result of a tool invocation. */
    data class ToolResult(val result: com.tom.rv2ide.artificial.tools.ToolResult) : AgentEvent

    /** Final complete response (when streaming is done). */
    data class FinalResponse(val text: String) : AgentEvent

    /** An error occurred during processing. */
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent
}