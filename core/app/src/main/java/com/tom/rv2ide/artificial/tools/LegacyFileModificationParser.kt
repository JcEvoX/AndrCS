package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.parser.SnippetParser
import java.util.UUID

/**
 * Temporary adapter for providers that still return the old FILE_TO_MODIFY text protocol.
 *
 * The adapter deliberately only parses a response. It never writes a file; all mutations are
 * routed through [WorkspaceToolExecutor].
 */
class LegacyFileModificationParser(
    private val snippetParser: SnippetParser = SnippetParser(),
) {

  fun parse(response: String): List<ToolCall.WriteFile> {
    val calls = mutableListOf<ToolCall.WriteFile>()
    var path: String? = null
    val content = StringBuilder()

    fun addCurrent() {
      val currentPath = path ?: return
      if (content.isEmpty()) return
      calls +=
          ToolCall.WriteFile(
              id = UUID.randomUUID().toString(),
              path = currentPath,
              content = snippetParser.cleanFileContent(content.toString()),
          )
    }

    response.lineSequence().forEach { line ->
      if (line.startsWith(FILE_MARKER)) {
        addCurrent()
        path = line.substringAfter(FILE_MARKER).trim().takeIf(String::isNotEmpty)
        content.clear()
      } else if (path != null) {
        content.appendLine(line)
      }
    }
    addCurrent()
    return calls
  }

  companion object {
    const val FILE_MARKER = "FILE_TO_MODIFY:"
  }
}
