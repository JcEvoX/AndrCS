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

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import android.content.SharedPreferences
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.AgentTimelineAdapter
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.handlers.AIRequestHandler
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ChatFragment : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var promptInput: TextInputEditText
    private lateinit var executeBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var statusText: MaterialTextView
    private lateinit var summaryText: MaterialTextView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var fileModificationList: RecyclerView
    private lateinit var agentTimelineList: RecyclerView
    private lateinit var summaryCard: LinearLayout
    private lateinit var fileModificationAdapter: FileModificationAdapter
    private lateinit var agentTimelineAdapter: AgentTimelineAdapter
    
    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler
    
    private var typingJob: Job? = null
    private var fileMonitorJob: Job? = null
    private var completionStateMonitorJob: Job? = null
    private var lastMonitoredFile: File? = null
    private var isSettingUpCompletion = false
    
    private val userRootProject = getProjectRoot().absolutePath.toString()
    
    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "code_completion_enabled") {
            val isEnabled = prefs.getBoolean(key, true)
            android.util.Log.d("ChatFragment", "Completion preference changed: $isEnabled")
            
            lifecycleScope.launch {
                handleCompletionStateChange(isEnabled)
            }
        }
    }

    companion object {
        fun newInstance(aiAgent: AIAgentManager): ChatFragment {
            return ChatFragment().apply {
                this.aiAgent = aiAgent
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!::aiAgent.isInitialized) {
            aiAgent = AIAgentManager(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

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
        statusText = view.findViewById(R.id.statusText)
        summaryText = view.findViewById(R.id.summaryText)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        fileModificationList = view.findViewById(R.id.fileModificationList)
        agentTimelineList = view.findViewById(R.id.agentTimelineList)
        summaryCard = view.findViewById(R.id.summaryCard)
    }

    private fun setupRecyclerView() {
        fileModificationAdapter = FileModificationAdapter()
        fileModificationList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fileModificationAdapter
            isNestedScrollingEnabled = false
        }
        
        fileModificationAdapter.setOnItemClickListener { fileName ->
            openFileInEditor(fileName)
        }

        agentTimelineAdapter = AgentTimelineAdapter()
        agentTimelineList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = agentTimelineAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupManagers() {
        codeCompletionManager = CodeCompletionManager.getInstance(
            requireContext(),
            lifecycleScope,
            aiAgent
        )
        
        aiRequestHandler = AIRequestHandler(
            lifecycleScope,
            aiAgent,
            statusText,
            summaryText,
            progressIndicator,
            executeBtn,
            fileModificationList,
            fileModificationAdapter,
            agentTimelineList,
            agentTimelineAdapter,
            summaryCard,
            onFileOpen = { fileName ->
                openFileInEditor(fileName)
            },
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
            aiRequestHandler.execute(userRequest)
        }
    
        clearBtn.setOnClickListener {
            clearConversation()
        }
    }
    
    private fun registerPreferenceListener() {
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
    
    private fun unregisterPreferenceListener() {
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
    
    private suspend fun handleCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("ChatFragment", "handleCompletionStateChange: $enabled")
        
        if (enabled) {
            delay(200)
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            
            if (editor != null && suggestionView != null) {
                android.util.Log.d("ChatFragment", "Re-enabling completion for current file")
                setupCodeCompletionForCurrentFile()
            }
        } else {
            android.util.Log.d("ChatFragment", "Disabling completion")
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
                    android.util.Log.d("ChatFragment", "State change detected in monitor: $lastKnownState -> $currentState")
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
                val success = aiAgent.setProjectRoot(userRootProject)
                
                if (success) {
                    statusText.text = "Project loaded successfully"
                } else {
                    statusText.text = "Failed to load project"
                }
            } catch (e: Exception) {
                statusText.text = "Error loading project: ${e.message}"
            }
        }
    }
    
    private fun startFileMonitoring() {
        stopFileMonitoring()
        
        fileMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                
                if (isSettingUpCompletion) {
                    continue
                }
                
                val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("code_completion_enabled", true)
                
                if (!isEnabled) {
                    continue
                }
                
                val currentFile = getCurrentFile()
                
                if (currentFile != null && currentFile != lastMonitoredFile) {
                    android.util.Log.d("ChatFragment", "File changed detected: ${currentFile.name}")
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
        if (isSettingUpCompletion) {
            android.util.Log.d("ChatFragment", "Already setting up, skipping")
            return
        }
        
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("code_completion_enabled", true)
        
        if (!isEnabled) {
            android.util.Log.d("ChatFragment", "Code completion is disabled, skipping setup")
            return
        }
        
        isSettingUpCompletion = true
        
        lifecycleScope.launch {
            delay(200)
            
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            
            if (editor != null && suggestionView != null) {
                android.util.Log.d("ChatFragment", "Setting up code completion")
                codeCompletionManager.setup(
                    editor,
                    suggestionView,
                    onReady = {
                        android.util.Log.d("ChatFragment", "✦ Code completion ready!")
                        isSettingUpCompletion = false
                    },
                    onError = { e ->
                        android.util.Log.e("ChatFragment", "✗ Completion setup failed: ${e.message}", e)
                        isSettingUpCompletion = false
                    }
                )
            } else {
                android.util.Log.w("ChatFragment", "Editor or SuggestionView is null, cannot setup")
                isSettingUpCompletion = false
            }
        }
    }
    
    fun getCodeCompletionManager(): CodeCompletionManager {
        return codeCompletionManager
    }

    private fun openFileInEditor(fileName: String) {
        if (userRootProject.isBlank()) {
            showSnackbar("Project path not set")
            return
        }
        
        lifecycleScope.launch {
            try {
                val file = findFileInProject(File(userRootProject), fileName)
                if (file == null) {
                    showSnackbar("File not found: $fileName")
                    return@launch
                }
                
                val activity = requireActivity()
                if (activity is EditorHandlerActivity) {
                    activity.openFile(file)
                    showSnackbar("Opened: ${file.name}")
                    
                    lastMonitoredFile = file
                    delay(500)
                    setupCodeCompletionForCurrentFile()
                }
            } catch (e: Exception) {
                showSnackbar("Error opening file: ${e.message}")
            }
        }
    }
    
    private fun findFileInProject(projectRoot: File, fileName: String): File? {
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return null
        }
        
        return projectRoot.walkTopDown().firstOrNull { 
            it.isFile && it.name == fileName 
        }
    }

    private fun typeText(text: String, delayMs: Long = 10L) {
        typingJob?.cancel()
        typingJob = lifecycleScope.launch {
            try {
                val editor = getCurrentEditor() ?: return@launch
                val lines = text.lines()
                val currentText = StringBuilder()
                
                for (line in lines) {
                    val words = line.split(" ")
                    for (i in words.indices) {
                        currentText.append(words[i])
                        if (i < words.size - 1) {
                            currentText.append(" ")
                        }
                        editor.setText(currentText.toString())
                        delay(delayMs)
                    }
                    currentText.append("\n")
                    editor.setText(currentText.toString())
                }
            } catch (e: Exception) {
            }
        }
    }

    fun clearConversation() {
        lifecycleScope.launch {
            try {
                typingJob?.cancel()
                codeCompletionManager.clearSuggestion()
                aiAgent.clearConversation()
                
                promptInput.text?.clear()
                statusText.text = "Conversation cleared. Ready for new request."
                fileModificationList.visibility = View.GONE
                summaryCard.visibility = View.GONE
                fileModificationAdapter.clear()
                
                showSnackbar("Conversation cleared")
            } catch (e: Exception) {
                showSnackbar("Error clearing: ${e.message}")
            }
        }
    }

    private fun getCurrentEditor() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.editor
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun getCurrentFile() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.file
        } else null
    } catch (e: Exception) {
        null
    }
    
    private fun getCurrentSuggestionView() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.suggestionView
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun refreshCurrentEditor() {
        try {
            val activity = requireActivity()
            if (activity is EditorHandlerActivity) {
                val currentEditor = activity.getCurrentEditor()
                val file = currentEditor?.file
                val newContent = file?.readText()
                val editorText = currentEditor?.editor?.text

                if (editorText != null && newContent != null) {
                    editorText.replace(0, editorText.length, newContent)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) 
            ?: view 
            ?: return
        
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        typingJob?.cancel()
        fileMonitorJob?.cancel()
        completionStateMonitorJob?.cancel()
        aiRequestHandler.cancel()
        unregisterPreferenceListener()
        super.onDestroyView()
    }
}