/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.services.builder

import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.slf4j.LoggerFactory

/**
 * Persists raw build output inside the opened project, independently from the editor UI.
 *
 * The latest build is always available at `.andrcs/logs/build-out.txt`.
 */
internal class ProjectBuildLog(private val projectRoot: () -> File?) : Closeable {

  private val lock = Any()
  private var writer: BufferedWriter? = null

  val file: File?
    get() = projectRoot()?.let { File(it, LOG_PATH) }

  fun start(buildInfo: BuildInfo) {
    synchronized(lock) {
      closeWriter()
      val outputFile = file ?: return
      try {
        outputFile.parentFile?.mkdirs()
        writer = outputFile.bufferedWriter()
        writeLine("# AndroidCodeStudio build output")
        writeLine("# Started: ${timestamp()}")
        writeLine("# Tasks: ${buildInfo.tasks.joinToString(" ")}")
        writeLine()
      } catch (error: Exception) {
        log.warn("Unable to create project build log at {}", outputFile, error)
        closeWriter()
      }
    }
  }

  fun append(line: String?) {
    if (line == null) return
    synchronized(lock) {
      try {
        writeLine(line)
        writer?.flush()
      } catch (error: Exception) {
        log.warn("Unable to append to project build log", error)
        closeWriter()
      }
    }
  }

  fun finish(successful: Boolean) {
    synchronized(lock) {
      try {
        writeLine()
        writeLine("# Finished: ${timestamp()}")
        writeLine("# Result: ${if (successful) "SUCCESS" else "FAILED"}")
        writer?.flush()
      } catch (error: Exception) {
        log.warn("Unable to finish project build log", error)
      } finally {
        closeWriter()
      }
    }
  }

  override fun close() {
    synchronized(lock) { closeWriter() }
  }

  private fun writeLine(value: String = "") {
    writer?.apply {
      write(value)
      newLine()
    }
  }

  private fun closeWriter() {
    try {
      writer?.close()
    } catch (error: Exception) {
      log.debug("Unable to close project build log", error)
    } finally {
      writer = null
    }
  }

  private fun timestamp(): String {
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())
  }

  companion object {
    const val LOG_PATH = ".andrcs/logs/build-out.txt"
    private val log = LoggerFactory.getLogger(ProjectBuildLog::class.java)
  }
}
