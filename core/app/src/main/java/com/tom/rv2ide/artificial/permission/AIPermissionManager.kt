/*
 * This file is part of AndroidCodeStudio.
 * 
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.permissions

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class AIPermissionManager(private val context: Context) {

    private val prefs: SharedPreferences = 
        context.getSharedPreferences("ai_permissions", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FILE_WRITE_ENABLED = "file_write_enabled"
        private const val KEY_ALLOWED_DIRECTORIES = "allowed_directories"
        private const val KEY_REQUIRE_CONFIRMATION = "require_confirmation"
        private const val KEY_AUTO_BACKUP = "auto_backup"
    }

    /**
     * Checks if file writing is enabled in the application's preferences.
     *
     * @return `true` if file writing is enabled, `false` otherwise.
     */
    // Check if AI has permission to write files
    fun isFileWriteEnabled(): Boolean {
        return prefs.getBoolean(KEY_FILE_WRITE_ENABLED, false)
    }

    /**
     * Enables or disables file writing to persistent storage.
     *
     * @param enabled `true` to enable file writing, `false` to disable.
     */
    // Enable/disable file writing
    fun setFileWriteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILE_WRITE_ENABLED, enabled).apply()
    }

    /**
     * Checks if confirmation is required before writing data.
     * @return `true` if confirmation is required, `false` otherwise.
     */
    // Check if confirmation is required before writing
    fun requiresConfirmation(): Boolean {
        return prefs.getBoolean(KEY_REQUIRE_CONFIRMATION, true)
    }

    /**
     * Sets whether confirmation is required for certain actions.
     *
     * @param required `true` if confirmation should be required, `false` otherwise.
     */
    // Set confirmation requirement
    fun setRequireConfirmation(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_CONFIRMATION, required).apply()
    }

    /**
     * Checks if auto backup is enabled in preferences.
     * @return `true` if auto backup is enabled, `false` otherwise.
     */
    // Check if auto backup is enabled
    fun isAutoBackupEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_BACKUP, true)
    }

    /**
     * Sets whether automatic backups are enabled.
     *
     * @param enabled `true` to enable automatic backups, `false` to disable.
     */
    // Set auto backup
    fun setAutoBackup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    /**
     * Retrieves the set of allowed directories from preferences.
     * @return A set of strings representing the allowed directories. Returns an empty set if no directories are allowed.
     */
    // Get allowed directories
    fun getAllowedDirectories(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_DIRECTORIES, setOf()) ?: setOf()
    }

    /**
     * Adds a directory to the list of allowed directories.
     *
     * @param path The path of the directory to add.
     */
    // Add allowed directory
    fun addAllowedDirectory(path: String) {
        val current = getAllowedDirectories().toMutableSet()
        val canonicalPath = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            return
        }
        current.add(canonicalPath)
        prefs.edit().putStringSet(KEY_ALLOWED_DIRECTORIES, current).apply()
    }

    /**
     * Removes a directory from the list of allowed directories.
     *
     * @param path The path of the directory to remove.
     */
    // Remove allowed directory
    fun removeAllowedDirectory(path: String) {
        val current = getAllowedDirectories().toMutableSet()
        val canonicalPath = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            path
        }
        current.remove(canonicalPath)
        prefs.edit().putStringSet(KEY_ALLOWED_DIRECTORIES, current).apply()
    }

    /**
     * Checks if the given file path is allowed based on configured write settings and allowed directories.
     *
     * @param filePath The file path to check.
     * @return `true` if the path is allowed, `false` otherwise.
     */
    // Check if a file path is allowed
    fun isPathAllowed(filePath: String): Boolean {
        if (!isFileWriteEnabled()) return false
        
        val allowedDirs = getAllowedDirectories()
        if (allowedDirs.isEmpty()) return false

        val candidate = try {
            File(filePath).canonicalFile.toPath()
        } catch (_: Exception) {
            return false
        }

        return allowedDirs.any { allowedDir ->
            try {
                val root = File(allowedDir).canonicalFile
                root.isDirectory && candidate.startsWith(root.toPath())
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Clears all stored permissions from shared preferences.
     */
    // Clear all permissions
    fun clearAllPermissions() {
        prefs.edit().clear().apply()
    }
}
