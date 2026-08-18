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
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
    
    suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String>
    
    /** Streaming chat with tool execution. Returns a Flow of typed events for UI rendering. */
    fun chat(
        prompt: String,
        tools: List<ToolDefinition> = emptyList(),
        onToolCall: suspend (ToolCall) -> ToolResult = { _ ->
            ToolResult.Failure("default", "Tool execution not wired")
        },
        maxIterations: Int = 8,
    ): Flow<AgentEvent> = flowOf(
        AgentEvent.Error("chat() not implemented for ${this.providerName}")
    )
    
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