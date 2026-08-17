package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import java.io.File

/**
 * Single enforcement point for AI workspace operations.
 */
class WorkspaceToolExecutor(
    private val permissionManager: AIPermissionManager,
    private val fileWriter: AIFileWriter,
    private val workspaceRoot: () -> File?,
    private val requestApproval: suspend (path: String) -> Boolean,
) {

  suspend fun execute(call: ToolCall): ToolResult =
      when (call) {
        is ToolCall.ListFiles -> executeListFiles(call)
        is ToolCall.ReadFile -> executeReadFile(call)
        is ToolCall.SearchFiles -> executeSearchFiles(call)
        is ToolCall.FileInfo -> executeFileInfo(call)
        is ToolCall.WriteFile -> executeWrite(call)
        is ToolCall.DeleteFile -> executeDeleteFile(call)
        is ToolCall.CreateDirectory -> executeCreateDirectory(call)
      }

  private fun executeListFiles(call: ToolCall.ListFiles): ToolResult {
    val directory = resolveWorkspacePath(call.path)
        ?: return ToolResult.Rejected(call.id, "Path is outside the opened project")
    if (!directory.isDirectory) return ToolResult.Failure(call.id, "Path is not a directory")

    val children =
        directory.listFiles()
            ?.asSequence()
            ?.filterNot(::isSensitive)
            ?.sortedBy { it.name.lowercase() }
            ?.take(MAX_LIST_ENTRIES)
            ?.joinToString("\n") { child ->
              val relative = child.relativeTo(workspaceRoot()!!.canonicalFile).path
              if (child.isDirectory) "$relative/" else relative
            }
            .orEmpty()
    return ToolResult.Success(call.id, children.ifEmpty { "(empty)" })
  }

  private fun executeReadFile(call: ToolCall.ReadFile): ToolResult {
    val file = resolveWorkspacePath(call.path)
        ?: return ToolResult.Rejected(call.id, "Path is outside the opened project")
    if (!file.isFile) return ToolResult.Failure(call.id, "File does not exist")
    if (isSensitive(file)) return ToolResult.Rejected(call.id, "File is excluded from AI access")
    if (file.length() > MAX_READ_BYTES) return ToolResult.Rejected(call.id, "File exceeds read limit")

    return try {
      ToolResult.Success(call.id, file.readText())
    } catch (error: Exception) {
      ToolResult.Failure(call.id, "Unable to read file: ${error.message}")
    }
  }

  private fun executeSearchFiles(call: ToolCall.SearchFiles): ToolResult {
    if (call.query.isBlank()) return ToolResult.Rejected(call.id, "Search query is blank")
    val directory = resolveWorkspacePath(call.path)
        ?: return ToolResult.Rejected(call.id, "Path is outside the opened project")
    if (!directory.isDirectory) return ToolResult.Failure(call.id, "Path is not a directory")

    val root = workspaceRoot()?.canonicalFile
        ?: return ToolResult.Rejected(call.id, "No project is open")
    val matches = mutableListOf<String>()
    directory.walkTopDown()
        .onEnter { candidate -> !isSensitive(candidate) }
        .filter { it.isFile && !isSensitive(it) && it.length() <= MAX_READ_BYTES }
        .take(MAX_SEARCHED_FILES)
        .forEach { file ->
          try {
            file.useLines { lines ->
              lines.forEachIndexed { index, line ->
                if (line.contains(call.query)) {
                  matches += "${file.relativeTo(root).path}:${index + 1}: ${line.take(MAX_MATCH_CHARS)}"
                }
              }
            }
          } catch (_: Exception) {
            // Ignore files that cannot be decoded as text.
          }
        }
    return ToolResult.Success(call.id, matches.take(MAX_SEARCH_RESULTS).joinToString("\n").ifEmpty { "(no matches)" })
  }

  private fun executeFileInfo(call: ToolCall.FileInfo): ToolResult {
    val file = resolveWorkspacePath(call.path)
        ?: return ToolResult.Rejected(call.id, "Path is outside the opened project")
    val relative = file.relativeTo(workspaceRoot()!!.canonicalFile).path
    val sb = StringBuilder()
    sb.append("path: $relative\n")
    sb.append("exists: ${file.exists()}\n")
    if (file.exists()) {
      sb.append("type: ${if (file.isDirectory) "directory" else "file"}\n")
      if (file.isFile) sb.append("size: ${file.length()} bytes\n")
      sb.append("lastModified: ${file.lastModified()} (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(file.lastModified()))})\n")
      if (file.isFile) sb.append("readable: ${file.canRead()}\n")
    }
    return ToolResult.Success(call.id, sb.toString().trimEnd())
  }

  private suspend fun executeWrite(call: ToolCall.WriteFile): ToolResult {
    if (!permissionManager.isFileWriteEnabled()) {
      return ToolResult.Rejected(call.id, "AI file writing is disabled")
    }
    if (!permissionManager.isPathAllowed(call.path)) {
      return ToolResult.Rejected(call.id, "Path is outside the opened project")
    }
    if (permissionManager.requiresConfirmation() && !requestApproval(call.path)) {
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

  private suspend fun executeDeleteFile(call: ToolCall.DeleteFile): ToolResult {
    if (!permissionManager.isFileWriteEnabled()) {
      return ToolResult.Rejected(call.id, "AI file modification is disabled")
    }
    val file = resolveWorkspacePath(call.path)
        ?: return ToolResult.Rejected(call.id, "Path is outside the opened project")
    if (!file.exists()) return ToolResult.Failure(call.id, "File does not exist")
    if (file.isDirectory) return ToolResult.Rejected(call.id, "Path points to a directory; delete_file supports files only")
    if (isSensitive(file)) return ToolResult.Rejected(call.id, "File is excluded from AI access")
    if (permissionManager.requiresConfirmation() && !requestApproval(call.path)) {
      return ToolResult.Rejected(call.id, "File deletion was denied")
    }
    return try {
      if (file.delete()) ToolResult.Success(call.id, "Deleted ${file.name}")
      else ToolResult.Failure(call.id, "delete() returned false")
    } catch (error: Exception) {
      ToolResult.Failure(call.id, "Unable to delete: ${error.message}")
    }
  }

  private suspend fun executeCreateDirectory(call: ToolCall.CreateDirectory): ToolResult {
    if (!permissionManager.isFileWriteEnabled()) {
      return ToolResult.Rejected(call.id, "AI file modification is disabled")
    }
    val dir = resolveWorkspacePath(call.path)
        ?: return ToolResult.Rejected(call.id, "Path is outside the opened project")
    if (dir.exists() && dir.isDirectory) return ToolResult.Success(call.id, "Directory already exists")
    if (dir.exists() && dir.isFile) return ToolResult.Rejected(call.id, "A file already exists at this path")
    if (permissionManager.requiresConfirmation() && !requestApproval(call.path)) {
      return ToolResult.Rejected(call.id, "Directory creation was denied")
    }
    return try {
      if (dir.mkdirs()) ToolResult.Success(call.id, "Created ${dir.relativeTo(workspaceRoot()!!.canonicalFile).path}")
      else ToolResult.Failure(call.id, "mkdirs() returned false")
    } catch (error: Exception) {
      ToolResult.Failure(call.id, "Unable to create directory: ${error.message}")
    }
  }

  private fun resolveWorkspacePath(path: String): File? {
    val root = workspaceRoot()?.canonicalFile ?: return null
    return try {
      val candidate = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
      candidate.takeIf { it.toPath().startsWith(root.toPath()) }
    } catch (_: Exception) {
      null
    }
  }

  private fun isSensitive(file: File): Boolean {
    val name = file.name.lowercase()
    return name in SENSITIVE_FILE_NAMES ||
        file.path.split(File.separatorChar).any { it in EXCLUDED_DIRECTORY_NAMES }
  }

  private companion object {
    const val MAX_LIST_ENTRIES = 500
    const val MAX_READ_BYTES = 512 * 1024L
    const val MAX_SEARCHED_FILES = 500
    const val MAX_SEARCH_RESULTS = 100
    const val MAX_MATCH_CHARS = 300
    val SENSITIVE_FILE_NAMES = setOf(".env", "local.properties", "keystore.properties")
    val EXCLUDED_DIRECTORY_NAMES = setOf(".git", ".gradle", ".andrcs", "build")
  }
}
