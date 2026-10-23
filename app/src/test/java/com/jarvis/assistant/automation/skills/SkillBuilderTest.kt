package com.jarvis.assistant.automation.skills

import com.jarvis.assistant.automation.skills.builtin.LocalProposal
import com.jarvis.assistant.automation.skills.builtin.RepositoryAnalysisSkill
import com.jarvis.assistant.automation.skills.builtin.SkillBuilderIntegrationSkill
import com.jarvis.assistant.automation.skills.builtin.SpoonKnifeDemoScreenSkill
import com.jarvis.assistant.automation.skills.companion.CompanionApiClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket

class SkillBuilderTest {

    @Test
    fun testSkillBuilderMetadata_HasHighRiskAndRequiredTools() {
        val skill = SkillBuilderIntegrationSkill()
        val meta = skill.metadata

        assertEquals("skill_builder_integration_skill", meta.id)
        assertEquals(SkillCategory.DEVELOPER_TOOLS, meta.category)
        assertEquals(SkillRiskLevel.HIGH_RISK, meta.riskLevel)
        assertTrue(meta.isNonIdempotent)

        assertTrue(meta.supportedTools.contains("inspect_integration_points"))
        assertTrue(meta.supportedTools.contains("generate_integration_proposal"))
        assertTrue(meta.supportedTools.contains("review_integration_diff"))
        assertTrue(meta.supportedTools.contains("apply_integration_proposal"))
        assertTrue(meta.supportedTools.contains("rollback_integration"))
    }

    @Test
    fun testSkillBuilder_ApprovalGateBlocksMissingOrInvalidToken() = runBlocking {
        val skill = SkillBuilderIntegrationSkill()

        // 1. Missing approval token must be BLOCKED
        val missingTokenArgs = JSONObject().apply {
            put("proposal_id", "dummy_prop_123")
            put("approval_token", "")
        }
        val missingContext = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_missing_tok",
            toolName = "apply_integration_proposal",
            arguments = missingTokenArgs
        )
        val missingResult = skill.execute(missingContext)
        assertEquals(SkillExecutionStatus.BLOCKED, missingResult.status)
        assertTrue(missingResult.userFacingMessage.contains("approval_token") || missingResult.userFacingMessage.contains("Security Barrier"))

        // 2. Mismatched / invalid token must be BLOCKED
        val propId = "test_prop_gate_${System.currentTimeMillis()}"
        SkillBuilderIntegrationSkill.localProposals[propId] = LocalProposal(
            proposalId = propId,
            approvalToken = "correct_secret_token_123",
            featureName = "TestFeature",
            description = "Test description",
            targetFilePath = "dummy/path.kt",
            proposedContent = "// test code",
            diffPreview = "--- diff"
        )

