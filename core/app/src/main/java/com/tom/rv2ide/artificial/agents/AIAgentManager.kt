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
import com.tom.rv2ide.artificial.agents.google.Gemini
import com.tom.rv2ide.artificial.agents.openai.OpenAI
import com.tom.rv2ide.artificial.agents.anthropic.Anthropic
import com.tom.rv2ide.artificial.agents.grok.Grok
import com.tom.rv2ide.artificial.agents.deepseek.DeepSeek
import com.tom.rv2ide.artificial.agents.local.LocalLLM
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.artificial.project.awareness.ProjectData
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.artificial.agents.AgentEvent
import com.tom.rv2ide.artificial.tools.LegacyFileModificationParser
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolCallParser
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.WorkspaceToolExecutor
import com.tom.rv2ide.artificial.tools.WorkspaceToolRegistry
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.dialogs.AIPermissionDialog

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class AIAgentManager(private val context: Context) {

    private val permissionManager = AIPermissionManager(context)
    private val legacyModificationParser = LegacyFileModificationParser()
    private val workspaceToolExecutor =
        WorkspaceToolExecutor(
            permissionManager,
            AIFileWriter(context),
            { currentProjectRoot },
        ) { path -> confirmFileWrite(path) }
    private var currentProjectRoot: File? = null
    private var currentProviderId: String = "gemini"
    private var currentAgent: AIAgent? = null
    private val providerSwitchDialog = ProviderSwitchDialog(context)

    init {
        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()
        
        permissionManager.initializeDefaults()
        
        setProvider(currentProviderId)
    }
    
    fun getCurrentAgent(): AIAgent? = currentAgent

    fun setProvider(providerId: String): Boolean {
        android.util.Log.d("AIAgentManager", "setProvider called with: $providerId")
        
        val factory = AIAgentRegistry.getFactory(providerId)
        if (factory == null) {
            android.util.Log.e("AIAgentManager", "No factory found for provider: $providerId")
            return false
        }
        
        if (!factory.hasValidApiKey()) {
            android.util.Log.e("AIAgentManager", "No valid API key for provider: $providerId")
            return false
        }
        
        currentProviderId = providerId
        currentAgent = factory.create(context)
        android.util.Log.d("AIAgentManager", "Agent created: ${currentAgent != null}")
        
        factory.getApiKey()?.let { apiKey ->
            android.util.Log.d("AIAgentManager", "Initializing agent with API key")
            currentAgent?.initialize(apiKey, context)
            currentAgent?.setContext(context)
            
            currentProjectRoot?.let { root ->
                val projectData = ProjectData()
                val projectTree = projectData.showProjectTree(root)
                currentAgent?.setProjectData(projectTree)
            }
            
            android.util.Log.d("AIAgentManager", "Agent initialized: ${currentAgent?.isInitialized()}")
        }
        
        return currentAgent?.isInitialized() ?: false
    }

    fun getCurrentProviderId(): String = currentProviderId
    
    fun getCurrentProviderName(): String {
        return currentAgent?.providerName ?: "Unknown"
    }
    
    fun getAvailableProviders(): List<ProviderInfo> {
        return AIAgentRegistry.getAvailableProviders().mapNotNull { providerId ->
            val factory = AIAgentRegistry.getFactory(providerId)
            val agent = factory?.create(context)
            agent?.let {
                ProviderInfo(
                    id = it.providerId,
                    name = it.providerName,
                    isAvailable = factory.hasValidApiKey()
                )
            }
        }
    }

    fun setProjectRoot(projectPath: String): Boolean {
        val projectRoot = File(projectPath)
        if (!projectRoot.exists()) return false

        currentProjectRoot = projectRoot
        val projectData = ProjectData()
        val projectTree = projectData.showProjectTree(projectRoot)

        currentAgent?.setProjectData(projectTree)
        permissionManager.addAllowedDirectory(projectRoot.absolutePath)

        return true
    }

    fun clearConversation() {
        currentAgent?.clearConversation()
    }

    /**
     * 流式入口:直接把 [AIAgent.chat] 的 `Flow<AgentEvent>` 透传给 UI。
     *
     * 与 [executeChat] 不同,这里不做任何字符串拍扁,UI 能拿到完整的类型化事件流,
     * 可以区分 Thinking / TextDelta / ToolCall / ToolResult / FinalResponse / Error,
     * 实现 Trae/Cline 风格的流式 agent UI(气泡流式追加、思考可折叠、工具卡可折叠)。
     *
     * 工具执行仍走 [workspaceToolExecutor],写操作仍走用户确认。
     *
     * 当 provider 未覆盖 [AIAgent.chat] 时,默认实现回退到 generateCode 并发出
     * FinalResponse 事件,所以非 OpenAI provider 也能用此入口。
     */
    fun executeChatStreaming(userRequest: String): kotlinx.coroutines.flow.Flow<AgentEvent> {
        val agent = currentAgent
        return if (agent == null) {
            kotlinx.coroutines.flow.flowOf(AgentEvent.Error("No agent initialized"))
        } else {
            agent.chat(
                prompt = userRequest,
                tools = WorkspaceToolRegistry.definitions,
                onToolCall = { call -> workspaceToolExecutor.execute(call) },
            )
        }
    }

    /**
     * 新接口:消费 `Flow<AgentEvent>`,把类型化的事件映射到现有 [AIAgentCallback]。
     *
     * 调用 [AIAgent.chat] 并把 [WorkspaceToolRegistry.definitions] 作为工具集传给模型。
     * 工具执行委托给 [workspaceToolExecutor],写操作仍走用户确认。
     *
     * 当 provider 未覆盖 chat() 时,默认实现回退到 generateCode 并包装为
     * AgentEvent.FinalResponse,所以非 OpenAI 的 provider 不需要改动即可使用此入口。
     */
    suspend fun executeChat(userRequest: String, callback: AIAgentCallback) {
        val agent = currentAgent
        if (agent == null) {
            callback.onError("No agent initialized")
            return
        }

        agent.resetAttemptCount()
        callback.onProcessing("Analyzing your request...")

        val modifications = mutableListOf<ModificationResult>()
        val textBuilder = StringBuilder()
        // tool_call_id → path,用于 ToolResult 事件回查 mutating 工具的目标文件。
        val pathByCallId = mutableMapOf<String, String>()

        try {
            agent.chat(
                prompt = userRequest,
                tools = WorkspaceToolRegistry.definitions,
                onToolCall = { call -> workspaceToolExecutor.execute(call) },
            ).collect { event ->
                when (event) {
                    is AgentEvent.Thinking -> {
                        callback.onProcessing("💭 thinking...")
                    }
                    is AgentEvent.TextDelta -> {
                        textBuilder.append(event.delta)
                    }
                    is AgentEvent.ToolCall -> {
                        // 记录 path 供 ToolResult 回查;对 mutating 工具同时回写 file modifying。
                        val path = extractPathFromArguments(event.argumentsJson)
                        if (path != null) pathByCallId[event.id] = path
                        if (WorkspaceToolRegistry.requiresConfirmation(event.name) && path != null) {
                            callback.onFileModifying(path, File(path).name)
                        }
                    }
                    is AgentEvent.ToolResult -> {
                        if (WorkspaceToolRegistry.requiresConfirmation(event.toolName)) {
                            val path = pathByCallId[event.callId]
                            if (path != null) {
                                callback.onFileModified(path, File(path).name, event.successful)
                                if (event.successful && event.toolName == "write_file") {
                                    modifications += ModificationResult(
                                        filePath = path,
                                        content = "",
                                        success = true,
                                        message = "Modified via tool call",
                                        isNewFile = !File(path).exists(),
                                    )
                                }
                            }
                        }
                    }
                    is AgentEvent.FinalResponse -> {
                        val response = event.text.ifBlank { textBuilder.toString() }
                        if (modifications.isEmpty()) {
                            val summary = ModificationSummary(0, 0, 0, 0, 0, emptyList())
                            callback.onTextResponse(response, summary)
                        } else {
                            val summary = createSummary(modifications)
                            callback.onSuccess(response, modifications, summary)
                        }
                    }
                    is AgentEvent.Error -> {
                        callback.onError(event.message)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AIAgentManager", "executeChat failed", e)
            callback.onError(formatErrorMessage(e))
        }
    }

    private fun extractPathFromArguments(argumentsJson: String): String? {
        return try {
            val obj = if (argumentsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(argumentsJson)
            obj.optString("path").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun executeRequest(userRequest: String, callback: AIAgentCallback) {
        var success = false
        var providerSwitched = false

        currentAgent?.resetAttemptCount()
        callback.onProcessing("Analyzing your request...")

        while (!success && (currentAgent?.canRetry() == true)) {
            try {
                val currentAttempt = currentAgent?.getCurrentAttemptCount() ?: 0

                if (currentAttempt > 0 && !providerSwitched) {
                    callback.onRetry(currentAttempt, "Thinking differently...")
                    delay(1000)
                }

                val previousFileStates = captureCurrentFileStates()

                val result = currentAgent?.generateCode(
                    prompt = userRequest,
                    context = null,
                    language = "kotlin",
                    projectStructure = null
                ) ?: Result.failure(Exception("No agent initialized"))

                result.fold(
                    onSuccess = { response ->
                        
                        if (response.contains("FILE_TO_MODIFY:")) {
                            callback.onProcessing("Modifying files...")
                            val modifications = processModifications(response, previousFileStates, callback)

                            if (modifications.isNotEmpty()) {
                                val permissionDenied = modifications.firstOrNull {
                                    it.writeResult is FileWriteResult.PermissionDenied
                                }?.writeResult as? FileWriteResult.PermissionDenied
                                if (permissionDenied != null) {
                                    callback.onError(permissionDenied.reason)
                                    success = true
                                    return@fold
                                }

                                val allSuccessful = modifications.all { it.writeResult is FileWriteResult.Success }

                                if (allSuccessful) {
                                    val results = modifications.map { mod ->
                                        val isNewFile = !previousFileStates.containsKey(mod.filePath)
                                        ModificationResult(
                                            filePath = mod.filePath,
                                            content = mod.content,
                                            success = true,
                                            message = "Modified successfully",
                                            isNewFile = isNewFile
                                        )
                                    }

                                    val summary = createSummary(results)
                                    callback.onSuccess(response, results, summary)
                                    success = true
                                } else {
                                    callback.onProcessing("Some files failed. Retrying...")
                                    currentAgent?.incrementAttemptCount()
                                    delay(1500)
                                }
                            } else {
                                callback.onProcessing("No files were modified. Retrying...")
                                currentAgent?.incrementAttemptCount()
                                delay(1500)
                            }
                        } else {
                            val summary = ModificationSummary(0, 0, 0, 0, 0, emptyList())
                            callback.onTextResponse(response, summary)
                            success = true
                        }
                    },
                  onFailure = { error ->
                      android.util.Log.e("AIAgentManager", "Error occurred: ${error.message}", error)
                      
                      val shouldSwitchProvider = error is com.tom.rv2ide.artificial.exceptions.RateLimitException ||
                                                error is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ||
                                                error is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ||
                                                error is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
                      
                      if (shouldSwitchProvider && !providerSwitched) {
                          val currentProviderName = currentAgent?.providerName ?: "Unknown"
                          val errorMsg = error.message ?: "Unknown error"
                          
                          if (providerSwitchDialog.isAutoSwitchEnabled()) {
                              val alternativeProvider = getAlternativeProvider()
                              if (alternativeProvider != null) {
                                  callback.onProcessing("⚠️ $currentProviderName: $errorMsg")
                                  callback.onProcessing("🔄 Auto-switching to another provider...")
                                  delay(1500)
                                  
                                  if (setProvider(alternativeProvider)) {
                                      providerSwitched = true
                                      currentAgent?.resetAttemptCount()
                                      
                                      val newProviderName = currentAgent?.providerName ?: "Unknown"
                                      callback.onProcessing("✅ Switched to $newProviderName")
                                  } else {
                                      val errorDisplay = formatErrorMessage(error)
                                      callback.onError("$errorDisplay\n\n❌ Failed to switch providers.")
                                      success = true
                                  }
                              } else {
                                  val errorDisplay = formatErrorMessage(error)
                                  callback.onError("$errorDisplay\n\n❌ No alternative providers available.")
                                  success = true
                              }
                          } else {
                              val errorDisplay = formatErrorMessage(error)
                              callback.onError("PROVIDER_SWITCH_REQUIRED::$errorDisplay")
                              success = true
                          }
                      } else if ((currentAgent?.canRetry() == true) && !providerSwitched) {
                          callback.onRetry(
                              currentAgent?.getCurrentAttemptCount() ?: 0,
                              "Error: ${error.message?.take(50) ?: "Unknown error"}. Retrying..."
                          )
                          currentAgent?.incrementAttemptCount()
                          delay(1500)
                      } else {
                          val errorDisplay = formatErrorMessage(error)
                          callback.onError(errorDisplay)
                          success = true
                      }
                  }
                )
            } catch (e: Exception) {
                android.util.Log.e("AIAgentManager", "Exception occurred: ${e.message}", e)
                
                if (currentAgent?.canRetry() == true) {
                    callback.onRetry(
                        currentAgent?.getCurrentAttemptCount() ?: 0,
                        "Exception: ${e.message?.take(50) ?: "Unknown"}. Trying again..."
                    )
                    currentAgent?.incrementAttemptCount()
                    delay(1500)
                } else {
                    val errorDisplay = formatErrorMessage(e)
                    callback.onError(errorDisplay)
                    success = true
                }
            }
        }

        if (!success) {
          val attemptCount = currentAgent?.getCurrentAttemptCount() ?: 0
          val agentName = currentAgent?.providerName ?: "No agent initialized"
          callback.onError("Failed after $attemptCount attempts with $agentName.\n\nPlease check your API key and try again.")
          undoLastModification()
        }
    }

    private fun getAlternativeProvider(): String? {
        val availableProviders = AIAgentRegistry.getAvailableProviders()
        return availableProviders.firstOrNull { it != currentProviderId }
    }

    private suspend fun processModifications(
        response: String,
        previousFileStates: Map<String, String>,
        callback: AIAgentCallback
    ): List<BaseFileModification> {
        val modifications = mutableListOf<BaseFileModification>()
        legacyModificationParser.parse(response).forEach { call ->
            val fileName = File(call.path).name
            callback.onFileModifying(call.path, fileName)

            val writeResult = executeWriteTool(call)
            val success = writeResult is FileWriteResult.Success
            currentAgent?.recordModification(
                call.path,
                previousFileStates[call.path],
                call.content,
                success
            )
            callback.onFileModified(call.path, fileName, success)
            delay(300)

            modifications.add(BaseFileModification(call.path, call.content, writeResult))
        }

        return modifications
    }

    private suspend fun executeWriteTool(call: ToolCall.WriteFile): FileWriteResult {
        return when (val result = workspaceToolExecutor.execute(call)) {
            is ToolResult.Success -> FileWriteResult.Success(call.path, backupCreated = false)
            is ToolResult.Rejected -> FileWriteResult.PermissionDenied(result.reason)
            is ToolResult.Failure -> FileWriteResult.Error(result.reason)
        }
    }

    private suspend fun confirmFileWrite(filePath: String): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                try {
                    AIPermissionDialog(context).showFileWriteConfirmation(
                        fileName = filePath,
                        onConfirm = {
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onDeny = {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    )
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    }

    private fun formatErrorMessage(error: Throwable): String {
        val errorMessage = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString().take(500)
        val providerName = currentAgent?.providerName ?: "Unknown"
        
        return when (error) {
            is com.tom.rv2ide.artificial.exceptions.RateLimitException -> 
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait a few minutes before trying again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.QuotaExceededException -> 
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException -> 
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\nPlease add credits or upgrade your plan.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException -> 
                "❌ INVALID API KEY\n\nThe API key is invalid or expired.\nPlease update your API key in the configuration.\n\nDetails: $errorMessage"
            is java.net.UnknownHostException ->
                "🌐 NETWORK ERROR\n\nCould not connect to the API server.\nPlease check your internet connection.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\nPlease try again.\n\nDetails: $errorMessage"
            is org.json.JSONException ->
                "📄 JSON PARSING ERROR\n\nFailed to parse API response.\nThe API may be experiencing issues.\n\nDetails: $errorMessage"
            else -> 
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage\n\nStack Trace (first 500 chars):\n$stackTrace"
        }
    }

    fun showProviderErrorDialogFromFragment(
        activity: android.app.Activity,
        errorMessage: String,
        onProviderSelected: (String) -> Unit
    ) {
        val currentProviderName = currentAgent?.providerName ?: "Unknown"
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }
        
        providerSwitchDialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(context)
                val availableModels = agents.getModelsForProvider(providerId)
                if (availableModels.isNotEmpty()) {
                    agents.setAgent(availableModels[0])
                }
                reinitializeWithSelectedModel()
                onProviderSelected(providerId)
            },
            onEnableAutoSwitch = {
                val alternativeProvider = getAlternativeProvider()
                if (alternativeProvider != null) {
                    setProvider(alternativeProvider)
                    val agents = Agents(context)
                    val availableModels = agents.getModelsForProvider(alternativeProvider)
                    if (availableModels.isNotEmpty()) {
                        agents.setAgent(availableModels[0])
                    }
                    reinitializeWithSelectedModel()
                }
            }
        )
    }
    
    fun isAutoSwitchEnabled(): Boolean {
        return providerSwitchDialog.isAutoSwitchEnabled()
    }
    
    fun setAutoSwitch(enabled: Boolean) {
        providerSwitchDialog.setAutoSwitch(enabled)
    }

    private fun createSummary(results: List<ModificationResult>): ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) FileStatus.SUCCESS else FileStatus.FAILED,
                changeType = if (result.isNewFile) ChangeType.CREATED else ChangeType.MODIFIED
            )
        }

        return ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    private fun captureCurrentFileStates(): Map<String, String> {
        val states = mutableMapOf<String, String>()
        val projectRoot = currentProjectRoot ?: return states

        if (!projectRoot.exists()) return states

        projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter {
                it.extension in listOf("kt", "java", "xml", "gradle", "kts") &&
                !it.path.contains("/build/") &&
                !it.path.contains("/.gradle/")
            }
            .forEach { file ->
                try {
                    states[file.absolutePath] = file.readText()
                } catch (e: Exception) {
                }
            }

        return states
    }

    fun undoLastModification(): Boolean {
        return currentAgent?.undoLastModification() ?: false
    }

    fun reinitializeWithSelectedModel() {
        val factory = AIAgentRegistry.getFactory(currentProviderId)
        factory?.getApiKey()?.let { apiKey ->
            currentAgent?.reinitializeWithNewModel(apiKey, context)
        }
    }

    fun getCurrentModelName(): String {
        val agents = Agents(context)
        return agents.getAgent()
    }
    
    fun getConversationHistory(): List<UnifiedModificationAttempt> {
        return currentAgent?.getModificationHistory()?.map {
            UnifiedModificationAttempt(
                timestamp = it.timestamp,
                filePath = it.filePath,
                previousContent = it.previousContent,
                newContent = it.newContent,
                attemptNumber = it.attemptNumber,
                success = it.success
            )
        } ?: emptyList()
    }

    interface AIAgentCallback {
        fun onProcessing(message: String)
        fun onFileModifying(filePath: String, fileName: String)
        fun onFileModified(filePath: String, fileName: String, success: Boolean)
        fun onSuccess(response: String, modifications: List<ModificationResult>, summary: ModificationSummary)
        fun onTextResponse(response: String, summary: ModificationSummary)
        fun onError(message: String)
        fun onRetry(attemptNumber: Int, message: String)
    }

    data class ModificationResult(
        val filePath: String,
        val content: String,
        val success: Boolean,
        val message: String,
        val isNewFile: Boolean = false
    )

    data class ModificationSummary(
        val totalFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val newFiles: Int,
        val modifiedFiles: Int,
        val fileDetails: List<FileDetail>
    )

    data class FileDetail(
        val fileName: String,
        val filePath: String,
        val status: FileStatus,
        val changeType: ChangeType
    )

    enum class FileStatus { SUCCESS, FAILED }
    enum class ChangeType { CREATED, MODIFIED }
    
    data class ProviderInfo(
        val id: String,
        val name: String,
        val isAvailable: Boolean
    )
}

data class BaseFileModification(
    val filePath: String,
    val content: String,
    val writeResult: FileWriteResult
)

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)