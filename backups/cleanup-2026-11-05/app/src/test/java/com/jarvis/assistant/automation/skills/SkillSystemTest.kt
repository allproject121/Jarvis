package com.jarvis.assistant.automation.skills

import com.jarvis.assistant.automation.skills.builtin.AppOpenerSkill
import com.jarvis.assistant.automation.skills.builtin.DeviceInfoSkill
import com.jarvis.assistant.automation.skills.builtin.RepositoryAnalysisSkill
import com.jarvis.assistant.automation.skills.builtin.TaskManagementSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillSystemTest {

    @Test
    fun testSkillRegistry_RegistersBuiltInSkillsAndFindsTools() {
        val registry = SkillRegistry()

        val allSkills = registry.getAllSkills()
        assertTrue("Built-in skills should be registered", allSkills.size >= 4)

        val repoSkill = registry.findSkillForTool("analyze_code_repository")
        assertNotNull(repoSkill)
        assertTrue(repoSkill is RepositoryAnalysisSkill)
        assertEquals("repo_analysis_skill", repoSkill?.metadata?.id)

        val appSkill = registry.findSkillForTool("open_app")
        assertNotNull(appSkill)
        assertTrue(appSkill is AppOpenerSkill)

        val deviceSkill = registry.findSkillForTool("get_telemetry")
        assertNotNull(deviceSkill)
        assertTrue(deviceSkill is DeviceInfoSkill)

        val taskSkill = registry.findSkillForTool("cancel_active_task")
        assertNotNull(taskSkill)
        assertTrue(taskSkill is TaskManagementSkill)

        assertNull("Unknown tool should return null", registry.findSkillForTool("unknown_phantom_tool"))
    }

    @Test
    fun testSkillRegistry_DuplicateSkillRejection() {
        val registry = SkillRegistry()

        try {
            // Attempting to re-register repository analysis skill
            registry.registerSkill(RepositoryAnalysisSkill())
            fail("Expected IllegalArgumentException when registering duplicate skill ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("already registered") == true)
        }
    }

    @Test
    fun testSkillRegistry_EnableDisableLifecycle() {
        val registry = SkillRegistry()
        val skillId = "repo_analysis_skill"

        assertTrue("Skill should be enabled by default", registry.isSkillEnabled(skillId))
        assertTrue(registry.getEnabledSkills().any { it.metadata.id == skillId })

        registry.setSkillEnabled(skillId, false)
        assertFalse("Skill should be reported as disabled", registry.isSkillEnabled(skillId))
        assertFalse(registry.getEnabledSkills().any { it.metadata.id == skillId })

        registry.setSkillEnabled(skillId, true)
        assertTrue("Skill should be re-enabled", registry.isSkillEnabled(skillId))
        assertTrue(registry.getEnabledSkills().any { it.metadata.id == skillId })
    }

    @Test
    fun testRepositoryAnalysisSkill_ZipSlipPathTraversalBlocked() {
        val skill = RepositoryAnalysisSkill()

        val tempDir = File.createTempFile("repo_test_dest_", "")
        tempDir.delete()
        tempDir.mkdirs()

        val maliciousZip = File.createTempFile("malicious_", ".zip")

        try {
            // Create a zip with a path-traversal entry targeting outside tempDir
            ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
                val entry = ZipEntry("../evil_traversal.txt")
                zos.putNextEntry(entry)
                zos.write("malicious payload".toByteArray())
                zos.closeEntry()
            }

            try {
                skill.unzipWithSecurityCheck(maliciousZip, tempDir)
                fail("Expected SecurityException on Zip-Slip path traversal entry")
            } catch (e: SecurityException) {
                assertTrue(
                    "Error message should mention Zip-Slip: ${e.message}",
                    e.message?.contains("Zip-Slip") == true
                )
            }

            val outsideFile = File(tempDir.parentFile, "evil_traversal.txt")
            assertFalse("Malicious file must not be created outside destination dir", outsideFile.exists())
        } finally {
            maliciousZip.delete()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRepositoryAnalysisSkill_ZipBombEntryCountExceededBlocked() {
        val skill = RepositoryAnalysisSkill()

        val tempDir = File.createTempFile("zipbomb_dest_", "")
        tempDir.delete()
        tempDir.mkdirs()

        val bombZip = File.createTempFile("zipbomb_", ".zip")

        try {
            // Create a zip with 1005 files (limit is 1000)
            ZipOutputStream(FileOutputStream(bombZip)).use { zos ->
                for (i in 1..1005) {
                    val entry = ZipEntry("file_$i.txt")
                    zos.putNextEntry(entry)
                    zos.write(byteArrayOf(0x41))
                    zos.closeEntry()
                }
            }

            try {
                skill.unzipWithSecurityCheck(bombZip, tempDir)
                fail("Expected SecurityException on Zip-Bomb file count limit")
            } catch (e: SecurityException) {
                assertTrue(
                    "Error message should mention too many entries: ${e.message}",
                    e.message?.contains("too many entries") == true
                )
            }
        } finally {
            bombZip.delete()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRepositoryAnalysisSkill_SecretRedaction() {
        val raw1 = "google_api_key = AIzaSyD987fakeKeyForTesting123456789"
        val redacted1 = RepositoryAnalysisSkill.redactSecrets(raw1)
        assertFalse("AIza key should not be present", redacted1.contains("AIzaSyD987fakeKeyForTesting123456789"))
        assertTrue("Should contain redacted token", redacted1.contains("[REDACTED_SECRET]"))

        val raw2 = "openai_token: sk-abcdefghijklmnopqrstuvwxyz123456"
        val redacted2 = RepositoryAnalysisSkill.redactSecrets(raw2)
        assertFalse("sk- token should not be present", redacted2.contains("sk-abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue("Should contain redacted token", redacted2.contains("[REDACTED_SECRET]"))

        val raw3 = "aws_key: AKIAIOSFODNN7EXAMPLE"
        val redacted3 = RepositoryAnalysisSkill.redactSecrets(raw3)
        assertFalse("AKIA key should not be present", redacted3.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue("Should contain redacted token", redacted3.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun testSkillMetadata_PropertiesAndRiskLevels() {
        val repoSkill = RepositoryAnalysisSkill()
        assertEquals(SkillCategory.DEVELOPER_TOOLS, repoSkill.metadata.category)
        assertEquals(SkillRiskLevel.SAFE, repoSkill.metadata.riskLevel)
        assertFalse(repoSkill.metadata.isNonIdempotent)
        assertTrue(repoSkill.metadata.supportedTools.contains("analyze_code_repository"))
        assertTrue(repoSkill.metadata.toolSchemas.isNotEmpty())

        val appSkill = AppOpenerSkill()
        assertEquals(SkillCategory.APP_AUTOMATION, appSkill.metadata.category)
        assertEquals(SkillRiskLevel.MODERATE, appSkill.metadata.riskLevel)
        assertTrue(appSkill.metadata.supportedTools.contains("open_app"))

        val taskSkill = TaskManagementSkill()
        assertEquals(SkillCategory.TASK_MANAGEMENT, taskSkill.metadata.category)
        assertEquals(SkillRiskLevel.SAFE, taskSkill.metadata.riskLevel)
        assertTrue(taskSkill.metadata.supportedTools.contains("get_active_task"))
        assertTrue(taskSkill.metadata.supportedTools.contains("cancel_active_task"))

        val deviceSkill = DeviceInfoSkill()
        assertEquals(SkillCategory.DEVICE_INFO, deviceSkill.metadata.category)
        assertEquals(SkillRiskLevel.SAFE, deviceSkill.metadata.riskLevel)
        assertTrue(deviceSkill.metadata.supportedTools.contains("get_telemetry"))
    }

    @Test
    fun testRepositoryAnalysisSkill_ExtractsCleanMockProjectDetails() {
        val tempDir = File.createTempFile("mock_project_", "")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            // Create mock build.gradle
            val gradleFile = File(tempDir, "build.gradle")
            gradleFile.writeText(
                """
                plugins {
                    id 'com.android.application'
                }
                dependencies {
                    implementation 'androidx.core:core-ktx:1.12.0'
                    implementation 'com.google.android.material:material:1.11.0'
                }
                """.trimIndent()
            )

            // Create mock package.json
            val pkgJson = File(tempDir, "package.json")
            pkgJson.writeText(
                """
                {
                  "name": "mock-service",
                  "version": "1.0.0",
                  "dependencies": {
                    "express": "^4.18.2"
                  }
                }
                """.trimIndent()
            )

            // Create mock MainActivity.kt entry point
            val mainActivity = File(tempDir, "MainActivity.kt")
            mainActivity.writeText("package com.test\nclass MainActivity")

            // Create a mock zip
            val projectZip = File.createTempFile("mock_repo_", ".zip")
            val destExtract = File.createTempFile("dest_extract_", "")
            destExtract.delete()
            destExtract.mkdirs()

            try {
                ZipOutputStream(FileOutputStream(projectZip)).use { zos ->
                    val entry1 = ZipEntry("build.gradle")
                    zos.putNextEntry(entry1)
                    zos.write(gradleFile.readBytes())
                    zos.closeEntry()

                    val entry2 = ZipEntry("package.json")
                    zos.putNextEntry(entry2)
                    zos.write(pkgJson.readBytes())
                    zos.closeEntry()

                    val entry3 = ZipEntry("MainActivity.kt")
                    zos.putNextEntry(entry3)
                    zos.write(mainActivity.readBytes())
                    zos.closeEntry()
                }

                val skill = RepositoryAnalysisSkill()
                skill.unzipWithSecurityCheck(projectZip, destExtract)

                assertTrue(File(destExtract, "build.gradle").exists())
                assertTrue(File(destExtract, "package.json").exists())
                assertTrue(File(destExtract, "MainActivity.kt").exists())
            } finally {
                projectZip.delete()
                destExtract.deleteRecursively()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
