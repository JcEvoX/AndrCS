package com.tom.rv2ide.fragments.sidebar

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.dialogs.LocalLLMConfigDialog
import com.tom.rv2ide.managers.CodeCompletionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AIPreferencesFragment(
    private val aiAgent: AIAgentManager,
    private val agents: Agents,
    private val codeCompletionManager: CodeCompletionManager?
) : Fragment() {

    private lateinit var configureAgentBtn: MaterialButton
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView

    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupToggles()
        updateCurrentStatus()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentStatus()
        syncCodeCompletionToggle()
    }

    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        configureAgentBtn = view.findViewById(R.id.configureAgentBtn)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)

        configureAgentBtn.setOnClickListener { showAgentConfigDialog() }
    }

    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()

        val providerDisplayName = when (currentProvider) {
            "gemini" -> "Google Gemini"
            "openai" -> "OpenAI"
            "claude" -> "Anthropic Claude"
            "deepseek" -> "DeepSeek"
            "grok" -> "xAI Grok"
            "localllm" -> "Local LLM"
            else -> currentProvider.uppercase()
        }

        currentProviderText.text = providerDisplayName
        currentModelText.text = currentModel
    }

    private fun showAgentConfigDialog() {
        val providerMap = linkedMapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "localllm" to "Local LLM"
        )
        val allProviderIds = providerMap.keys.toList()
        val currentProviderId = agents.getProvider()
        val currentModel = agents.getAgent()

        // ── Step 1: pick provider ──
        val providerItems = providerMap.map { (id, name) ->
            val marker = if (id == currentProviderId) "  ✓" else ""
            "$name$marker"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Provider")
            .setItems(providerItems) { _, which ->
                val selectedProviderId = allProviderIds[which]
                if (selectedProviderId == "localllm") {
                    showLocalLLMConfigDialog("Local LLM")
                } else {
                    showModelAndKeyDialog(selectedProviderId, providerMap[selectedProviderId] ?: selectedProviderId, currentModel)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModelAndKeyDialog(providerId: String, providerDisplayName: String, currentModel: String) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        // Model dropdown
        val models = agents.getModelsForProvider(providerId).toList()
        val modelItems = if (models.isNotEmpty()) models.toTypedArray() else arrayOf(currentModel)

        val modelLayout = TextInputLayout(requireContext()).apply {
            hint = "Model"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        val modelDropdown = AutoCompleteTextView(requireContext()).apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modelItems))
            setText(if (currentModel in models) currentModel else modelItems.firstOrNull() ?: currentModel, false)
            inputType = android.text.InputType.TYPE_NULL
        }
        modelLayout.addView(modelDropdown)
        layout.addView(modelLayout)

        // API Key
        val apiKeyLayout = TextInputLayout(requireContext()).apply {
            hint = "API Key (leave empty to keep current)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        val apiKeyInput = EditText(requireContext()).apply { setSingleLine() }
        apiKeyLayout.addView(apiKeyInput)
        layout.addView(apiKeyLayout)

        AlertDialog.Builder(requireContext())
            .setTitle(providerDisplayName)
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val selectedModel = modelDropdown.text.toString().ifBlank { null }
                val apiKey = apiKeyInput.text.toString().trim().ifBlank { null }
                applyAgentConfig(providerId, selectedModel, apiKey)
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showLocalLLMConfigDialog(providerName: String) {
        val dialog = LocalLLMConfigDialog { _, _ ->
            // LocalLLM config was saved via the dialog
            updateCurrentStatus()
        }
        dialog.show(parentFragmentManager, "LocalLLMConfigDialog")
    }

    private fun applyAgentConfig(providerId: String, modelName: String?, apiKey: String?) {
        if (apiKey != null) {
            // Save API key to SharedPreferences
            val keyName = when (providerId) {
                "gemini" -> "api_key_gemini"
                "openai" -> "api_key_openai"
                "claude" -> "api_key_claude"
                "deepseek" -> "api_key_deepseek"
                "grok" -> "api_key_grok"
                else -> "api_key_$providerId"
            }
            requireContext().getSharedPreferences("api_keys", Context.MODE_PRIVATE)
                .edit().putString(keyName, apiKey).apply()
        }

        if (modelName != null) {
            agents.setAgent(modelName)
        }
        agents.setProvider(providerId)

        if (aiAgent.setProvider(providerId)) {
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()

            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                }
            }
            showSnackbar("Agent configured: ${currentProviderText.text}")
        } else {
            showSnackbar("No valid API key for ${currentProviderText.text}")
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            showSnackbar(if (isChecked) "Auto-switch enabled" else "Auto-switch disabled")
        }

        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState

        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            isCompletionEnabled = isChecked
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit().putBoolean("code_completion_enabled", isChecked).apply()
            lifecycleScope.launch { applyCompletionStateChange(isChecked) }
            showSnackbar(if (isChecked) "Code completion enabled" else "Code completion disabled")
        }
    }

    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        completionStateMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(100)
                val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                if (savedState != isCompletionEnabled) {
                    isCompletionEnabled = savedState
                    if (codeCompletionToggle.isChecked != savedState) codeCompletionToggle.isChecked = savedState
                    applyCompletionStateChange(savedState)
                }
            }
        }
    }

    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        if (enabled) codeCompletionManager?.reattachToCurrentEditor()
        else codeCompletionManager?.cleanup()
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}