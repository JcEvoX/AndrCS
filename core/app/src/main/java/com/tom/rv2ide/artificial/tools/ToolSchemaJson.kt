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

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [WorkspaceToolRegistry] 的 provider-neutral 声明转成 OpenAI Chat Completions
 * 的 `tools` JSON 数组(也兼容 Anthropic / DeepSeek / Grok 等 OpenAI-compatible 接口)。
 *
 * 设计参考 ACSIDE 反编译样本(`afad2351...`)中 `WorkspaceTools` 向 OpenAI schema
 * 转换的固定结构。仅复用 schema 形状,不复制反编译源码。
 */
object ToolSchemaJson {

  fun toArray(definitions: List<ToolDefinition>): JSONArray {
    val array = JSONArray()
    definitions.forEach { def ->
      array.put(
        JSONObject().apply {
          put("type", "function")
          put(
            "function",
            JSONObject().apply {
              put("name", def.name)
              put("description", def.description)
              put("parameters", buildParameters(def.parameters))
            },
          )
        },
      )
    }
    return array
  }

  private fun buildParameters(params: List<ToolParameter>): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    params.forEach { p ->
      properties.put(
        p.name,
        JSONObject().apply {
          put("type", p.type)
          put("description", p.description)
        },
      )
      if (p.required) required.put(p.name)
    }
    return JSONObject().apply {
      put("type", "object")
      put("properties", properties)
      if (required.length() > 0) put("required", required)
    }
  }
}
