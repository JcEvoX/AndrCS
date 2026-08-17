package com.tom.rv2ide.artificial.tools

/**
 * Provider-neutral workspace operations. Provider adapters translate their native tool-call
 * formats into these calls; only [WorkspaceToolExecutor] performs the operation.
 */
sealed interface ToolCall {
  val id: String
  val name: String

  data class ListFiles(
      override val id: String,
      val path: String = ".",
  ) : ToolCall {
    override val name: String = "list_files"
  }

  data class ReadFile(
      override val id: String,
      val path: String,
  ) : ToolCall {
    override val name: String = "read_file"
  }

  data class SearchFiles(
      override val id: String,
      val query: String,
      val path: String = ".",
  ) : ToolCall {
    override val name: String = "search_files"
  }

  data class WriteFile(
      override val id: String,
      val path: String,
      val content: String,
  ) : ToolCall {
    override val name: String = "write_file"
  }

  data class DeleteFile(
      override val id: String,
      val path: String,
  ) : ToolCall {
    override val name: String = "delete_file"
  }

  data class CreateDirectory(
      override val id: String,
      val path: String,
  ) : ToolCall {
    override val name: String = "create_directory"
  }

  data class FileInfo(
      override val id: String,
      val path: String,
  ) : ToolCall {
    override val name: String = "file_info"
  }
}

sealed interface ToolResult {
  val callId: String

  data class Success(
      override val callId: String,
      val output: String,
  ) : ToolResult

  data class Rejected(
      override val callId: String,
      val reason: String,
  ) : ToolResult

  data class Failure(
      override val callId: String,
      val reason: String,
  ) : ToolResult
}
