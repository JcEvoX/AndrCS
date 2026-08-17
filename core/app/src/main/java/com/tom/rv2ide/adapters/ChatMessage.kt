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

/**
 * 聊天消息 UI 模型。一条 [ChatMessage] 对应 RecyclerView 的一行,
 * 由 [com.tom.rv2ide.adapters.ChatMessageAdapter] 按 [type] 选择 view-type 渲染。
 *
 * 流式追加通过 [appendText] / [appendReasoning] / [setResult] 原地修改 + adapter
 * 调 `notifyItemChanged` 实现,而不是插入新行 —— 这是流式 chat UI 的关键。
 */
data class ChatMessage(
    val id: String,
    val type: Type,
    /** 用户消息原文 / 助手最终文本 / 错误内容。 */
    var text: String = "",
    /** Reasoning 增量片段(DeepSeek R1 / Claude thinking / GLM-4.5)。 */
    var reasoning: String = "",
    /** 工具调用:工具名。 */
    var toolName: String = "",
    /** 工具调用:参数 JSON。 */
    var toolArguments: String = "",
    /** 工具调用:执行结果(成功时 output,失败时 reason)。 */
    var toolResult: String = "",
    /** 工具调用:是否成功完成。null = 尚未返回结果。 */
    var toolSuccessful: Boolean? = null,
    /** 工具调用:是否在 UI 上展开参数。 */
    var toolExpanded: Boolean = false,
    /** Reasoning 是否在 UI 上展开(默认折叠,只显示标题)。 */
    var reasoningExpanded: Boolean = false,
    /** 时间戳,用于唯一标识和历史排序。 */
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Type {
        USER,
        ASSISTANT,
        REASONING,
        TOOL_CALL,
        ERROR,
    }

    fun appendText(delta: String) {
        if (delta.isNotBlank()) text += delta
    }

    fun appendReasoning(delta: String) {
        if (delta.isNotBlank()) reasoning += delta
    }

    fun setResult(output: String, successful: Boolean) {
        toolResult = output
        toolSuccessful = successful
    }
}
