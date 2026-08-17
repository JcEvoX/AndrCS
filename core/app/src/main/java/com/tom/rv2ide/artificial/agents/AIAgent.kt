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

import android.content.Context
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.tools.ToolDefinition
import kotlinx.coroutines.flow.Flow

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

interface AIAgent {
    val providerId: String
    val providerName: String

    fun initialize(apiKey: String, context: Context)
    fun reinitializeWithNewModel(apiKey: String, context: Context)
    fun setContext(context: Context)
    fun setProjectData(projectTreeResult: ProjectTreeResult)
    fun clearConversation()

    /**
     * 旧接口:同步阻塞返回完整字符串。保留以兼容现有 provider 实现。
     * 新代码应优先使用 [chat]。
     */
    suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String>

    /**
     * 新接口:ReAct 流式 Agent。模型可调用 [tools] 中的工具,工具结果会自动回填到
     * 对话历史并继续下一轮,直到模型给出最终回答或达到 [maxIterations]。
     *
     * 默认实现回退到 [generateCode],provider 可按需覆盖以提供原生 tool_calls 支持。
     *
     * 注意:chat() 本身不声明 suspend —— 返回的 Flow 在 collect 时才在协程上下文里执行
     * suspend 逻辑(如 [generateCode]、withContext、网络请求)。这样非 suspend 的
     * 入口函数(比如 AIAgentManager.executeChatStreaming)也能直接调用 chat() 返回
     * Flow,再由 UI 侧 lifecycleScope 调 collect。
     */
    fun chat(
        prompt: String,
        tools: List<ToolDefinition> = emptyList(),
        onToolCall: suspend (com.tom.rv2ide.artificial.tools.ToolCall) -> com.tom.rv2ide.artificial.tools.ToolResult = { _ ->
            com.tom.rv2ide.artificial.tools.ToolResult.Failure("default", "Tool execution not wired")
        },
        maxIterations: Int = 8,
    ): Flow<AgentEvent> = kotlinx.coroutines.flow.flow {
        // 默认实现:回退到旧接口,把字符串包成 FinalResponse 事件。
        // 注意:flow body 是 suspend 的,collect 时支持 cancellation(取消协程即中断)。
        val result = generateCode(prompt, context = null, language = "kotlin", projectStructure = null)
        result.fold(
            onSuccess = { emit(AgentEvent.FinalResponse(it)) },
            onFailure = { emit(AgentEvent.Error(it.message ?: "Unknown error", it)) },
        )
    }

    fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean)
    fun undoLastModification(): Boolean
    fun getModificationHistory(): List<ModificationAttempt>

    fun resetAttemptCount()
    fun incrementAttemptCount()
    fun getCurrentAttemptCount(): Int
    fun canRetry(): Boolean

    fun writeFile(filePath: String, content: String): FileWriteResult
    fun isInitialized(): Boolean
}

data class ModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)