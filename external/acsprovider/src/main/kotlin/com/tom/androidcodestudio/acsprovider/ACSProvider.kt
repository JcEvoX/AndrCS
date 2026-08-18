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
package com.tom.androidcodestudio.acsprovider

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tom.androidcodestudio.acsprovider.models.ACSConfig
import com.tom.androidcodestudio.acsprovider.models.PackageEntry
import com.tom.androidcodestudio.acsprovider.utils.DownloadCallback
import com.tom.androidcodestudio.acsprovider.utils.HashUtils
import com.tom.rv2ide.utils.Environment.TMP_DIR as TMPDIR
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory

/**
 * Android Code Studio Build System Provider Download and manage build system packages
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class ACSProvider(
    private val downloadDir: File,
    private val tempDir: File = TMPDIR,
    enableLogging: Boolean = false,
) {

  private val logger = LoggerFactory.getLogger(ACSProvider::class.java)
  private val gson = Gson()

  private val client: OkHttpClient =
      OkHttpClient.Builder()
          .connectTimeout(30, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .writeTimeout(120, TimeUnit.SECONDS)
          .followRedirects(true)
          .followSslRedirects(true)
          .retryOnConnectionFailure(true)
          .apply {
            if (enableLogging) {
              val logging =
                  HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
              addInterceptor(logging)
            }
          }
          .build()

  init {
    if (!downloadDir.exists()) {
      downloadDir.mkdirs()
    }
    if (!tempDir.exists()) {
      tempDir.mkdirs()
    }
  }

  /** Download JSON manifest from URL */
  suspend fun downloadJson(url: String, silent: Boolean = false): String =
      downloadJson(listOf(url), silent)

  /** Download JSON manifest, trying multiple mirror URLs with fallback.
   *  Each candidate is checked: HTTP success, non-empty body, and the first non-whitespace
   *  character MUST be '{' (JSON object).  Anything else (e.g. 429 HTML page) is treated as
   *  a mirror failure and the next candidate is tried. */
  suspend fun downloadJson(urls: List<String>, silent: Boolean = false): String =
      withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
          throw IllegalArgumentException("No JSON manifest URLs provided")
        }

        val errors = mutableListOf<String>()
        for ((index, url) in urls.withIndex()) {
          if (!silent) {
            logger.info(
                "Downloading JSON from [{}/{}]: {}",
                index + 1,
                urls.size,
                url,
            )
          }
          val request = Request.Builder().url(url).get().build()
          try {
            client.newCall(request).execute().use { response ->
              if (!response.isSuccessful) {
                errors += "[$url] HTTP ${response.code}"
                logger.warn("JSON mirror failed [{}]: HTTP {}", url, response.code)
                return@use
              }
              val body =
                  response.body?.string()
                      ?: throw IOException("[$url] Downloaded JSON content is empty")
              if (body.isEmpty()) {
                errors += "[$url] Empty body"
                logger.warn("JSON mirror failed [{}]: empty body", url)
                return@use
              }
              val first = body.firstOrNull { !it.isWhitespace() }
              if (first != '{') {
                errors +=
                    "[$url] Body does not start with JSON object (got '${first.take(1)}'). " +
                        "Likely an HTML rate-limit / error page from the mirror."
                logger.warn(
                    "JSON mirror failed [{}]: non-JSON body prefix {}",
                    url,
                    body.take(80).replace("\n", "\\n"),
                )
                return@use
              }
              return@withContext body
            }
          } catch (e: IOException) {
            errors += "[$url] ${e.javaClass.simpleName}: ${e.message}"
            logger.warn("JSON mirror failed [{}] with exception", url, e)
          }
        }

        throw IOException(
            "All ${urls.size} JSON manifest URLs failed.\n" +
                errors.joinToString("\n") { "  - $it" },
        )
      }

  private fun resolveJsonUrls(config: ACSConfig): List<String> {
    return buildList {
      addAll(config.jsonUrlCandidates)
      if (!config.jsonUrl.isNullOrEmpty()) add(config.jsonUrl)
    }.distinct()
  }

  private fun resolvePackageUrls(entry: PackageEntry, config: ACSConfig): List<String> {
    return buildList {
      addAll(config.packageUrlCandidates)
      add(entry.url)
    }.distinct()
  }

  private fun resolveDirectUrls(config: ACSConfig): List<String> {
    return buildList {
      addAll(config.directUrlCandidates)
      if (!config.directUrl.isNullOrEmpty()) add(config.directUrl)
    }.distinct()
  }

  /** Parse JSON and find package entry matching criteria */
  fun findPackageEntry(jsonContent: String, config: ACSConfig): PackageEntry {
    val jsonObject = JsonParser.parseString(jsonContent).asJsonObject

    if (!jsonObject.has("packages") || !jsonObject.get("packages").isJsonArray) {
      throw IllegalArgumentException("JSON data does not contain 'packages' array")
    }

    val packages = jsonObject.getAsJsonArray("packages")
    val matchingPackages = mutableListOf<PackageEntry>()

    // First pass: find packages matching architecture and ID
    for (i in 0 until packages.size()) {
      val entry = packages[i].asJsonObject

      val archMatch =
          entry.has("architecture") && entry.get("architecture").asString == config.architecture

      val idMatch =
          config.packageId.isNullOrEmpty() ||
              (entry.has("id") && entry.get("id").asString == config.packageId)

      if (archMatch && idMatch) {
        matchingPackages.add(parsePackageEntry(entry))
      }
    }

    if (matchingPackages.isEmpty()) {
      var errorMsg = "Architecture '${config.architecture}'"
      if (!config.packageId.isNullOrEmpty()) {
        errorMsg += " with ID '${config.packageId}'"
      }
      errorMsg += " not found in packages"
      throw IllegalArgumentException(errorMsg)
    }

    // Second pass: filter by version if specified
    if (!config.version.isNullOrEmpty()) {
      matchingPackages
          .find { it.version == config.version }
          ?.let {
            return it
          }

      var errorMsg =
          "Version '${config.version}' not found for architecture '${config.architecture}'"
      if (!config.packageId.isNullOrEmpty()) {
        errorMsg += " with ID '${config.packageId}'"
      }
      throw IllegalArgumentException(errorMsg)
    }

    // Return first match if no version specified
    return matchingPackages[0]
  }

  private fun parsePackageEntry(jsonObject: JsonObject): PackageEntry {
    return PackageEntry(
        id = jsonObject.get("id").asString,
        architecture = jsonObject.get("architecture").asString,
        version = jsonObject.get("version").asString,
        filename = jsonObject.get("filename").asString,
        url = jsonObject.get("url").asString,
        sha256 = if (jsonObject.has("sha256")) jsonObject.get("sha256").asString else null,
    )
  }

  /** List available versions for given architecture and ID */
  fun listAvailableVersions(
      jsonContent: String,
      architecture: String,
      packageId: String? = null,
  ): List<String> {
    val jsonObject = JsonParser.parseString(jsonContent).asJsonObject

    if (!jsonObject.has("packages") || !jsonObject.get("packages").isJsonArray) {
      throw IllegalArgumentException("JSON data does not contain 'packages' array")
    }

    val packages = jsonObject.getAsJsonArray("packages")
    val versions = mutableSetOf<String>()

    for (i in 0 until packages.size()) {
      val entry = packages[i].asJsonObject

      val archMatch =
          entry.has("architecture") && entry.get("architecture").asString == architecture

      val idMatch =
          packageId.isNullOrEmpty() || (entry.has("id") && entry.get("id").asString == packageId)

      if (archMatch && idMatch && entry.has("version")) {
        versions.add(entry.get("version").asString)
      }
    }

    return versions.sorted()
  }

  /** Get specific field from package entry */
  suspend fun getField(config: ACSConfig): String =
      withContext(Dispatchers.IO) {
        val jsonUrls = resolveJsonUrls(config)
        if (jsonUrls.isEmpty()) {
          throw IllegalArgumentException("JSON URL is required")
        }
        if (config.getField.isNullOrEmpty()) {
          throw IllegalArgumentException("Field name is required")
        }

        val jsonContent = downloadJson(jsonUrls, silent = true)
        val entry = findPackageEntry(jsonContent, config)

        when (config.getField) {
          "id" -> entry.id
          "architecture" -> entry.architecture
          "version" -> entry.version
          "filename" -> entry.filename
          "url" -> entry.url
          "sha256" -> entry.sha256 ?: ""
          else -> throw IllegalArgumentException("Unknown field: ${config.getField}")
        }
      }

  /** Download file from URL with progress tracking */
  suspend fun downloadFile(
      url: String,
      outputFile: File,
      callback: DownloadCallback? = null,
  ): Boolean =
      withContext(Dispatchers.IO) {
        try {
          logger.info("Downloading: {}", url)
          logger.info("Output: {}", outputFile.absolutePath)

          val request = Request.Builder().url(url).get().build()

          client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
              throw IOException("Failed to download file. HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Response body is null")
            val contentLength = body.contentLength()

            FileOutputStream(outputFile).use { output ->
              val buffer = ByteArray(8192)
              var bytesRead: Int
              var totalBytesRead: Long = 0

              body.byteStream().use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                  output.write(buffer, 0, bytesRead)
                  totalBytesRead += bytesRead

                  if (contentLength > 0) {
                    val percentage = ((totalBytesRead * 100) / contentLength).toInt()
                    callback?.onProgress(totalBytesRead, contentLength, percentage)
                  }
                }
              }
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
              outputFile.delete()
              throw IOException("Downloaded file is empty or does not exist")
            }

            callback?.onComplete(outputFile)
            true
          }
        } catch (e: Exception) {
          callback?.onError(e)
          if (outputFile.exists()) {
            outputFile.delete()
          }
          throw e
        }
      }

  /** Download a single file, trying a list of mirror URLs. If SHA256 is provided, the
   *  successful URL's output must match it — otherwise we continue trying mirrors. */
  private suspend fun downloadFileWithFallback(
      urls: List<String>,
      outputFile: File,
      callback: DownloadCallback? = null,
      expectedSha256: String? = null,
  ) {
    require(urls.isNotEmpty()) { "No package download URLs provided" }

    val errors = mutableListOf<String>()
    for ((index, url) in urls.withIndex()) {
      logger.info(
          "Downloading package [{}/{}]: {} -> {}",
          index + 1,
          urls.size,
          url,
          outputFile.absolutePath,
      )
      try {
        val ok = downloadFile(url, outputFile, callback)
        if (!ok || !outputFile.exists() || outputFile.length() == 0L) {
          errors += "[$url] downloadFile returned false / empty file"
          if (outputFile.exists()) outputFile.delete()
          continue
        }
        if (expectedSha256 != null) {
          val actualHash = HashUtils.calculateSHA256(outputFile)
          logger.info("Expected SHA256: {}", expectedSha256)
          logger.info("Actual   SHA256: {}", actualHash)
          if (actualHash.equals(expectedSha256, ignoreCase = true)) {
            logger.info("SHA256 verification: PASSED")
            return
          } else {
            errors +=
                "[$url] SHA256 mismatch (expected $expectedSha256, got $actualHash). " +
                    "Treating as bad mirror/corrupt payload and falling back."
            logger.warn("Package mirror failed [{}]: sha256 mismatch", url)
            outputFile.delete()
            continue
          }
        }
        return
      } catch (e: Exception) {
        errors += "[$url] ${e.javaClass.simpleName}: ${e.message}"
        logger.warn("Package mirror failed [{}] with exception", url, e)
        if (outputFile.exists()) outputFile.delete()
      }
    }

    throw IOException(
        "All ${urls.size} package mirrors failed for ${outputFile.name}.\n" +
            errors.joinToString("\n") { "  - $it" },
    )
  }

  /** Download package based on config */
  suspend fun downloadPackage(config: ACSConfig, callback: DownloadCallback? = null): File =
      withContext(Dispatchers.IO) {
        val directUrls = resolveDirectUrls(config)
        if (directUrls.isNotEmpty()) {
          val filename =
              directUrls
                  .first()
                  .substringAfterLast('/')
                  .ifEmpty { "downloaded_file" }
          val outputFile = File(downloadDir, filename)
          downloadFileWithFallback(directUrls, outputFile, callback, expectedSha256 = null)
          return@withContext outputFile
        }

        // JSON manifest download (manifest candidates, then package URL candidates)
        val jsonUrls = resolveJsonUrls(config)
        if (jsonUrls.isEmpty()) {
          throw IllegalArgumentException("JSON URL is required")
        }

        val jsonContent = downloadJson(jsonUrls, silent = false)
        val entry = findPackageEntry(jsonContent, config)

        val outputFile = File(downloadDir, entry.filename)
        val packageUrls = resolvePackageUrls(entry, config)
        downloadFileWithFallback(packageUrls, outputFile, callback, expectedSha256 = entry.sha256)

        if (entry.sha256 == null) {
          logger.warn("No SHA256 checksum available for verification")
        }

        outputFile
      }

  /** Execute operation based on config */
  suspend fun execute(config: ACSConfig, callback: DownloadCallback? = null): Any =
      withContext(Dispatchers.IO) {
        if (config.shouldDownload) {
          return@withContext downloadPackage(config, callback)
        }

        if (!config.getField.isNullOrEmpty()) {
          return@withContext getField(config)
        }

        val directUrls = resolveDirectUrls(config)
        if (directUrls.isNotEmpty()) {
          return@withContext directUrls.first()
        }

        throw IllegalArgumentException(
            "Invalid configuration. Specify either download, getField, or directUrl"
        )
      }
}
