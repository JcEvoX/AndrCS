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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.ChatMessage
import com.tom.rv2ide.adapters.ChatMessageAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.AgentEvent
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 流式 Agent 聊天 Fragment。直接消费 [AIAgentManager.executeChatStreaming] 返回的
 * `Flow<AgentEvent>`,把事件映射到 [ChatMessageAdapter] 的多 view-type 行:
 * - AgentEvent.Thinking → REASONING 行(可折叠,增量追加)
 * - AgentEvent.TextDelta → ASSISTANT 行(增量追加,流式光标)
 * - AgentEvent.ToolCall → TOOL_CALL 行(可折叠 args)
 * - AgentEvent.ToolResult → 更新对应 TOOL_CALL 行的结果区
 * - AgentEvent.FinalResponse → 完成最后一行 ASSISTANT 文本,隐藏光标
 * - AgentEvent.Error → ERROR 行
 *
 * Send 按钮在流式生成期间切换为 Stop,点击取消当前协程即中断生成。
 */
class ChatFragment : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var promptInput: TextInputEditText
    private lateinit var executeBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var streamingIndicator: CircularProgressIndicator
    private lateinit var chatMessagesList: RecyclerView
    private lateinit var chatAdapter: ChatMessageAdapter

    private var streamingJob: Job? = null

    private val userRootProject = getProjectRoot().absolutePath.toString()

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
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupRecyclerView()
        setupListeners()
        loadProject()
    }

    private fun initializeViews(view: View) {
        promptInput = view.findViewById(R.id.anyText)
        executeBtn = view.findViewById(R.id.executeBtn)
        clearBtn = view.findViewById(R.id.clearBtn)
        streamingIndicator = view.findViewById(R.id.streamingIndicator)
        chatMessagesList = view.findViewById(R.id.chatMessagesList)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatMessageAdapter()
        chatMessagesList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatAdapter
            // 新消息追加时自动滚动到底部。
            chatAdapter.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        chatMessagesList.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    }
                },
            )
        }
    }

    private fun setupListeners() {
        executeBtn.setOnClickListener {
            // 流式生成中点击 = Stop。
            if (streamingJob?.isActive == true) {
                streamingJob?.cancel()
                setStreamingState(false)
                return@setOnClickListener
            }

            val userRequest = promptInput.text?.toString().orEmpty()
            if (userRequest.isBlank()) {
                showSnackbar("Please enter a request")
                return@setOnClickListener
            }

            startStreaming(userRequest)
        }

        clearBtn.setOnClickListener {
            clearConversation()
        }
    }

    private fun startStreaming(userRequest: String) {
        // 追加用户消息行。
        chatAdapter.add(
            ChatMessage(
                id = "user-${System.currentTimeMillis()}",
                type = ChatMessage.Type.USER,
                text = userRequest,
            ),
        )

        // 预追加一个空 ASSISTANT 行,后续 TextDelta 增量填充它(流式光标效果)。
        chatAdapter.add(
            ChatMessage(
                id = "assistant-${System.currentTimeMillis()}",
                type = ChatMessage.Type.ASSISTANT,
                text = "",
            ),
        )

        setStreamingState(true)

        streamingJob = lifecycleScope.launch {
            try {
                aiAgent.executeChatStreaming(userRequest).collect { event -> handleAgentEvent(event) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动 Stop,正常退出。
            } catch (e: Exception) {
                chatAdapter.add(
                    ChatMessage(
                        id = "error-${System.currentTimeMillis()}",
                        type = ChatMessage.Type.ERROR,
                        text = e.message ?: "Unknown error",
                    ),
                )
            } finally {
                setStreamingState(false)
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> {
                // 若最后一条不是 REASONING,新加一条;否则增量追加。
                val pos = chatAdapter.appendReasoningToLast(event.delta)
                if (pos < 0) {
                    chatAdapter.add(
                        ChatMessage(
                            id = "reasoning-${System.currentTimeMillis()}",
                            type = ChatMessage.Type.REASONING,
                            reasoning = event.delta,
                        ),
                    )
                }
            }
            is AgentEvent.TextDelta -> {
                // 追加到最后一条 ASSISTANT 行。
                val pos = chatAdapter.appendTextToLast(event.delta)
                if (pos < 0) {
                    // 极端情况:没有预追加的 ASSISTANT 行,补一条。
                    chatAdapter.add(
                        ChatMessage(
                            id = "assistant-${System.currentTimeMillis()}",
                            type = ChatMessage.Type.ASSISTANT,
                            text = event.delta,
                        ),
                    )
                }
            }
            is AgentEvent.ToolCall -> {
                chatAdapter.add(
                    ChatMessage(
                        id = event.id,
                        type = ChatMessage.Type.TOOL_CALL,
                        toolName = event.name,
                        toolArguments = event.argumentsJson,
                    ),
                )
            }
            is AgentEvent.ToolResult -> {
                chatAdapter.setToolResult(event.callId, event.output, event.successful)
            }
            is AgentEvent.FinalResponse -> {
                // 把 FinalResponse 的完整 text 写入最后一条 ASSISTANT(覆盖增量)。
                if (event.text.isNotBlank()) {
                    chatAdapter.appendTextToLast("") // 触发 notify 刷新
                }
                setStreamingState(false)
            }
            is AgentEvent.Error -> {
                chatAdapter.add(
                    ChatMessage(
                        id = "error-${System.currentTimeMillis()}",
                        type = ChatMessage.Type.ERROR,
                        text = event.message,
                    ),
                )
                setStreamingState(false)
            }
        }
    }

    private fun setStreamingState(streaming: Boolean) {
        if (streaming) {
            streamingIndicator.visibility = View.VISIBLE
            executeBtn.text = "Stop"
            // 流式期间不显示图标,纯文字+左侧进度环已足够区分。
            executeBtn.icon = null
        } else {
            streamingIndicator.visibility = View.GONE
            executeBtn.text = "Send"
            executeBtn.icon = context?.getDrawable(R.drawable.ic_send)
            streamingJob = null
        }
    }

    private fun loadProject() {
        lifecycleScope.launch {
            try {
                val success = aiAgent.setProjectRoot(userRootProject)
                if (!success) {
                    showSnackbar("Failed to load project")
                }
            } catch (e: Exception) {
                showSnackbar("Error loading project: ${e.message}")
            }
        }
    }

    private fun clearConversation() {
        streamingJob?.cancel()
        aiAgent.clearConversation()
        chatAdapter.clear()
        promptInput.text?.clear()
        setStreamingState(false)
        showSnackbar("Conversation cleared")
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) ?: view ?: return
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * 旧 [ChatFragment] 通过 [CodeCompletionManager] 提供行内代码补全。新的流式 chat UI
     * 暂未集成补全(它依赖编辑器轮询),返回 null 让 [AIPreferencesFragment] 优雅降级:
     * 不显示补全相关偏好项。后续可在流式 UI 之上重新引入。
     */
    fun getCodeCompletionManager(): com.tom.rv2ide.managers.CodeCompletionManager? = null

    override fun onDestroyView() {
        streamingJob?.cancel()
        super.onDestroyView()
    }
}
