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
package com.tom.rv2ide.artificial.secrets

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.preferences.internal.prefManager

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

object ApiKey {

    private val encryptedPreferences: SharedPreferences by lazy {
        val context = BaseApplication.getBaseInstance()
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_agent_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Check if AI Agent is enabled
    fun isAIAgentEnabled(): Boolean {
        return prefManager.getBoolean("ai_agent_enabled", false)
    }
    
    // Gemini API Key
    fun getGeminiApiKey(): String {
        return getSecret("ai_agent_gemini_api_key")
    }

    fun setGeminiApiKey(value: String) = setSecret("ai_agent_gemini_api_key", value)
    
    fun hasGeminiKey(): Boolean {
        val key = getGeminiApiKey()
        return key.isNotBlank() && key.length > 20
    }
    
    // OpenAI API Key
    fun getOpenAIApiKey(): String {
        return getSecret("ai_agent_openai_api_key")
    }

    fun setOpenAIApiKey(value: String) = setSecret("ai_agent_openai_api_key", value)
    
    fun hasOpenAIKey(): Boolean {
        val key = getOpenAIApiKey()
        return key.isNotBlank() && key.length > 20
    }
    
    // Deepseek API Key
    fun getDeepseekApiKey(): String {
        return getSecret("ai_agent_deepseek_api_key")
    }

    fun setDeepseekApiKey(value: String) = setSecret("ai_agent_deepseek_api_key", value)
    
    fun hasDeepseekKey(): Boolean {
        val key = getDeepseekApiKey()
        return key.isNotBlank() && key.length > 20
    }
    
    // Anthropic API Key
    fun getAnthropicApiKey(): String {
        return getSecret("ai_agent_anthropic_api_key")
    }

    fun setAnthropicApiKey(value: String) = setSecret("ai_agent_anthropic_api_key", value)
    
    fun hasAnthropicKey(): Boolean {
        val key = getAnthropicApiKey()
        return key.isNotBlank() && key.length > 20
    }
    
    // Grok API Key
    fun getGrokApiKey(): String {
        return getSecret("ai_agent_grok_api_key")
    }

    fun setGrokApiKey(value: String) = setSecret("ai_agent_grok_api_key", value)
    
    fun hasGrokKey(): Boolean {
        val key = getGrokApiKey()
        return key.isNotBlank() && key.length > 20
    }
    
    // Legacy methods for backward compatibility
    @Deprecated("Use getGeminiApiKey() instead", ReplaceWith("getGeminiApiKey()"))
    fun getApiKey(): String {
        return getGeminiApiKey()
    }
    
    // Get list of available providers
    fun getAvailableProviders(): List<String> {
        val providers = mutableListOf<String>()
        if (hasGeminiKey()) providers.add("Gemini")
        if (hasOpenAIKey()) providers.add("OpenAI")
        if (hasDeepseekKey()) providers.add("Deepseek")
        if (hasAnthropicKey()) providers.add("Anthropic")
        if (hasGrokKey()) providers.add("Grok")
        return providers
    }
    
    // Get all API keys as a map
    fun getAllApiKeys(): Map<String, String> {
        return mapOf(
            "gemini" to getGeminiApiKey(),
            "openai" to getOpenAIApiKey(),
            "deepseek" to getDeepseekApiKey(),
            "anthropic" to getAnthropicApiKey(),
            "grok" to getGrokApiKey()
        ).filterValues { it.isNotBlank() }
    }
    
    // Check if any API key is configured
    fun hasAnyApiKey(): Boolean {
        return hasGeminiKey() || hasOpenAIKey() || hasDeepseekKey() || 
               hasAnthropicKey() || hasGrokKey()
    }

    private fun getSecret(key: String): String {
        encryptedPreferences.getString(key, null)?.let { return it }

        // One-time migration from the legacy default SharedPreferences storage.
        val legacyValue = prefManager.getString(key, "")
        if (legacyValue.isNotBlank()) {
            encryptedPreferences.edit().putString(key, legacyValue).apply()
            prefManager.remove(key)
        }
        return legacyValue
    }

    private fun setSecret(key: String, value: String) {
        encryptedPreferences.edit().apply {
            if (value.isBlank()) remove(key) else putString(key, value)
        }.apply()
        // Ensure an old plaintext value cannot shadow the encrypted store.
        prefManager.remove(key)
    }
}