package com.tom.rv2ide.artificial.project.awareness

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectDataTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `project context excludes generated output and secrets`() {
    val project = temporaryFolder.newFolder("project")
    val source = File(project, "app/src/main/java/example/Main.kt").apply {
      parentFile?.mkdirs()
      writeText("class Main")
    }
    File(project, "app/build/generated.kt").apply {
      parentFile?.mkdirs()
      writeText("class Generated")
    }
    File(project, "local.properties").writeText("sdk.dir=/private/sdk")
    File(project, ".env").writeText("TOKEN=secret")

    val result = ProjectData().showProjectTree(project)

    assertTrue(result.tree.contains(source.canonicalPath))
    assertFalse(result.tree.contains("generated.kt"))
    assertFalse(result.tree.contains("local.properties"))
    assertFalse(result.tree.contains(".env"))
    assertEquals(mapOf(source.canonicalPath to "class Main"), result.readRelevantFiles())
  }

  @Test
  fun `project context respects aggregate character budget`() {
    val project = temporaryFolder.newFolder("budget")
    repeat(4) { index ->
      File(project, "Source$index.kt").writeText("x".repeat(30))
    }

    val context =
        ProjectData()
            .showProjectTree(project)
            .readRelevantFiles(maxFiles = 4, maxTotalChars = 50, maxFileChars = 30)

    assertEquals(50, context.values.sumOf(String::length))
  }
}
