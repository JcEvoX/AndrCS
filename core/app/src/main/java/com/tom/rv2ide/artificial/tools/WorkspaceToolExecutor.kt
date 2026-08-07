package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.permissions.AIPermissionManager

/**
 * Single enforcement point for mutating AI workspace operations.
 *
 * Reads will be added alongside provider-native tool calling. Writes are implemented first so the
 * legacy response format and future tool-calling adapters share exactly the same policy.
 */
class WorkspaceToolExecutor(
    private val permissionManager: AIPermissionManager,
    private val fileWriter: AIFileWriter,
    private val requestApproval: suspend (ToolCall.WriteFile) -> Boolean,
) {

  suspend fun execute(call: ToolCall): ToolResult =
      when (call) {
        is ToolCall.WriteFile -> executeWrite(call)
        else ->
            ToolResult.Rejected(
                call.id,
                "${call.name} is not enabled by this workspace executor",
            )
      }

  private suspend fun executeWrite(call: ToolCall.WriteFile): ToolResult {
    if (!permissionManager.isFileWriteEnabled()) {
      return ToolResult.Rejected(call.id, "AI file writing is disabled")
    }
    if (!permissionManager.isPathAllowed(call.path)) {
      return ToolResult.Rejected(call.id, "Path is outside the opened project")
    }
    if (permissionManager.requiresConfirmation() && !requestApproval(call)) {
      return ToolResult.Rejected(call.id, "File write was denied")
    }

    return when (val result = fileWriter.writeFile(call.path, call.content)) {
      is FileWriteResult.Success ->
          ToolResult.Success(
              call.id,
              "Wrote ${result.path}" +
                  if (result.backupCreated) " (backup created)" else "",
          )
      is FileWriteResult.PermissionDenied -> ToolResult.Rejected(call.id, result.reason)
      is FileWriteResult.Error -> ToolResult.Failure(call.id, result.message)
    }
  }
}
