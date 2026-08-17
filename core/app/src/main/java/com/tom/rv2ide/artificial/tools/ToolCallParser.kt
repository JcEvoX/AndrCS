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

package com.tom.rv2ide.artificial.tools

import org.json.JSONException
import org.json.JSONObject

/**
 * 把 OpenAI Chat Completions 返回的 `tool_calls[].function.{name,arguments}` 解析成
 * provider-neutral 的 [ToolCall] 子类。`arguments` 是模型生成的 JSON 字符串。
 */
object ToolCallParser {

  /**
   * @param id 工具调用 ID(由模型给出,需要原样回填到 tool_call_id)。
   * @param name 工具名。
   * @param argumentsJson 模型生成的 JSON 字符串参数。
   * @return 解析后的 [ToolCall],或 [ParseError] 给出可读原因。
   */
  fun parse(id: String, name: String, argumentsJson: String): Result<ToolCall> = runCatching {
    val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
    when (name) {
      "list_files" -> ToolCall.ListFiles(id = id, path = args.optString("path").ifBlank { "." })
      "read_file" -> ToolCall.ReadFile(id = id, path = args.requiredString("path"))
      "search_files" -> ToolCall.SearchFiles(id = id, query = args.requiredString("query"), path = args.optString("path").ifBlank { "." })
      "write_file" -> ToolCall.WriteFile(id = id, path = args.requiredString("path"), content = args.requiredString("content"))
      "delete_file" -> ToolCall.DeleteFile(id = id, path = args.requiredString("path"))
      "create_directory" -> ToolCall.CreateDirectory(id = id, path = args.requiredString("path"))
      "file_info" -> ToolCall.FileInfo(id = id, path = args.requiredString("path"))
      else -> throw IllegalArgumentException("Unknown tool: $name")
    }
  }

  private fun JSONObject.requiredString(key: String): String {
    val v = optString(key)
    if (v.isBlank()) throw JSONException("Missing required parameter: $key")
    return v
  }
}
