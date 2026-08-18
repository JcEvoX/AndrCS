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

package com.tom.rv2ide.setup.servers.kotlin

import android.content.Context
import com.tom.rv2ide.setup.servers.ILanguageServerInstaller
import com.tom.rv2ide.utils.Environment
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Kotlin(private val context: Context) : ILanguageServerInstaller {
  
  companion object {
    private const val SERVER_ID = "Kotlin"
    private const val MANIFEST_UPSTREAM = "https://raw.githubusercontent.com/AndroidCSOfficial/acs-language-servers/refs/heads/main/servers-manifest.json"
    private const val MANIFEST_JSDELIVR = "https://cdn.jsdelivr.net/gh/AndroidCSOfficial/acs-language-servers@main/servers-manifest.json"
    private const val MANIFEST_FASTLY = "https://fastly.jsdelivr.net/gh/AndroidCSOfficial/acs-language-servers@main/servers-manifest.json"

    private fun manifestCandidates(selfHosted: List<String> = emptyList()): List<String> =
        buildList {
          addAll(selfHosted)
          add(MANIFEST_UPSTREAM)
          add(MANIFEST_JSDELIVR)
          add(MANIFEST_FASTLY)
        }.distinct()

    private fun fetchTextWithFallback(urls: List<String>): String {
      require(urls.isNotEmpty()) { "No URLs provided" }
      val errors = mutableListOf<String>()
      for (u in urls) {
        try {
          val c = URL(u).openConnection().apply {
            connectTimeout = 20_000; readTimeout = 45_000
            setRequestProperty("User-Agent", "AndrCS-LSP/1.0")
          }
          val body = c.getInputStream().bufferedReader().use { it.readText() }
          if (body.isBlank()) { errors += "[$u] empty body"; continue }
          val first = body.firstOrNull { !it.isWhitespace() }
          if (first != '{') { errors += "[$u] non-JSON body (likely 429 HTML)"; continue }
          return body
        } catch (e: Exception) { errors += "[$u] ${e.javaClass.simpleName}: ${e.message}" }
      }
      throw IOException("All ${urls.size} LSP manifest mirrors failed:\n" + errors.joinToString("\n") { "  - $it" })
    }
  }
  
  override fun isInstalled(): Boolean {
    val kotlinBinary = File(Environment.SERVERS_KOTLIN_DIR, "bin/kotlin-language-server")
    val atLeastOneJar = File(Environment.SERVERS_KOTLIN_DIR, "lib/kotlin-stdlib-2.1.0.jar")
    return kotlinBinary.exists() && atLeastOneJar.exists()
  }
  
  override fun install(onOutput: (String) -> Unit): Boolean {
    return try {
      onOutput("Fetching Kotlin language server information...")
      
      val json = fetchTextWithFallback(manifestCandidates())
      val jsonObject = JSONObject(json)
      val serversArray = jsonObject.getJSONArray("Servers")
      
      var downloadLink: String? = null
      var version: String? = null
      
      for (i in 0 until serversArray.length()) {
        val server = serversArray.getJSONObject(i)
        if (server.getString("id") == SERVER_ID) {
          downloadLink = server.getString("link")
          version = server.getString("version")
          if (downloadLink == "null") {
            downloadLink = null
          }
          break
        }
      }
      
      if (downloadLink == null) {
        onOutput("\nError: No download link available for Kotlin language server")
        onOutput("Server ID searched: $SERVER_ID")
        return false
      }
      
      onOutput("Found Kotlin language server version: $version")
      onOutput("Download URL: $downloadLink")
      
      val serversDir = File(Environment.HOME, "acs/servers")
      serversDir.mkdirs()
      
      val serverDir = File(serversDir, SERVER_ID.lowercase())
      serverDir.mkdirs()
      
      onOutput("\nConnecting to download server...")
      
      val connection = URL(downloadLink).openConnection()
      connection.connect()
      
      val fileLength = connection.contentLength
      val inputStream = connection.getInputStream()
      
      val tempFile = File(serversDir, "temp_${SERVER_ID}.zip")
      val outputStream = FileOutputStream(tempFile)
      
      onOutput("Downloading Kotlin language server...")
      if (fileLength > 0) {
        onOutput("File size: ${fileLength / 1024 / 1024} MB")
      }
      
      val buffer = ByteArray(8192)
      var totalRead = 0L
      var len: Int
      var lastProgress = 0
      
      while (inputStream.read(buffer).also { len = it } > 0) {
        outputStream.write(buffer, 0, len)
        totalRead += len
        
        if (fileLength > 0) {
          val progress = ((totalRead * 100) / fileLength).toInt()
          if (progress != lastProgress && progress % 10 == 0) {
            onOutput("Download progress: $progress%")
            lastProgress = progress
          }
        }
      }
      
      outputStream.close()
      inputStream.close()
      
      onOutput("\nDownload completed!")
      onOutput("Extracting files...")
      
      val zipInputStream = ZipInputStream(tempFile.inputStream())
      var zipEntry = zipInputStream.nextEntry
      var extractedCount = 0
      
      while (zipEntry != null) {
        val file = File(serverDir, zipEntry.name)
        
        if (zipEntry.isDirectory) {
          file.mkdirs()
        } else {
          file.parentFile?.mkdirs()
          val fileOutputStream = FileOutputStream(file)
          val extractBuffer = ByteArray(8192)
          var extractLen: Int
          while (zipInputStream.read(extractBuffer).also { extractLen = it } > 0) {
            fileOutputStream.write(extractBuffer, 0, extractLen)
          }
          fileOutputStream.close()
          
          if (file.extension.isEmpty() || file.name.endsWith(".sh")) {
            file.setExecutable(true)
          }
          
          extractedCount++
          
          if (extractedCount % 50 == 0) {
            onOutput("Extracted $extractedCount files...")
          }
        }
        
        zipInputStream.closeEntry()
        zipEntry = zipInputStream.nextEntry
      }
      
      zipInputStream.close()
      tempFile.delete()
      
      onOutput("\nExtraction completed!")
      onOutput("Total files extracted: $extractedCount")
      
      onOutput("\nVerifying installation...")
      if (isInstalled()) {
        onOutput("Kotlin language server binaries found!")
        true
      } else {
        onOutput("Installation verification failed. Server binaries not found.")
        false
      }
      
    } catch (e: Exception) {
      onOutput("\nError during installation: ${e.message}")
      e.printStackTrace()
      false
    }
  }
}