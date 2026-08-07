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

package com.tom.rv2ide.artificial.completion

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.setup.updater.LspUpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AICodeCompletionService(
    private val context: Context,
    private val aiAgent: AIAgent
) {
    
    private val completionCache = mutableMapOf<String, CompletionResult>()
    
    data class CompletionResult(
        val text: String,
        val isMultiLine: Boolean,
        val lineCount: Int
    )
    
    suspend fun getSuggestion(
        currentCode: String,
        cursorPosition: Int,
        language: String = "kotlin"
    ): CompletionResult? = withContext(Dispatchers.IO) {
        if (!aiAgent.isInitialized()) {
            android.util.Log.d("AICodeCompletionService", "Agent not initialized")
            return@withContext null
        }
        
        try {
            val beforeCursor = currentCode.substring(0, cursorPosition.coerceAtMost(currentCode.length))
            val afterCursor = currentCode.substring(cursorPosition.coerceAtMost(currentCode.length))
            val lastLines = beforeCursor.lines().takeLast(20).joinToString("\n")
            
            val cacheKey = lastLines.takeLast(150)
            if (completionCache.containsKey(cacheKey)) {
                android.util.Log.d("AICodeCompletionService", "Returning cached suggestion")
                return@withContext completionCache[cacheKey]
            }
            
            val prompt = buildCompletionPrompt(lastLines, afterCursor.lines().take(5).joinToString("\n"), language)
            
            android.util.Log.d("AICodeCompletionService", "Sending prompt to AI")
            
            val result = aiAgent.generateCode(
                prompt = prompt,
                context = null,
                language = language,
                projectStructure = null
            )
            
            result.fold(
                onSuccess = { response ->
                    val completionResult = extractSuggestion(response)
                    if (completionResult != null && completionResult.text.isNotBlank()) {
                        completionCache[cacheKey] = completionResult
                        android.util.Log.d("AICodeCompletionService", "Extracted a code suggestion")
                        return@withContext completionResult
                    } else {
                        android.util.Log.d("AICodeCompletionService", "Failed to extract suggestion")
                    }
                    null
                },
                onFailure = { e ->
                    android.util.Log.e("AICodeCompletionService", "AI request failed", e)
                    handleException(e)
                    null
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("AICodeCompletionService", "Exception in getSuggestion", e)
            handleException(e)
            null
        }
    }
    
    private suspend fun handleException(e: Throwable) {
        withContext(Dispatchers.Main) {
            when (e) {
                is QuotaExceededException -> {
                    android.util.Log.w("AICodeCompletionService", "Quota exceeded, will retry on next request")
                }
                is RateLimitException -> {
                    android.util.Log.w("AICodeCompletionService", "Rate limit reached, will retry on next request")
                }
                is InsufficientBalanceException -> {
                    android.util.Log.w("AICodeCompletionService", "Insufficient balance")
                }
                is InvalidApiKeyException -> {
                    android.util.Log.w("AICodeCompletionService", "Invalid API key")
                }
            }
        }
    }
    
    private fun buildCompletionPrompt(contextBefore: String, contextAfter: String, language: String): String {
        return """You are an intelligent code completion AI for $language. Complete the code at cursor position.

Context before cursor:
$contextBefore|

Context after cursor:
$contextAfter

Instructions:
- Analyze the code context and patterns
- Output ONLY the completion text (what comes after |)
- NO explanations, NO markdown, NO backticks
- Match the coding style and patterns in the context
- Keep completions concise (under 150 characters)

Completion:""".trimIndent()
    }

    private fun extractSuggestion(response: String): CompletionResult? {
        var cleaned = response.trim()

        cleaned = cleaned.replace(Regex("```[\\w]*\\n?"), "")
        cleaned = cleaned.replace("```", "")
        cleaned = cleaned.removePrefix("Completion:")
        cleaned = cleaned.removePrefix("Output:")
        cleaned = cleaned.removePrefix("Next:")
        cleaned = cleaned.trim()
        
        val lines = cleaned.lines().filter { line -> 
            val l = line.trim()
            l.isNotBlank() && 
            !l.startsWith("//") && 
            !l.startsWith("/*") &&
            !l.startsWith("*") &&
            !l.contains("ASSISTANT", ignoreCase = true) &&
            !l.startsWith("You", ignoreCase = true) &&
            !l.startsWith("The", ignoreCase = true) &&
            !l.startsWith("Given", ignoreCase = true) &&
            !l.startsWith("Instructions", ignoreCase = true) &&
            !l.startsWith("Context", ignoreCase = true) &&
            !l.startsWith("Code:", ignoreCase = true) &&
            !l.startsWith("Complete", ignoreCase = true)
        }
        
        if (lines.isEmpty()) return null
        
        val suggestionText = lines.joinToString("\n")
        
        if (suggestionText.isBlank() || suggestionText.length > 200) {
            android.util.Log.d("AICodeCompletionService", "Suggestion invalid: blank=${suggestionText.isBlank()}, length=${suggestionText.length}")
            return null
        }
        
        val isMultiLine = lines.size > 1
        
        android.util.Log.d("AICodeCompletionService", "Prepared ${lines.size}-line suggestion")
        
        return CompletionResult(
            text = suggestionText,
            isMultiLine = isMultiLine,
            lineCount = lines.size
        )
    }
    
    fun clearCache() {
        completionCache.clear()
    }
    
    fun isEnabled(): Boolean = true
}