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

package com.tom.rv2ide.artificial.agents.openai

import android.content.Context
import com.tom.rv2ide.artificial.services.ArtificialService
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.tom.rv2ide.artificial.agents.Agents
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import com.tom.rv2ide.artificial.exceptions.*
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AgentEvent
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolCallParser
import com.tom.rv2ide.artificial.tools.ToolDefinition
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.artificial.tools.ToolSchemaJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class OpenAI : AIAgent {

  private var apiKey: String? = null
  private val writingRules = WritingRules.Instructions()
  private var projectTreeResult: ProjectTreeResult? = null
  private var fileWriter: AIFileWriter? = null
  private val conversationHistory = mutableListOf<ConversationMessage>()
  private val modificationHistory = mutableListOf<ModificationAttempt>()
  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3
  private var agents: Agents? = null
  private var selectedModel: String = "gpt-4o"
  override val providerId = "openai"
  override val providerName = "OpenAI"

  companion object {
      fun registerAgent() {
          AIAgentRegistry.register("openai", object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent {
                  return OpenAI()
              }
              
              override fun hasValidApiKey(): Boolean {
                  val key = ApiKey.getOpenAIApiKey()
                  android.util.Log.d("OpenAI", "hasValidApiKey check: ${key != null && key.isNotEmpty()}, key length: ${key?.length ?: 0}")
                  return key != null && key.isNotEmpty()
              }
              
              override fun getApiKey(): String? {
                  val key = ApiKey.getOpenAIApiKey()
                  android.util.Log.d("OpenAI", "getApiKey called, returning key of length: ${key?.length ?: 0}")
                  return key
              }
          })
      }
  }
        
  override fun initialize(apiKey: String, context: Context) {
      try {
          this.apiKey = apiKey
          agents = Agents(context)
          var selectedModel = agents?.getAgent() ?: "gpt-4o"
          
          // Ensure we're using a valid OpenAI model
          if (!agents!!.isValidModelForProvider(selectedModel, "openai")) {
              selectedModel = "gpt-4o"
              agents?.setAgent(selectedModel)
              agents?.setProvider("openai")
          }
          
          this.selectedModel = selectedModel
      } catch (e: Exception) {
          throw e
      }
  }

  override fun reinitializeWithNewModel(apiKey: String, context: Context) {
    initialize(apiKey, context)
  }

  override fun setContext(context: Context) {
    fileWriter = AIFileWriter(context)
  }

  override fun setProjectData(projectTreeResult: ProjectTreeResult) {
    this.projectTreeResult = projectTreeResult
  }

  override fun clearConversation() {
    conversationHistory.clear()
    modificationHistory.clear()
    currentAttemptCount = 0
  }

  override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
    modificationHistory.add(
        ModificationAttempt(
            timestamp = System.currentTimeMillis(),
            filePath = filePath,
            previousContent = oldContent,
            newContent = newContent,
            attemptNumber = currentAttemptCount,
            success = success
        )
    )
  }

  override fun undoLastModification(): Boolean {
    if (modificationHistory.isEmpty()) return false
    
    val lastMod = modificationHistory.lastOrNull { it.success } ?: return false
    
    if (lastMod.previousContent != null) {
      val result = writeFile(lastMod.filePath, lastMod.previousContent)
      if (result is FileWriteResult.Success) {
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        return true
      }
    } else {
      try {
        File(lastMod.filePath).delete()
        modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
        return true
      } catch (e: Exception) {
        return false
      }
    }
    return false
  }

  override fun getModificationHistory(): List<ModificationAttempt> {
    return modificationHistory.toList()
  }

  override fun resetAttemptCount() {
    currentAttemptCount = 0
  }

  override fun incrementAttemptCount() {
    currentAttemptCount++
  }

  override fun getCurrentAttemptCount(): Int = currentAttemptCount

  override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

  private fun isUserRequestingCorrection(message: String): Boolean {
    val correctionKeywords = listOf(
        "wrong", "not what", "mistake", "error", "incorrect", 
        "that's not", "not right", "fix", "undo", "revert",
        "different", "try again", "not working"
    )
    return correctionKeywords.any { message.lowercase().contains(it) }
  }

  override suspend fun generateCode(
      prompt: String,
      context: String?,
      language: String,
      projectStructure: String?,
  ): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          val key = apiKey
              ?: return@withContext Result.failure(
                  IllegalStateException("OpenAI service not initialized")
              )

          val fileContents = readRelevantFiles()
          val needsCorrection = isUserRequestingCorrection(prompt)

          val fullPrompt = buildString {
            append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
            if (projectTreeResult != null) {
              append(projectTreeResult!!.tree)
              append("\n\n")
              append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like '/storage/emulated/0/project' or 'com.example.yourproject'.\n")
              append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
            }
            
            if (fileContents.isNotEmpty()) {
              append("=== CURRENT FILES CONTENT ===\n")
              fileContents.forEach { (path, content) ->
                append("FILE: $path\n")
                append("CONTENT:\n")
                append(content)
                append("\n\n")
              }
            }
            
            if (context != null) {
              append("=== ADDITIONAL CONTEXT ===\n")
              append(context)
              append("\n\n")
            }
            
            if (conversationHistory.isNotEmpty()) {
              append("=== CONVERSATION HISTORY ===\n")
              conversationHistory.forEach { msg ->
                append("${msg.role.uppercase()}: ${msg.content}\n\n")
              }
            }

            if (needsCorrection && modificationHistory.isNotEmpty()) {
              append("=== CORRECTION REQUIRED ===\n")
              append("The user indicated the previous modification was WRONG.\n")
              append("Previous failed attempts:\n")
              modificationHistory.takeLast(3).forEach { attempt ->
                append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
                append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
              }
              append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n")
              append("Analyze what went wrong and provide a better solution.\n\n")
            }

            if (currentAttemptCount > 0) {
              append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
              append("This is retry attempt number $currentAttemptCount.\n")
              append("Previous attempts did not satisfy the user.\n")
              append("Think carefully and provide a different solution.\n\n")
            }
            
            append("=== USER REQUEST ===\n")
            append(prompt)
          }

          val response = callOpenAIAPI(key, fullPrompt)

          if (response.isBlank()) {
            return@withContext Result.failure(Exception("Empty response from AI"))
          }

          conversationHistory.add(ConversationMessage("user", prompt))
          conversationHistory.add(ConversationMessage("assistant", response))

          if (conversationHistory.size > 20) {
            conversationHistory.removeAt(0)
            conversationHistory.removeAt(0)
          }

          Result.success(response)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  private fun callOpenAIAPI(apiKey: String, prompt: String): String {
    android.util.Log.d("OpenAI", "Starting API call to OpenAI")
    
    val url = URL("https://api.openai.com/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    
    try {
      connection.requestMethod = "POST"
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      connection.doOutput = true
      connection.connectTimeout = 30000
      connection.readTimeout = 30000
      
      val messages = JSONArray()
      
      val systemMessage = JSONObject()
      systemMessage.put("role", "system")
      systemMessage.put("content", writingRules.useThis())
      messages.put(systemMessage)
      
      val userMessage = JSONObject()
      userMessage.put("role", "user")
      userMessage.put("content", prompt)
      messages.put(userMessage)
      
      val requestBody = JSONObject()
      requestBody.put("model", selectedModel)
      requestBody.put("messages", messages)
      requestBody.put("temperature", 0.7)
      requestBody.put("max_tokens", 4096)
      
      connection.outputStream.use { os ->
        os.write(requestBody.toString().toByteArray())
      }
      
      val responseCode = connection.responseCode
      android.util.Log.d("OpenAI", "Response code: $responseCode")
      
      if (responseCode != HttpURLConnection.HTTP_OK) {
        val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        android.util.Log.e("OpenAI", "Error response: $errorStream")
        
        // Parse error response
        try {
          val errorJson = JSONObject(errorStream)
          val errorObj = errorJson.optJSONObject("error")
          val errorMessage = errorObj?.optString("message") ?: errorStream
          val errorType = errorObj?.optString("type") ?: ""
          val errorCode = errorObj?.optString("code") ?: ""
          
          android.util.Log.e("OpenAI", "Error type: $errorType, code: $errorCode, message: $errorMessage")
          
          // Identify specific error types
          when {
            responseCode == 429 || errorType.contains("rate_limit") || errorCode.contains("rate_limit") -> 
              throw com.tom.rv2ide.artificial.exceptions.RateLimitException("OpenAI rate limit exceeded: $errorMessage")
            errorType.contains("insufficient_quota") || errorMessage.contains("quota") || errorMessage.contains("billing") -> 
              throw com.tom.rv2ide.artificial.exceptions.QuotaExceededException("OpenAI quota exceeded: $errorMessage")
            errorType.contains("invalid_api_key") || errorCode.contains("invalid_api_key") -> 
              throw com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException("Invalid OpenAI API key: $errorMessage")
            responseCode == 401 -> 
              throw com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException("OpenAI authentication failed: $errorMessage")
            else -> 
              throw Exception("OpenAI API error ($responseCode) - Type: $errorType, Code: $errorCode, Message: $errorMessage")
          }
        } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
          throw e
        } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
          throw e
        } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
          throw e
        } catch (e: Exception) {
          throw Exception("OpenAI API error ($responseCode): $errorStream")
        }
      }
      
      val responseBody = connection.inputStream.bufferedReader().readText()
      android.util.Log.d("OpenAI", "Success response received, length: ${responseBody.length}")
      
      val jsonResponse = JSONObject(responseBody)
      
      val choices = jsonResponse.getJSONArray("choices")
      if (choices.length() > 0) {
        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.getJSONObject("message")
        return message.getString("content")
      }
      
      throw Exception("No response from OpenAI API")
    } catch (e: com.tom.rv2ide.artificial.exceptions.RateLimitException) {
      android.util.Log.e("OpenAI", "Rate limit exception", e)
      throw e
    } catch (e: com.tom.rv2ide.artificial.exceptions.QuotaExceededException) {
      android.util.Log.e("OpenAI", "Quota exceeded exception", e)
      throw e
    } catch (e: com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException) {
      android.util.Log.e("OpenAI", "Invalid API key exception", e)
      throw e
    } catch (e: java.net.SocketTimeoutException) {
      android.util.Log.e("OpenAI", "Timeout exception", e)
      throw Exception("OpenAI request timeout: ${e.message}")
    } catch (e: java.net.UnknownHostException) {
      android.util.Log.e("OpenAI", "Network exception", e)
      throw Exception("Network error - cannot reach OpenAI: ${e.message}")
    } catch (e: Exception) {
      android.util.Log.e("OpenAI", "General exception", e)
      throw e
    } finally {
      connection.disconnect()
    }
  }

  private fun readRelevantFiles(): Map<String, String> {
    return projectTreeResult?.readRelevantFiles() ?: emptyMap()
  }

  fun readFile(filePath: String): String? {
    return try {
      if (File(filePath).exists()) {
        File(filePath).readText()
      } else {
        projectTreeResult?.readFileContent(File(filePath).name)
      }
    } catch (e: Exception) {
      null
    }
  }

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }

  fun parseAndApplyModifications(response: String, capturedStates: Map<String, String>): List<FileModification> {
    val modifications = mutableListOf<FileModification>()
    val parser = com.tom.rv2ide.artificial.parser.SnippetParser()
    
    if (response.contains("FILE_TO_MODIFY:")) {
      val lines = response.lines()
      var currentFile: String? = null
      val contentBuilder = StringBuilder()
      var inContent = false
      
      for (line in lines) {
        if (line.startsWith("FILE_TO_MODIFY:")) {
          if (currentFile != null && contentBuilder.isNotEmpty()) {
            val rawContent = contentBuilder.toString().trim()
            val cleanedContent = parser.cleanFileContent(rawContent)
            val previousContent = capturedStates[currentFile]
            val writeResult = writeFile(currentFile, cleanedContent)
            
            val success = writeResult is FileWriteResult.Success
            recordModification(currentFile, previousContent, cleanedContent, success)
            
            modifications.add(FileModification(currentFile, cleanedContent, writeResult))
          }
          
          currentFile = line.substringAfter("FILE_TO_MODIFY:").trim()
          contentBuilder.clear()
          inContent = true
        } else if (inContent) {
          contentBuilder.append(line).append("\n")
        }
      }
      
      if (currentFile != null && contentBuilder.isNotEmpty()) {
        val rawContent = contentBuilder.toString().trim()
        val cleanedContent = parser.cleanFileContent(rawContent)
        val previousContent = capturedStates[currentFile]
        val writeResult = writeFile(currentFile, cleanedContent)
        
        val success = writeResult is FileWriteResult.Success
        recordModification(currentFile, previousContent, cleanedContent, success)
        
        modifications.add(FileModification(currentFile, cleanedContent, writeResult))
      }
    }
    
    return modifications
  }

  override fun isInitialized(): Boolean = apiKey != null

  /**
   * ReAct 流式 Agent。覆盖 AIAgent 的默认实现,使用 OpenAI 原生 `tool_calls` 字段,
   * 而不是依赖脆弱的 `"FILE_TO_MODIFY:"` 字符串协议。
   *
   * 设计参考 ACSIDE 反编译样本(`afad2351...`)中的 `OpenAiCompatibleMcpAgent.chat`
   * `Flow<AgentEvent>` 形态与 `WorkspaceTools` 工具调度。仅复用 ReAct 循环与事件流
   * 的架构思想,不复制反编译源码。
   */
  override suspend fun chat(
      prompt: String,
      tools: List<ToolDefinition>,
      onToolCall: suspend (ToolCall) -> ToolResult,
      maxIterations: Int,
  ): Flow<AgentEvent> = flow {
    val key = apiKey
        ?: run {
          emit(AgentEvent.Error("OpenAI service not initialized"))
          return@flow
        }

    val messages = JSONArray()
    messages.put(JSONObject().apply {
      put("role", "system")
      put("content", buildSystemPrompt())
    })

    // 复用现有 conversationHistory,保持多轮对话上下文。
    conversationHistory.forEach { msg ->
      messages.put(JSONObject().apply {
        put("role", msg.role)
        put("content", msg.content)
      })
    }

    messages.put(JSONObject().apply {
      put("role", "user")
      put("content", prompt)
    })

    val toolsJson = ToolSchemaJson.toArray(tools)
    val hasTools = tools.isNotEmpty()
    var iteration = 0

    while (iteration < maxIterations) {
      iteration++

      val responsePair = withContext(Dispatchers.IO) {
        runCatching { callOpenAIChatAPI(key, messages, toolsJson, hasTools) }
      }
      val response = responsePair.getOrNull()
      if (response == null) {
        emit(AgentEvent.Error("OpenAI API call failed: ${responsePair.exceptionOrNull()?.message ?: "unknown"}", responsePair.exceptionOrNull()))
        return@flow
      }

      val choices = response.optJSONArray("choices")
      if (choices == null || choices.length() == 0) {
        emit(AgentEvent.Error("OpenAI returned no choices"))
        return@flow
      }
      val message = choices.getJSONObject(0).getJSONObject("message")

      // 提取 reasoning_content(DeepSeek R1 / Claude thinking / GLM-4.5 等推理模型)。
      val reasoning = message.optString("reasoning_content", "")
      if (reasoning.isNotBlank()) emit(AgentEvent.Thinking(reasoning))

      val textContent = message.optString("content", "")
      if (textContent.isNotBlank()) emit(AgentEvent.TextDelta(textContent))

      // 把 assistant 消息追加到 messages 用于下一轮(保留 tool_calls 字段)。
      messages.put(message)

      val toolCalls = message.optJSONArray("tool_calls")
      if (toolCalls == null || toolCalls.length() == 0) {
        // 没有工具调用,这是最终响应。
        val usage = response.optJSONObject("usage")
        emit(
            AgentEvent.FinalResponse(
                text = textContent,
                totalTokens = usage?.optInt("total_tokens", 0) ?: 0,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                candidateTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            ),
        )
        // 同步到旧 conversationHistory,保持向后兼容。
        conversationHistory.add(ConversationMessage("user", prompt))
        conversationHistory.add(ConversationMessage("assistant", textContent))
        if (conversationHistory.size > 20) {
          conversationHistory.removeAt(0)
          conversationHistory.removeAt(0)
        }
        return@flow
      }

      // 解析并执行每个工具调用,把结果回填到 messages。
      for (i in 0 until toolCalls.length()) {
        val tc = toolCalls.getJSONObject(i)
        val tcId = tc.getString("id")
        if (tc.optString("type", "function") != "function") continue
        val function = tc.getJSONObject("function")
        val toolName = function.getString("name")
        val argsJson = function.optString("arguments", "{}")

        emit(AgentEvent.ToolCall(id = tcId, name = toolName, argumentsJson = argsJson))

        val parseResult = ToolCallParser.parse(tcId, toolName, argsJson)
        val toolResult = if (parseResult.isSuccess) {
          val call = parseResult.getOrThrow()
          try {
            onToolCall(call)
          } catch (e: Exception) {
            ToolResult.Failure(tcId, "Tool execution error: ${e.message}")
          }
        } else {
          ToolResult.Failure(tcId, "Tool parse error: ${parseResult.exceptionOrNull()?.message}")
        }

        val resultText = when (toolResult) {
          is ToolResult.Success -> toolResult.output
          is ToolResult.Rejected -> "REJECTED: ${toolResult.reason}"
          is ToolResult.Failure -> "FAILURE: ${toolResult.reason}"
        }
        emit(
            AgentEvent.ToolResult(
                callId = tcId,
                toolName = toolName,
                output = resultText,
                successful = toolResult is ToolResult.Success,
            ),
        )

        messages.put(JSONObject().apply {
          put("role", "tool")
          put("tool_call_id", tcId)
          put("content", resultText)
        })

        // 记录写操作到 modificationHistory,保持与旧 generateCode 路径一致的撤销能力。
        if (toolResult is ToolResult.Success && parseResult.isSuccess) {
          val call = parseResult.getOrThrow()
          if (call is ToolCall.WriteFile) {
            // previousContent 此处无法精确捕获,因为 WorkspaceToolExecutor 内部已落地。
            // 传 null 仍可让撤销链工作(空 → 删除)。
            recordModification(call.path, null, call.content, success = true)
          }
        }
      }
      // 循环回到 while 头部,把 messages 发给模型继续。
    }

    emit(AgentEvent.Error("Reached max iterations ($maxIterations) without final response"))
  }

  private fun buildSystemPrompt(): String = buildString {
    append(writingRules.useThis())
    append("\n\n")
    append("=== PROJECT STRUCTURE (use ONLY these paths) ===\n")
    if (projectTreeResult != null) {
      append(projectTreeResult!!.tree)
      append("\nCRITICAL: Do NOT fabricate paths like '/storage/emulated/0/project'.\n")
    } else {
      append("(no project loaded)\n")
    }
  }

  /**
   * OpenAI Chat Completions 调用,支持可选的 `tools` 字段。返回完整 JSON 响应。
   * 抛出与 [callOpenAIAPI] 相同的异常类型,供 [chat] 在调用方包装为 AgentEvent.Error。
   */
  private fun callOpenAIChatAPI(
      apiKey: String,
      messages: JSONArray,
      toolsJson: JSONArray,
      hasTools: Boolean,
  ): JSONObject {
    val url = URL("https://api.openai.com/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      connection.doOutput = true
      connection.connectTimeout = 30000
      connection.readTimeout = 120000

      val requestBody = JSONObject().apply {
        put("model", selectedModel)
        put("messages", messages)
        put("temperature", 0.7)
        put("max_tokens", 4096)
        if (hasTools) {
          put("tools", toolsJson)
          put("tool_choice", "auto")
        }
      }

      connection.outputStream.use { os ->
        os.write(requestBody.toString().toByteArray())
      }

      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK) {
        val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
        throw classifyError(responseCode, errorStream)
      }

      val responseBody = connection.inputStream.bufferedReader().readText()
      return JSONObject(responseBody)
    } finally {
      connection.disconnect()
    }
  }

  /** 把 HTTP 错误响应分类成项目自定义异常,沿用 [callOpenAIAPI] 的分类规则。 */
  private fun classifyError(responseCode: Int, errorStream: String): Exception {
    return try {
      val errorJson = JSONObject(errorStream)
      val errorObj = errorJson.optJSONObject("error")
      val errorMessage = errorObj?.optString("message") ?: errorStream
      val errorType = errorObj?.optString("type") ?: ""
      val errorCode = errorObj?.optString("code") ?: ""
      when {
        responseCode == 429 || errorType.contains("rate_limit") || errorCode.contains("rate_limit") ->
          RateLimitException("OpenAI rate limit exceeded: $errorMessage")
        errorType.contains("insufficient_quota") || errorMessage.contains("quota") || errorMessage.contains("billing") ->
          QuotaExceededException("OpenAI quota exceeded: $errorMessage")
        errorType.contains("invalid_api_key") || errorCode.contains("invalid_api_key") ->
          InvalidApiKeyException("Invalid OpenAI API key: $errorMessage")
        responseCode == 401 ->
          InvalidApiKeyException("OpenAI authentication failed: $errorMessage")
        else -> Exception("OpenAI API error ($responseCode) - Type: $errorType, Code: $errorCode, Message: $errorMessage")
      }
    } catch (e: RateLimitException) { e }
    catch (e: QuotaExceededException) { e }
    catch (e: InvalidApiKeyException) { e }
    catch (e: Exception) {
      Exception("OpenAI API error ($responseCode): $errorStream")
    }
  }
}

data class FileModification(
    val filePath: String,
    val content: String,
    val writeResult: FileWriteResult
)

data class ConversationMessage(
    val role: String,
    val content: String
)
