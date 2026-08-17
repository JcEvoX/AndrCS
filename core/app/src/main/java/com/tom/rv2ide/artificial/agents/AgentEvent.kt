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

package com.tom.rv2ide.artificial.agents

/**
 * 类型化的 Agent 事件流。`chat()` 返回 `Flow<AgentEvent>`,UI 按子类分别渲染。
 *
 * 设计参考 ACSIDE 反编译样本(`afad2351...` / `com.nullij.androidcodestudio`)
 * 中的 `mcp/agent/AgentEvent` sealed class。仅复用架构思想,不复制反编译源码。
 */
sealed interface AgentEvent {

  /** 模型思考中(reasoning_content 或 thinking 内容)。增量片段通过 [delta] 拼接。 */
  data class Thinking(
    val delta: String,
  ) : AgentEvent

  /** 模型产生的可读文本(非工具调用)。增量片段通过 [delta] 拼接。 */
  data class TextDelta(
    val delta: String,
  ) : AgentEvent

  /** 模型请求调用一个工具。ReAct 循环中可能多次发出此事件。 */
  data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
  ) : AgentEvent

  /** 工具执行结束的结果(成功/拒绝/失败)。 */
  data class ToolResult(
    val callId: String,
    val toolName: String,
    val output: String,
    val successful: Boolean,
  ) : AgentEvent

  /** 一轮 Agent 循环结束,产生最终响应。 */
  data class FinalResponse(
    val text: String,
    val totalTokens: Int = 0,
    val promptTokens: Int = 0,
    val candidateTokens: Int = 0,
  ) : AgentEvent

  /** 不可恢复的错误。ReAct 循环终止。 */
  data class Error(
    val message: String,
    val cause: Throwable? = null,
  ) : AgentEvent
}
