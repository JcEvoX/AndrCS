package com.tom.rv2ide.artificial.tools

/**
 * Provider-neutral declarations used to generate the native tool schema for each model API.
 *
 * Keeping the schemas here prevents individual providers from silently diverging in their
 * workspace capabilities or safety classification.
 */
object WorkspaceToolRegistry {
  val definitions: List<ToolDefinition> =
      listOf(
          ToolDefinition(
              name = "list_files",
              description = "List non-sensitive files and directories under a workspace path.",
              mutatesWorkspace = false,
              parameters = listOf(ToolParameter("path", "string", required = false)),
          ),
          ToolDefinition(
              name = "read_file",
              description = "Read a non-sensitive UTF-8 text file in the workspace.",
              mutatesWorkspace = false,
              parameters = listOf(ToolParameter("path", "string")),
          ),
          ToolDefinition(
              name = "search_files",
              description = "Search non-sensitive workspace text files for a literal query.",
              mutatesWorkspace = false,
              parameters =
                  listOf(
                      ToolParameter("query", "string"),
                      ToolParameter("path", "string", required = false),
                  ),
          ),
          ToolDefinition(
              name = "write_file",
              description = "Create or replace a text file in the workspace after user approval.",
              mutatesWorkspace = true,
              parameters =
                  listOf(
                      ToolParameter("path", "string"),
                      ToolParameter("content", "string"),
                  ),
          ),
      )

  fun find(name: String): ToolDefinition? = definitions.firstOrNull { it.name == name }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val mutatesWorkspace: Boolean,
    val parameters: List<ToolParameter>,
)

data class ToolParameter(
    val name: String,
    val type: String,
    val required: Boolean = true,
)
