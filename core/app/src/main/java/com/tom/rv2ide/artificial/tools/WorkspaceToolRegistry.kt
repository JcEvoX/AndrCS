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
              parameters =
                  listOf(
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative path. Defaults to project root.",
                          required = false,
                      ),
                  ),
          ),
          ToolDefinition(
              name = "read_file",
              description = "Read a non-sensitive UTF-8 text file in the workspace.",
              mutatesWorkspace = false,
              parameters =
                  listOf(
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative path of the file to read.",
                      ),
                  ),
          ),
          ToolDefinition(
              name = "search_files",
              description = "Search non-sensitive workspace text files for a literal query.",
              mutatesWorkspace = false,
              parameters =
                  listOf(
                      ToolParameter(
                          name = "query",
                          type = "string",
                          description = "Literal substring to look for.",
                      ),
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative directory to search. Defaults to project root.",
                          required = false,
                      ),
                  ),
          ),
          ToolDefinition(
              name = "write_file",
              description = "Create or replace a text file in the workspace after user approval.",
              mutatesWorkspace = true,
              parameters =
                  listOf(
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative path of the file to write.",
                      ),
                      ToolParameter(
                          name = "content",
                          type = "string",
                          description = "Full UTF-8 text content to write.",
                      ),
                  ),
          ),
          ToolDefinition(
              name = "delete_file",
              description = "Delete a file in the workspace after user approval. Directories are not supported.",
              mutatesWorkspace = true,
              parameters =
                  listOf(
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative path of the file to delete.",
                      ),
                  ),
          ),
          ToolDefinition(
              name = "create_directory",
              description = "Create a directory (and missing parents) in the workspace after user approval.",
              mutatesWorkspace = true,
              parameters =
                  listOf(
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative path of the directory to create.",
                      ),
                  ),
          ),
          ToolDefinition(
              name = "file_info",
              description = "Query metadata for a workspace file: existence, size, last-modified time, type.",
              mutatesWorkspace = false,
              parameters =
                  listOf(
                      ToolParameter(
                          name = "path",
                          type = "string",
                          description = "Workspace-relative path of the file to inspect.",
                      ),
                  ),
          ),
      )

  fun find(name: String): ToolDefinition? = definitions.firstOrNull { it.name == name }

  /** Whether the named tool requires user confirmation before applying. */
  fun requiresConfirmation(name: String): Boolean =
      definitions.firstOrNull { it.name == name }?.mutatesWorkspace == true
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
    val description: String = "",
    val required: Boolean = true,
)
