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

package com.tom.rv2ide.artificial.project.awareness

import java.io.File
import java.io.IOException

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class ProjectData {

    /** Builds a bounded, secret-aware project index for model context. */
    fun showProjectTree(proj: File): ProjectTreeResult {
        val canonicalRoot = proj.canonicalFile
        val sb = StringBuilder()
        var entryCount = 0

        fun walk(dir: File) {
            if (entryCount >= MAX_TREE_ENTRIES) return
            val children = dir.listFiles()?.sortedWith(
                compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
            ) ?: return

            for (file in children) {
                if (entryCount >= MAX_TREE_ENTRIES) break
                if (shouldExclude(canonicalRoot, file)) continue

                val canonical = try {
                    file.canonicalFile
                } catch (_: IOException) {
                    continue
                }
                if (!canonical.toPath().startsWith(canonicalRoot.toPath())) continue

                sb.appendLine(canonical.path)
                entryCount++
                if (canonical.isDirectory) walk(canonical)
            }
        }

        sb.appendLine(canonicalRoot.path)
        walk(canonicalRoot)
        if (entryCount >= MAX_TREE_ENTRIES) {
            sb.appendLine("[project tree truncated at $MAX_TREE_ENTRIES entries]")
        }
        return ProjectTreeResult(sb.toString(), canonicalRoot)
    }

    private fun shouldExclude(root: File, file: File): Boolean {
        val relativePath = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return true
        if (relativePath.split('/').any { it.lowercase() in EXCLUDED_DIRECTORIES }) return true

        val name = file.name.lowercase()
        if (name in SENSITIVE_FILE_NAMES) return true
        if (SENSITIVE_SUFFIXES.any(name::endsWith)) return true
        return file.isFile && file.length() > MAX_INDEXED_FILE_BYTES
    }

    companion object {
        private const val MAX_TREE_ENTRIES = 2_500
        private const val MAX_INDEXED_FILE_BYTES = 2L * 1024L * 1024L
        private val EXCLUDED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", ".andrcs", "build", "out", "node_modules",
            ".externalnativebuild", ".cxx"
        )
        private val SENSITIVE_FILE_NAMES = setOf(
            ".env", "local.properties", "keystore.properties", "google-services.json",
            "service-account.json", "secrets.properties"
        )
        private val SENSITIVE_SUFFIXES = setOf(
            ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"
        )
    }
}

class ProjectTreeResult(
    val tree: String,
    private val root: File
) {

    fun getFileByName(filename: String): String? {
        return indexedFiles().firstOrNull { it.name == filename }?.canonicalPath
    }

    fun readFileContent(filename: String): String? {
        val path = getFileByName(filename) ?: return null
        return File(path).readText()
    }

    /** Returns deterministic source context bounded by file and total character budgets. */
    fun readRelevantFiles(
        maxFiles: Int = 32,
        maxTotalChars: Int = 64_000,
        maxFileChars: Int = 12_000
    ): Map<String, String> {
        if (maxFiles <= 0 || maxTotalChars <= 0 || maxFileChars <= 0) return emptyMap()

        val result = linkedMapOf<String, String>()
        var remaining = maxTotalChars
        indexedFiles()
            .filter(::isPromptSource)
            .sortedWith(compareBy<File>({ sourcePriority(it) }, { it.path }))
            .take(maxFiles)
            .forEach { file ->
                if (remaining <= 0) return@forEach
                try {
                    val content = file.readText().take(minOf(maxFileChars, remaining))
                    if (content.isNotEmpty()) {
                        result[file.canonicalPath] = content
                        remaining -= content.length
                    }
                } catch (_: Exception) {
                    // Ignore unreadable or concurrently removed files.
                }
            }
        return result
    }

    private fun indexedFiles(): Sequence<File> {
        val rootPath = root.toPath()
        return tree.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("[") }
            .map(::File)
            .filter { candidate ->
                try {
                    candidate.isFile && candidate.canonicalFile.toPath().startsWith(rootPath)
                } catch (_: IOException) {
                    false
                }
            }
    }

    private fun isPromptSource(file: File): Boolean {
        val name = file.name.lowercase()
        return name == "androidmanifest.xml" ||
            name == "settings.gradle" ||
            name == "settings.gradle.kts" ||
            name == "build.gradle" ||
            name == "build.gradle.kts" ||
            file.extension.lowercase() in SOURCE_EXTENSIONS
    }

    private fun sourcePriority(file: File): Int {
        val name = file.name.lowercase()
        return when {
            name == "androidmanifest.xml" -> 0
            name.startsWith("settings.gradle") -> 1
            name.startsWith("build.gradle") -> 2
            file.extension.lowercase() in setOf("kt", "java") -> 3
            else -> 4
        }
    }

    companion object {
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "java", "xml", "gradle", "kts", "toml", "pro", "properties"
        )
    }
}