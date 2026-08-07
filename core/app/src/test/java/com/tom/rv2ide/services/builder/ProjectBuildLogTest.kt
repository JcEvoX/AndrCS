package com.tom.rv2ide.services.builder

import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectBuildLogTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `writes complete build output under project metadata directory`() {
    val project = temporaryFolder.newFolder("project")
    val buildLog = ProjectBuildLog { project }

    buildLog.start(BuildInfo(listOf(":app:assembleDebug")))
    buildLog.append("> Task :app:compileDebugKotlin")
    buildLog.append("BUILD SUCCESSFUL")
    buildLog.finish(successful = true)

    val outputFile = File(project, ProjectBuildLog.LOG_PATH)
    val output = outputFile.readText()

    assertEquals(outputFile.canonicalFile, buildLog.file?.canonicalFile)
    assertTrue(output.contains("# Tasks: :app:assembleDebug"))
    assertTrue(output.contains("> Task :app:compileDebugKotlin"))
    assertTrue(output.contains("BUILD SUCCESSFUL"))
    assertTrue(output.contains("# Result: SUCCESS"))
  }

  @Test
  fun `new build replaces stale output`() {
    val project = temporaryFolder.newFolder("replacement")
    val buildLog = ProjectBuildLog { project }

    buildLog.start(BuildInfo(listOf("oldTask")))
    buildLog.append("stale")
    buildLog.finish(successful = false)
    buildLog.start(BuildInfo(listOf("newTask")))
    buildLog.append("fresh")
    buildLog.finish(successful = true)

    val output = File(project, ProjectBuildLog.LOG_PATH).readText()
    assertTrue(output.contains("newTask"))
    assertTrue(output.contains("fresh"))
    assertTrue(!output.contains("stale"))
  }
}
