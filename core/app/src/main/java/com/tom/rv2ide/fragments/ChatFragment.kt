package com.tom.rv2ide.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.ChatMessage
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.AgentEvent
import com.tom.rv2ide.artificial.tools.ToolCall
import com.tom.rv2ide.artificial.tools.ToolResult
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.handlers.AIRequestHandler
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.adapters.ToolCallItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ChatFragment : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var promptInput: TextInputEditText
    private lateinit var executeBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler

    private var typingJob: Job? = null
    private var fileMonitorJob: Job? = null
    private var completionStateMonitorJob: Job? = null
    private var lastMonitoredFile: File? = null
    private var isSettingUpCompletion = false
    private var chatJob: Job? = null

    private val messageIdCounter = AtomicLong(0)
    private val userRootProject = getProjectRoot().absolutePath.toString()

    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "code_completion_enabled") {
            val isEnabled = prefs.getBoolean(key, true)
            lifecycleScope.launch { handleCompletionStateChange(isEnabled) }
        }
    }

    companion object {
        fun newInstance(aiAgent: AIAgentManager): ChatFragment {
            return ChatFragment().apply { this.aiAgent = aiAgent }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!::aiAgent.isInitialized) {
            aiAgent = AIAgentManager(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupRecyclerView()
        setupManagers()
        setupListeners()
        loadProject()
        registerPreferenceListener()
    }

    override fun onResume() {
        super.onResume()
        startFileMonitoring()
        startCompletionStateMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopFileMonitoring()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        promptInput = view.findViewById(R.id.anyText)
        executeBtn = view.findViewById(R.id.executeBtn)
        clearBtn = view.findViewById(R.id.clearBtn)
        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        emptyState = view.findViewById(R.id.emptyState)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatMessageAdapter { msg -> retryMessage(msg) }
        chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupManagers() {
        codeCompletionManager = CodeCompletionManager.getInstance(requireContext(), lifecycleScope, aiAgent)

        // Keep AIRequestHandler for legacy file operations (not used for streaming UI)
        aiRequestHandler = AIRequestHandler(
            lifecycleScope, aiAgent,
            com.google.android.material.textview.MaterialTextView(requireContext()),
            com.google.android.material.textview.MaterialTextView(requireContext()),
            com.google.android.material.progressindicator.CircularProgressIndicator(requireContext()),
            executeBtn,
            RecyclerView(requireContext()),
            com.tom.rv2ide.adapters.FileModificationAdapter(),
            LinearLayout(requireContext()),
            onFileOpen = { openFileInEditor(it) },
            onTypeText = { text, delay -> typeText(text, delay) },
            getCurrentFile = { getCurrentFile() },
            refreshEditor = { refreshCurrentEditor() }
        )
    }

    private fun setupListeners() {
        executeBtn.setOnClickListener {
            val userRequest = promptInput.text.toString()
            if (userRequest.isBlank()) {
                showSnackbar("Please enter a request")
                return@setOnClickListener
            }
            codeCompletionManager.clearSuggestion()
            executeStreamingChat(userRequest)
        }
        clearBtn.setOnClickListener { clearConversation() }
    }

    private fun executeStreamingChat(userRequest: String) {
        chatJob?.cancel()
        val userMsg = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            isUser = true,
            role = ChatMessage.Role.USER,
            text = StringBuilder(userRequest),
            isComplete = true,
        )
        val agentMsg = ChatMessage(
            id = messageIdCounter.incrementAndGet(),
            isUser = false,
            role = ChatMessage.Role.AGENT,
        )

        chatAdapter.submitList(chatAdapter.currentList + userMsg + agentMsg)
        chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
        emptyState.isVisible = false
        promptInput.text?.clear()
        executeBtn.isEnabled = false

        chatJob = lifecycleScope.launch {
            try {
                aiAgent.executeChatStreaming(userRequest)
                    .catch { e ->
                        updateAgentMessage(agentMsg.id) {
                            it.appendText("\nError: ${e.message}").markError()
                        }
                    }
                    .collect { event ->
                        when (event) {
                            is AgentEvent.Thinking -> updateAgentMessage(agentMsg.id) {
                                it.appendThinking(event.text)
                            }
                            is AgentEvent.TextDelta -> updateAgentMessage(agentMsg.id) {
                                it.appendText(event.text)
                            }
                            is AgentEvent.ToolCall -> {
                                val tc = event.call
                                val item = ToolCallItem(
                                    id = tc.name + "_" + System.currentTimeMillis(),
                                    name = when (tc) {
                                        is ToolCall.WriteFile -> "write ${File(tc.path).name}"
                                        is ToolCall.ReadFile -> "read ${File(tc.path).name}"
                                        is ToolCall.ListFiles -> "list ${tc.path}"
                                        is ToolCall.SearchFiles -> "search ${tc.pattern}"
                                        else -> tc.name
                                    },
                                    arguments = "",
                                )
                                updateAgentMessage(agentMsg.id) { it.addToolCall(item) }
                            }
                            is AgentEvent.ToolResult -> {
                                val resultId = when (event.result) {
                                    is ToolResult.Success -> event.result.toolName
                                    is ToolResult.Failure -> event.result.toolName
                                    is ToolResult.Rejected -> event.result.toolName
                                    else -> "unknown"
                                }
                                val resultText = when (event.result) {
                                    is ToolResult.Success -> event.result.output
                                    is ToolResult.Failure -> "Error: ${event.result.reason}"
                                    is ToolResult.Rejected -> "Rejected: ${event.result.reason}"
                                    else -> ""
                                }
                                val success = event.result is ToolResult.Success
                                updateAgentMessage(agentMsg.id) {
                                    it.updateToolResult(resultId, resultText, success)
                                }
                            }
                            is AgentEvent.FinalResponse -> updateAgentMessage(agentMsg.id) {
                                if (it.text.isEmpty()) it.appendText(event.text)
                                it.markComplete()
                            }
                            is AgentEvent.Error -> updateAgentMessage(agentMsg.id) {
                                it.appendText("\nError: ${event.message}").markError()
                            }
                        }
                    }
                // Finalize
                updateAgentMessage(agentMsg.id) { it.markComplete() }
            } catch (e: Exception) {
                updateAgentMessage(agentMsg.id) {
                    it.appendText("\nError: ${e.message}").markError()
                }
            } finally {
                executeBtn.isEnabled = true
            }
        }
    }

    private fun updateAgentMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        val list = chatAdapter.currentList.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = transform(list[idx])
            chatAdapter.submitList(list)
            chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun retryMessage(msg: ChatMessage) {
        // Re-send the last user message before this agent message
        val list = chatAdapter.currentList
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx > 0) {
            val userMsg = list.getOrNull(idx - 1)
            if (userMsg?.isUser == true) {
                executeStreamingChat(userMsg.text.toString())
            }
        }
    }

    private fun registerPreferenceListener() {
        requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private fun unregisterPreferenceListener() {
        requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private suspend fun handleCompletionStateChange(enabled: Boolean) {
        if (enabled) {
            delay(200)
            setupCodeCompletionForCurrentFile()
        } else {
            codeCompletionManager.cleanup()
        }
    }

    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        completionStateMonitorJob = lifecycleScope.launch {
            var lastKnownState = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                .getBoolean("code_completion_enabled", true)
            while (true) {
                delay(200)
                val currentState = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                if (currentState != lastKnownState) {
                    lastKnownState = currentState
                    handleCompletionStateChange(currentState)
                }
            }
        }
    }

    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private fun loadProject() {
        lifecycleScope.launch {
            try {
                aiAgent.setProjectRoot(userRootProject)
            } catch (_: Exception) {}
        }
    }

    private fun startFileMonitoring() {
        stopFileMonitoring()
        fileMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                if (isSettingUpCompletion) continue
                val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean("code_completion_enabled", true)) continue
                val currentFile = getCurrentFile()
                if (currentFile != null && currentFile != lastMonitoredFile) {
                    lastMonitoredFile = currentFile
                    setupCodeCompletionForCurrentFile()
                }
            }
        }
    }

    private fun stopFileMonitoring() {
        fileMonitorJob?.cancel()
        fileMonitorJob = null
    }

    private fun setupCodeCompletionForCurrentFile() {
        if (isSettingUpCompletion) return
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("code_completion_enabled", true)) return
        isSettingUpCompletion = true
        lifecycleScope.launch {
            delay(200)
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            if (editor != null && suggestionView != null) {
                codeCompletionManager.setup(editor, suggestionView,
                    onReady = { isSettingUpCompletion = false },
                    onError = { isSettingUpCompletion = false }
                )
            } else {
                isSettingUpCompletion = false
            }
        }
    }

    fun getCodeCompletionManager(): CodeCompletionManager = codeCompletionManager

    private fun openFileInEditor(fileName: String) {
        if (userRootProject.isBlank()) return
        lifecycleScope.launch {
            try {
                val file = findFileInProject(File(userRootProject), fileName) ?: return@launch
                val activity = requireActivity()
                if (activity is EditorHandlerActivity) {
                    activity.openFile(file)
                    lastMonitoredFile = file
                    delay(500)
                    setupCodeCompletionForCurrentFile()
                }
            } catch (_: Exception) {}
        }
    }

    private fun findFileInProject(projectRoot: File, fileName: String): File? {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return null
        return projectRoot.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
    }

    private fun typeText(text: String, delayMs: Long = 10L) {
        typingJob?.cancel()
        typingJob = lifecycleScope.launch {
            try {
                val editor = getCurrentEditor() ?: return@launch
                editor.setText(text)
            } catch (_: Exception) {}
        }
    }

    fun clearConversation() {
        lifecycleScope.launch {
            chatJob?.cancel()
            typingJob?.cancel()
            codeCompletionManager.clearSuggestion()
            aiAgent.clearConversation()
            promptInput.text?.clear()
            chatAdapter.submitList(emptyList())
            emptyState.isVisible = true
            showSnackbar("Conversation cleared")
        }
    }

    private fun getCurrentEditor() = try {
        (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor()?.editor
    } catch (_: Exception) { null }

    private fun getCurrentFile() = try {
        (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor()?.file
    } catch (_: Exception) { null }

    private fun getCurrentSuggestionView() = try {
        (requireActivity() as? EditorHandlerActivity)?.getCurrentEditor()?.suggestionView
    } catch (_: Exception) { null }

    private fun refreshCurrentEditor() {
        try {
            val activity = requireActivity() as? EditorHandlerActivity ?: return
            val editor = activity.getCurrentEditor() ?: return
            val file = editor.file ?: return
            editor.editor?.text?.replace(0, editor.editor?.text?.length ?: 0, file.readText())
        } catch (_: Exception) {}
    }

    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        chatJob?.cancel()
        typingJob?.cancel()
        fileMonitorJob?.cancel()
        completionStateMonitorJob?.cancel()
        unregisterPreferenceListener()
        super.onDestroyView()
    }
}