        val invalidArgs = JSONObject().apply {
            put("proposal_id", propId)
            put("approval_token", "wrong_attacker_token_456")
        }
        val invalidContext = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_invalid_tok",
            toolName = "apply_integration_proposal",
            arguments = invalidArgs
        )
        val invalidResult = skill.execute(invalidContext)
        assertEquals(SkillExecutionStatus.BLOCKED, invalidResult.status)
        assertTrue(invalidResult.userFacingMessage.contains("does not match") || invalidResult.userFacingMessage.contains("Security Barrier"))
    }

    @Test
    fun testSkillBuilder_GenerateProposalAndReviewDiffWorkflow() = runBlocking {
        val skill = SkillBuilderIntegrationSkill()

        // Generate proposal
        val genArgs = JSONObject().apply {
            put("feature_name", "BatteryOptimizer")
            put("description", "Monitors background battery drain")
        }
        val genContext = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_gen",
            toolName = "generate_integration_proposal",
            arguments = genArgs
        )
        val genResult = skill.execute(genContext)
        assertEquals(SkillExecutionStatus.WAITING_FOR_USER, genResult.status)
        assertNotNull(genResult.data)

        val proposalId = genResult.data!!.getString("proposal_id")
        val approvalToken = genResult.data!!.getString("approval_token")
        assertTrue(proposalId.isNotBlank())
        assertTrue(approvalToken.isNotBlank())

        // Review diff
        val revArgs = JSONObject().apply {
            put("proposal_id", proposalId)
        }
        val revContext = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_rev",
            toolName = "review_integration_diff",
            arguments = revArgs
        )
        val revResult = skill.execute(revContext)
        assertEquals(SkillExecutionStatus.SUCCESS, revResult.status)
        assertTrue(revResult.data!!.getString("diff").contains("BatteryOptimizer"))
    }

    @Test
    fun testRepositoryAnalysisSkill_RejectsUnsupportedOrMaliciousProtocols() = runBlocking {
        val skill = RepositoryAnalysisSkill()

        // 1. ssh:// protocol must be rejected
        val sshArgs = JSONObject().apply {
            put("repo_path", "ssh://git@github.com/owner/repo.git")
        }
        val sshContext = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_ssh",
            toolName = "analyze_code_repository",
            arguments = sshArgs
        )
        val sshRes = skill.execute(sshContext)
        assertEquals(SkillExecutionStatus.FAILED, sshRes.status)
        assertTrue(sshRes.userFacingMessage.contains("Only public HTTPS GitHub URLs"))

        // 2. ftp:// protocol must be rejected
        val ftpArgs = JSONObject().apply {
            put("repo_path", "ftp://malicious.org/repo.zip")
        }
        val ftpContext = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_ftp",
            toolName = "analyze_code_repository",
            arguments = ftpArgs
        )
        val ftpRes = skill.execute(ftpContext)
        assertEquals(SkillExecutionStatus.FAILED, ftpRes.status)
        assertTrue(ftpRes.userFacingMessage.contains("Only public HTTPS GitHub URLs"))
    }

    @Test
    fun testCompanionApiClient_DefaultConfiguration() {
        val client = CompanionApiClient(TestMockContext())
        assertEquals("http://127.0.0.1:8765", CompanionApiClient.DEFAULT_BASE_URL)
    }

    @Test
    fun testRepositoryAnalysisSkill_PublicGitHubUrlRoutingOffline_ReturnsBlockedNotFileNotFound() = runBlocking {
        val skill = RepositoryAnalysisSkill()
        val mockContext = TestMockContext()
        mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit().putString("companion_base_url", "http://127.0.0.1:59998").apply()

        val args = JSONObject().apply {
            put("repo_path", "https://github.com/octocat/Hello-World.git")
        }
        val context = SkillExecutionContext(
            context = mockContext,
            toolCallId = "call_gh_offline",
            toolName = "analyze_code_repository",
            arguments = args
        )
        val res = skill.execute(context)
        assertEquals(SkillExecutionStatus.BLOCKED, res.status)
        assertTrue(res.userFacingMessage.contains("requires the Jarvis Laptop Companion"))
        assertFalse(res.userFacingMessage.contains("does not exist"))
    }

    @Test
    fun testRepositoryAnalysisSkill_NormalizesGitHubUrlWithoutHttps() = runBlocking {
        val skill = RepositoryAnalysisSkill()
        val mockContext = TestMockContext()
        mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit().putString("companion_base_url", "http://127.0.0.1:59998").apply()

        val args = JSONObject().apply {
            put("repo_path", "github.com/octocat/Hello-World")
        }
        val context = SkillExecutionContext(
            context = mockContext,
            toolCallId = "call_norm",
            toolName = "analyze_code_repository",
            arguments = args
        )
        val res = skill.execute(context)
        assertEquals(SkillExecutionStatus.BLOCKED, res.status)
        assertTrue(res.userFacingMessage.contains("requires the Jarvis Laptop Companion"))
        assertFalse(res.userFacingMessage.contains("does not exist"))
    }

    @Test
    fun testRepositoryAnalysisSkill_HttpUrlUpgradedToHttps() = runBlocking {
        val skill = RepositoryAnalysisSkill()
        val mockContext = TestMockContext()
        mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit().putString("companion_base_url", "http://127.0.0.1:59998").apply()

        val args = JSONObject().apply {
            put("repo_path", "http://github.com/octocat/Hello-World")
        }
        val context = SkillExecutionContext(
            context = mockContext,
            toolCallId = "call_http",
            toolName = "analyze_code_repository",
            arguments = args
        )
        val res = skill.execute(context)
        assertEquals(SkillExecutionStatus.BLOCKED, res.status)
        assertTrue(res.userFacingMessage.contains("requires the Jarvis Laptop Companion"))
    }

    @Test
    fun testRepositoryAnalysisSkill_EndToEndWithCompanionServerMock() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val expectedToken = "secret_mock_token_789"

        server.handler = { path, headers, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE","service":"JarvisLaptopCompanion"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"$expectedToken"}"""
                "/api/analyze" -> {
                    val tokenHeader = headers["x-jarvis-token"]
                    if (tokenHeader != expectedToken) {
                        401 to """{"error":"Unauthorized"}"""
                    } else {
                        200 to """
                            {
                                "repository": "Hello-World",
                                "isCloned": true,
                                "location": "companion_sandbox",
                                "analysis": {
                                    "totalFiles": 1,
                                    "totalDirs": 0,
                                    "technologies": ["Markdown / Docs"],
                                    "keyDependencies": [],
                                    "entryPoints": [],
                                    "fileTreePreview": ["README"]
                                }
                            }
                        """.trimIndent()
                    }
                }
                else -> 404 to """{"error":"Not Found"}"""
            }
        }

        try {
            val mockContext = TestMockContext()
            val client = CompanionApiClient(mockContext)
            client.baseUrl = "http://127.0.0.1:$port"

            val skill = RepositoryAnalysisSkill()
            val args = JSONObject().apply {
                put("repo_path", "https://github.com/octocat/Hello-World.git")
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_e2e",
                toolName = "analyze_code_repository",
                arguments = args
            )
            val res = skill.execute(context)

            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertNotNull(res.data)
            assertEquals("Hello-World", res.data!!.getString("repository"))
            assertTrue(res.userFacingMessage.contains("1 across 0 directories"))
            assertTrue(res.userFacingMessage.contains("README"))
            assertEquals(expectedToken, client.authToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun testRepositoryAnalysisSkill_CompanionErrorDoesNotFallThroughToLocalPath() = runBlocking {
        val server = MockHttpServer()
        val port = server.port

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"tok123"}"""
                "/api/analyze" -> 502 to """{"error":"Remote repository shallow clone timed out after 45s"}"""
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            val client = CompanionApiClient(mockContext)
            client.baseUrl = "http://127.0.0.1:$port"

            val skill = RepositoryAnalysisSkill()
            val args = JSONObject().apply {
                put("repo_path", "https://github.com/octocat/Hello-World.git")
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_err",
                toolName = "analyze_code_repository",
                arguments = args
            )
            val res = skill.execute(context)

            assertEquals(SkillExecutionStatus.FAILED, res.status)
            assertTrue(res.userFacingMessage.contains("shallow clone timed out"))
            assertFalse(res.userFacingMessage.contains("The specified path does not exist"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testCompanionApiClient_AutoPairingAndTokenHandling() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val pairedSecret = "pair_secret_xyz"

        server.handler = { path, _, _ ->
            when (path) {
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"$pairedSecret"}"""
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            val client = CompanionApiClient(mockContext)
            client.baseUrl = "http://127.0.0.1:$port"

            assertEquals("", client.authToken)
            val success = client.ensurePaired()
            assertTrue(success)
            assertEquals(pairedSecret, client.authToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun testSkillRegistry_RegistersAndExposesReviewAndRollbackTools() {
        val mockContext = TestMockContext()
        val registry = SkillRegistry(mockContext)

        val reviewSkill = registry.findSkillForTool("review_integration_diff")
        assertNotNull(reviewSkill)
        assertTrue(reviewSkill is SkillBuilderIntegrationSkill)

        val rollbackSkill = registry.findSkillForTool("rollback_integration")
        assertNotNull(rollbackSkill)
        assertTrue(rollbackSkill is SkillBuilderIntegrationSkill)

        val schemas = registry.getRegisteredToolSchemas()
        val reviewSchema = schemas.firstOrNull { it.optJSONObject("function")?.optString("name") == "review_integration_diff" }
        assertNotNull(reviewSchema)
        val reviewFunc = reviewSchema!!.getJSONObject("function")
        val reviewParams = reviewFunc.getJSONObject("parameters")
        val reviewRequired = reviewParams.getJSONArray("required")
        assertEquals("proposal_id", reviewRequired.getString(0))

        val rollbackSchema = schemas.firstOrNull { it.optJSONObject("function")?.optString("name") == "rollback_integration" }
        assertNotNull(rollbackSchema)
        val rollbackFunc = rollbackSchema!!.getJSONObject("function")
        val rollbackParams = rollbackFunc.getJSONObject("parameters")
        val rollbackRequired = rollbackParams.getJSONArray("required")
        assertEquals("checkpoint_id", rollbackRequired.getString(0))
    }

    @Test
    fun testReviewIntegrationDiff_QueriesCompanionWhenNotCachedLocally() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val targetProposalId = "proposal_valid_companion_diff_123"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 200 to """
                    {
                        "status": "SUCCESS",
                        "proposal_id": "$targetProposalId",
                        "feature_name": "Spoon-Knife Demo Screen",
                        "target_file": "app/src/main/java/com/jarvis/assistant/automation/skills/builtin/SpoonKnifeDemoScreenSkill.kt",
                        "diff": "--- a/SpoonKnifeDemoScreenSkill.kt\n+++ b/SpoonKnifeDemoScreenSkill.kt\n+// Verified integration",
                        "proposal": {
                            "proposalId": "$targetProposalId",
                            "approvalToken": "4672d52ce7e22808155ee202f12bfbe1",
                            "featureName": "Spoon-Knife Demo Screen",
                            "diffPreview": "--- a/SpoonKnifeDemoScreenSkill.kt\n+++ b/SpoonKnifeDemoScreenSkill.kt\n+// Verified integration"
                        }
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            // Ensure not in local cache initially
            SkillBuilderIntegrationSkill.localProposals.remove(targetProposalId)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", targetProposalId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_remote",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertNotNull(res.data)
            assertEquals(targetProposalId, res.data!!.getString("proposal_id"))
            assertTrue(res.data!!.getString("diff").contains("SpoonKnifeDemoScreenSkill.kt"))
            assertTrue(res.userFacingMessage.contains("Reviewing Proposal '$targetProposalId'"))

            // Verify it was populated into local cache
            assertTrue(SkillBuilderIntegrationSkill.localProposals.containsKey(targetProposalId))
            val cached = SkillBuilderIntegrationSkill.localProposals[targetProposalId]
            assertEquals("4672d52ce7e22808155ee202f12bfbe1", cached?.approvalToken)
        } finally {
            server.close()
            SkillBuilderIntegrationSkill.localProposals.remove(targetProposalId)
        }
    }

    @Test
    fun testReviewIntegrationDiff_ReturnsClearErrorForUnknownProposal() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val unknownId = "unknown_proposal_xyz"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 404 to """{"error":"Proposal '$unknownId' not found or expired."}"""
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            SkillBuilderIntegrationSkill.localProposals.remove(unknownId)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", unknownId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_unknown",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.FAILED, res.status)
            assertTrue(res.userFacingMessage.contains("not found or expired"))
            assertTrue(res.errorMessage?.contains("not found") == true)
        } finally {
            server.close()
        }
    }

    @Test
    fun testRollbackIntegration_DelegatesToCompanionWhenNotCachedLocally() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val testCheckpointId = "checkpoint_test_999"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/rollback" -> 200 to """
                    {
                        "status": "ROLLED_BACK",
                        "message": "Successfully restored pre-application state from checkpoint '$testCheckpointId'."
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            SkillBuilderIntegrationSkill.localCheckpoints.remove(testCheckpointId)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("checkpoint_id", testCheckpointId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rollback_remote",
                toolName = "rollback_integration",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertTrue(res.userFacingMessage.contains("Successfully restored pre-application state"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testSpoonKnifeDemoScreenSkill_MetadataAndSchema() {
        val skill = SpoonKnifeDemoScreenSkill()
        val meta = skill.metadata

        assertEquals("spoon_knife_demo_screen_skill", meta.id)
        assertEquals(SkillCategory.CUSTOM, meta.category)
        assertEquals(SkillRiskLevel.SAFE, meta.riskLevel)
        assertTrue(meta.supportedTools.contains("open_spoon_knife_demo"))
        assertTrue(meta.supportedTools.contains("close_spoon_knife_demo"))

        assertEquals(2, meta.toolSchemas.size)
        val toolNames = meta.toolSchemas.map { it.getJSONObject("function").getString("name") }
        assertTrue(toolNames.contains("open_spoon_knife_demo"))
        assertTrue(toolNames.contains("close_spoon_knife_demo"))
    }

    @Test
    fun testSkillRegistry_RegistersAndExposesSpoonKnifeTools() {
        val mockContext = TestMockContext()
        val registry = SkillRegistry(mockContext)

        val openSkill = registry.findSkillForTool("open_spoon_knife_demo")
        assertNotNull(openSkill)
        assertTrue(openSkill is SpoonKnifeDemoScreenSkill)

        val closeSkill = registry.findSkillForTool("close_spoon_knife_demo")
        assertNotNull(closeSkill)
        assertTrue(closeSkill is SpoonKnifeDemoScreenSkill)

        val schemas = registry.getRegisteredToolSchemas()
        val openSchema = schemas.firstOrNull { it.optJSONObject("function")?.optString("name") == "open_spoon_knife_demo" }
        assertNotNull(openSchema)

        val closeSchema = schemas.firstOrNull { it.optJSONObject("function")?.optString("name") == "close_spoon_knife_demo" }
        assertNotNull(closeSchema)
    }

    @Test
    fun testSpoonKnifeDemoScreenSkill_CloseWhenNotOpen() = runBlocking {
        val skill = SpoonKnifeDemoScreenSkill()
        val context = SkillExecutionContext(
            context = TestMockContext(),
            toolCallId = "call_close",
            toolName = "close_spoon_knife_demo",
            arguments = JSONObject()
        )
        val res = skill.execute(context)
        assertEquals(SkillExecutionStatus.SUCCESS, res.status)
        assertTrue(res.isVerified)
        assertEquals("NOT_OPEN", res.data?.optString("status"))
        assertTrue(res.userFacingMessage.contains("not currently open"))
    }

    @Test
    fun testReviewIntegrationDiff_RejectsInvalidatedExposedProposal() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val revokedId = "proposal_1792510894907_594057fd"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 410 to """{"error":"Proposal '$revokedId' has been revoked and invalidated for security reasons. A fresh proposal must be generated."}"""
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            SkillBuilderIntegrationSkill.localProposals.remove(revokedId)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", revokedId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_revoked",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.FAILED, res.status)
            assertTrue(res.userFacingMessage.contains("invalidated") || res.errorMessage?.contains("invalidated") == true)
        } finally {
            server.close()
        }
    }

    @Test
    fun testReviewIntegrationDiff_MultiFileDiffParsing() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val multiFileProposalId = "proposal_test_multifile_123"

        val multiFileDiff = """
            --- /dev/null
            +++ b/app/src/main/assets/spoonknife/index.html
            +<!DOCTYPE html>
            +<html>
            +  <head><link rel="stylesheet" href="styles.css"></head>
            +  <body>Hello Spoon-Knife</body>
            +</html>
            --- /dev/null
            +++ b/app/src/main/assets/spoonknife/styles.css
            +body { background-color: #fafafa; }
            --- /dev/null
            +++ b/app/src/main/java/com/jarvis/assistant/ui/spoonknife/SpoonKnifeDemoActivity.kt
            +package com.jarvis.assistant.ui.spoonknife
            --- /dev/null
            +++ b/app/src/main/java/com/jarvis/assistant/automation/skills/builtin/SpoonKnifeDemoScreenSkill.kt
            +package com.jarvis.assistant.automation.skills.builtin
        """.trimIndent()

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 200 to """
                    {
                        "status": "SUCCESS",
                        "proposal_id": "$multiFileProposalId",
                        "feature_name": "Spoon-Knife Static Frontend",
                        "target_file": "app/src/main/assets/spoonknife/index.html",
                        "diff": ${JSONObject.quote(multiFileDiff)},
                        "files": [
                            {"path": "app/src/main/assets/spoonknife/index.html", "action": "CREATE"},
                            {"path": "app/src/main/assets/spoonknife/styles.css", "action": "CREATE"},
                            {"path": "app/src/main/java/com/jarvis/assistant/ui/spoonknife/SpoonKnifeDemoActivity.kt", "action": "CREATE"},
                            {"path": "app/src/main/java/com/jarvis/assistant/automation/skills/builtin/SpoonKnifeDemoScreenSkill.kt", "action": "CREATE"}
                        ],
                        "proposal": {
                            "proposalId": "$multiFileProposalId",
                            "approvalToken": "tok_multi_456",
                            "featureName": "Spoon-Knife Static Frontend",
                            "diffPreview": ${JSONObject.quote(multiFileDiff)}
                        }
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            SkillBuilderIntegrationSkill.localProposals.remove(multiFileProposalId)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", multiFileProposalId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_multi",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertNotNull(res.data)
            val diff = res.data!!.getString("diff")
            assertTrue(diff.contains("app/src/main/assets/spoonknife/index.html"))
            assertTrue(diff.contains("app/src/main/assets/spoonknife/styles.css"))
            assertTrue(diff.contains("SpoonKnifeDemoActivity.kt"))
            assertTrue(diff.contains("SpoonKnifeDemoScreenSkill.kt"))
        } finally {
            server.close()
            SkillBuilderIntegrationSkill.localProposals.remove(multiFileProposalId)
        }
    }

    @Test
    fun testReviewIntegrationDiff_ResolvesHyphenatedAndMalformedId() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val canonicalId = "proposal_1792514327636_6306288f"
        val hyphenatedInput = "proposal-1792514327636"

        server.handler = { path, _, body ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> {
                    val reqJson = JSONObject(body)
                    val pId = reqJson.optString("proposal_id")
                    // Companion resolves hyphenated input to canonicalId
                    if (pId == hyphenatedInput || pId == canonicalId) {
                        200 to """
                            {
                                "status": "SUCCESS",
                                "proposal_id": "$canonicalId",
                                "feature_name": "Spoon-Knife Demo Screen",
                                "target_file": "app/src/main/assets/spoonknife/index.html",
                                "diff": "--- a/app/src/main/assets/spoonknife/index.html\n+++ b/app/src/main/assets/spoonknife/index.html\n+<!DOCTYPE html>",
                                "proposal": {
                                    "proposalId": "$canonicalId",
                                    "approvalToken": "tok_canonical_777",
                                    "featureName": "Spoon-Knife Demo Screen",
                                    "diffPreview": "--- a/app/src/main/assets/spoonknife/index.html\n+++ b/app/src/main/assets/spoonknife/index.html\n+<!DOCTYPE html>"
                                }
                            }
                        """.trimIndent()
                    } else {
                        404 to """{"error":"Proposal '$pId' not found."}"""
                    }
                }
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            SkillBuilderIntegrationSkill.localProposals.remove(canonicalId)
            SkillBuilderIntegrationSkill.localProposals.remove(hyphenatedInput)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", hyphenatedInput)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_hyphen",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertNotNull(res.data)
            assertEquals(canonicalId, res.data!!.getString("proposal_id"))
            assertTrue(res.data!!.getString("diff").contains("index.html"))
        } finally {
            server.close()
            SkillBuilderIntegrationSkill.localProposals.remove(canonicalId)
            SkillBuilderIntegrationSkill.localProposals.remove(hyphenatedInput)
        }
    }

    @Test
    fun testReviewIntegrationDiff_ResolvesLatestWhenIdIsLatestOrEmpty() = runBlocking {
        SkillBuilderIntegrationSkill.localProposals.clear()
        val server = MockHttpServer()
        val port = server.port
        val latestCanonicalId = "proposal_1792516972968_latest"

        server.handler = { path, _, body ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> {
                    200 to """
                        {
                            "status": "SUCCESS",
                            "proposal_id": "$latestCanonicalId",
                            "feature_name": "Latest Pending Feature",
                            "target_file": "app/src/main/java/LatestSkill.kt",
                            "diff": "--- /dev/null\n+++ b/LatestSkill.kt\n+// Latest code",
                            "proposal": {
                                "proposalId": "$latestCanonicalId",
                                "approvalToken": "tok_latest_999",
                                "featureName": "Latest Pending Feature",
                                "diffPreview": "--- /dev/null\n+++ b/LatestSkill.kt\n+// Latest code"
                            }
                        }
                    """.trimIndent()
                }
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", "latest")
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_latest",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertEquals(latestCanonicalId, res.data!!.getString("proposal_id"))
        } finally {
            server.close()
            SkillBuilderIntegrationSkill.localProposals.remove(latestCanonicalId)
        }
    }

    @Test
    fun testReviewIntegrationDiff_NonExistentProposalReturnsClearErrorAndGuidance() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val missingId = "proposal_nonexistent_888"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 404 to """
                    {
                        "error": "Proposal '$missingId' not found or expired.",
                        "suggestion": "To regenerate a proposal, call generate_integration_proposal with the feature_name (e.g. 'Spoon-Knife Demo Screen')."
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            SkillBuilderIntegrationSkill.localProposals.remove(missingId)

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", missingId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_nonexistent",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.FAILED, res.status)
            assertTrue(res.userFacingMessage.contains("not found or expired"))
            assertTrue(res.userFacingMessage.contains("generate_integration_proposal") || res.userFacingMessage.contains("regenerate"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testApplyIntegrationProposal_RejectsStaleOrMismatchedToken() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val targetId = "proposal_stale_check_555"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/apply-proposal" -> 403 to """
                    {
                        "error": "Action Blocked: Invalid or mismatched approval_token for proposal '$targetId'. Code modification requires the exact approval token generated for this specific proposal.",
                        "status": "WAITING_FOR_USER_APPROVAL"
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", targetId)
                put("approval_token", "old_stale_token_from_previous_diff")
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_apply_stale",
                toolName = "apply_integration_proposal",
                arguments = args
            )

            val res = skill.execute(context)
            assertTrue(res.status == SkillExecutionStatus.FAILED || res.status == SkillExecutionStatus.BLOCKED)
            assertTrue(res.userFacingMessage.contains("Invalid or mismatched approval_token") || res.userFacingMessage.contains("Action Blocked"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testReviewIntegrationDiff_CompanionOfflineReturnsBlockedNotNotFound() = runBlocking {
        // Mock context pointing to an invalid/unreachable port
        val mockContext = TestMockContext()
        mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
            .putString("companion_base_url", "http://127.0.0.1:65530") // Unreachable port
            .putString("companion_auth_token", "dummy_token")
            .apply()

        SkillBuilderIntegrationSkill.localProposals.clear()

        val skill = SkillBuilderIntegrationSkill()
        val args = JSONObject().apply {
            put("proposal_id", "proposal_1792514327636_6306288f")
        }
        val context = SkillExecutionContext(
            context = mockContext,
            toolCallId = "call_rev_offline",
            toolName = "review_integration_diff",
            arguments = args
        )

        val res = skill.execute(context)
        assertEquals(SkillExecutionStatus.BLOCKED, res.status)
        assertTrue("Message must indicate companion is unreachable, not that proposal is not found",
            res.userFacingMessage.contains("unreachable") || res.userFacingMessage.contains("Companion service is unreachable"))
        assertFalse("Message must NOT claim proposal was not found or expired when offline",
            res.userFacingMessage.contains("not found or expired"))
    }

    @Test
    fun testReviewIntegrationDiff_AmbiguousMatchReturnsDetailedError() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val queryId = "12345678"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 400 to """
                    {
                        "error": "Ambiguous proposal query '$queryId': Matches multiple active proposals. Please specify the exact canonical proposal ID.",
                        "status": "AMBIGUOUS_MATCH",
                        "availableProposals": [
                            {"proposalId": "proposal_12345678_aaa", "featureName": "Feature1"},
                            {"proposalId": "proposal_12345678_bbb", "featureName": "Feature2"}
                        ]
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", queryId)
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_amb",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.FAILED, res.status)
            assertTrue(res.userFacingMessage.contains("Ambiguous proposal query"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testReviewIntegrationDiff_NaturalLanguageSpoonKnifeQueryResolves() = runBlocking {
        val server = MockHttpServer()
        val port = server.port
        val canonicalId = "proposal_1792514327636_6306288f"

        server.handler = { path, _, _ ->
            when (path) {
                "/health" -> 200 to """{"status":"ONLINE"}"""
                "/api/pair" -> 200 to """{"status":"PAIRED","token":"test_tok"}"""
                "/api/review-proposal" -> 200 to """
                    {
                        "status": "SUCCESS",
                        "proposal_id": "$canonicalId",
                        "feature_name": "Spoon-Knife Demo Screen",
                        "target_file": "app/src/main/java/com/jarvis/assistant/automation/skills/builtin/SpoonKnifeDemoScreenSkill.kt",
                        "diff": "--- a/app/src/main/assets/spoonknife/index.html\n+++ b/app/src/main/assets/spoonknife/index.html\n+<!DOCTYPE html>",
                        "diff_preview": "--- a/app/src/main/assets/spoonknife/index.html\n+++ b/app/src/main/assets/spoonknife/index.html\n+<!DOCTYPE html>",
                        "proposal_status": "PENDING_USER_APPROVAL",
                        "files": [
                            {"targetFile": "app/src/main/assets/spoonknife/index.html", "proposedContent": "<!DOCTYPE html>"}
                        ]
                    }
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        try {
            val mockContext = TestMockContext()
            mockContext.getSharedPreferences("jarvis_companion_prefs", 0).edit()
                .putString("companion_base_url", "http://127.0.0.1:$port")
                .putString("companion_auth_token", "test_tok")
                .apply()

            val skill = SkillBuilderIntegrationSkill()
            val args = JSONObject().apply {
                put("proposal_id", "Spoon Knife")
            }
            val context = SkillExecutionContext(
                context = mockContext,
                toolCallId = "call_rev_natural",
                toolName = "review_integration_diff",
                arguments = args
            )

            val res = skill.execute(context)
            assertEquals(SkillExecutionStatus.SUCCESS, res.status)
            assertTrue(res.isVerified)
            assertEquals(canonicalId, res.data!!.getString("proposal_id"))
            assertEquals("Spoon-Knife Demo Screen", res.data!!.getString("feature_name"))
        } finally {
            server.close()
            SkillBuilderIntegrationSkill.localProposals.remove(canonicalId)
        }
    }

    // Lightweight mock HTTP server using standard Java ServerSocket (zero external dependencies)
    private class MockHttpServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        @Volatile
        var handler: ((path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>)? = null

        init {
            val thread = Thread {
                try {
                    while (!serverSocket.isClosed) {
                        val socket = serverSocket.accept()
                        Thread {
                            try {
                                socket.use { s ->
                                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                    val requestLine = reader.readLine() ?: return@use
                                    val parts = requestLine.split(" ")
                                    val path = if (parts.size > 1) parts[1] else "/"

                                    val headers = mutableMapOf<String, String>()
                                    var line: String?
                                    var contentLength = 0
                                    while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
                                        val idx = line!!.indexOf(":")
                                        if (idx > 0) {
                                            val k = line!!.substring(0, idx).trim().lowercase()
                                            val v = line!!.substring(idx + 1).trim()
                                            headers[k] = v
                                            if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                                        }
                                    }

                                    val body = if (contentLength > 0) {
                                        val chars = CharArray(contentLength)
                                        var read = 0
                                        while (read < contentLength) {
                                            val r = reader.read(chars, read, contentLength - read)
                                            if (r == -1) break
                                            read += r
                                        }
                                        String(chars, 0, read)
                                    } else ""

                                    val (statusCode, responseBody) = handler?.invoke(path, headers, body) ?: (200 to "{}")
                                    val statusMsg = if (statusCode == 200) "OK" else if (statusCode == 401) "Unauthorized" else "Error"
                                    val out = OutputStreamWriter(s.getOutputStream())
                                    out.write("HTTP/1.1 $statusCode $statusMsg\r\n")
                                    out.write("Content-Type: application/json\r\n")
                                    out.write("Content-Length: ${responseBody.toByteArray().size}\r\n")
                                    out.write("Connection: close\r\n\r\n")
                                    out.write(responseBody)
                                    out.flush()
                                }
                            } catch (_: Exception) {}
                        }.apply { isDaemon = true; start() }
                    }
                } catch (_: Exception) {}
            }
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            try { serverSocket.close() } catch (_: Exception) {}
        }
    }

    // Lightweight mock context for JVM unit test execution
    private class TestMockContext : android.content.ContextWrapper(null) {
        private val tempDir = File.createTempFile("mock_ctx_", "").apply {
            delete()
            mkdirs()
        }
        private val prefsMap = mutableMapOf<String, MockTestPrefs>()

        override fun getFilesDir(): File = File(tempDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(tempDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): android.content.Context = this
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
            val key = name ?: "default"
            return prefsMap.getOrPut(key) { MockTestPrefs() }
        }
    }

    private class MockTestPrefs : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = MockEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class MockEditor(val map: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) map[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }
}
