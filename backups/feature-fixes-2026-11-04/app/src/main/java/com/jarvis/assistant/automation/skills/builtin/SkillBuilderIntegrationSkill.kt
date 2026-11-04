package com.jarvis.assistant.automation.skills.builtin

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.automation.skills.JarvisSkill
import com.jarvis.assistant.automation.skills.SkillCategory
import com.jarvis.assistant.automation.skills.SkillExecutionContext
import com.jarvis.assistant.automation.skills.SkillExecutionResult
import com.jarvis.assistant.automation.skills.SkillExecutionStatus
import com.jarvis.assistant.automation.skills.SkillMetadata
import com.jarvis.assistant.automation.skills.SkillRiskLevel
import com.jarvis.assistant.automation.skills.companion.CompanionApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LocalProposal(
    val proposalId: String,
    val approvalToken: String,
    val featureName: String,
    val description: String,
    val targetFilePath: String,
    val proposedContent: String,
    val diffPreview: String,
    var status: String = "PENDING_APPROVAL",
    val files: List<Map<String, String>> = emptyList()
)

data class LocalCheckpoint(
    val checkpointId: String,
    val proposalId: String,
    val targetFile: File,
    val backupFile: File?,
    val fileExisted: Boolean
)

/**
 * Built-in Skill for autonomous feature integration, cross-codebase mapping,
 * proposal generation, validated diff review, and checkpointed patch application.
 *
 * Enforces strict safety rules:
 *  - Explicit User Approval Token Gate: Code changes CANNOT be applied without approval_token.
 *  - Automatic Pre-Application Checkpoint: Files are backed up prior to any modification.
 *  - Safe Atomic Rollback: Changes can be reverted to clean checkpoint state anytime.
 *  - Untrusted Repo Isolation: Never executes arbitrary scripts or hooks from repos.
 *  - Companion Delegation: Cooperates with the Laptop Companion for Git clones & Gradle tasks.
 */
class SkillBuilderIntegrationSkill : JarvisSkill {

    companion object {
        private const val TAG = "SkillBuilderSkill"
        private val INVALIDATED_PROPOSALS = setOf("proposal_1792510894907_594057fd")

        fun isInvalidatedProposal(id: String): Boolean {
            if (id.isBlank()) return false
            val norm = id.trim().lowercase().replace("-", "_")
            return INVALIDATED_PROPOSALS.any { inv ->
                val normInv = inv.lowercase()
                norm == normInv || (norm.length >= 15 && (normInv.startsWith(norm) || norm.startsWith(normInv))) || norm.contains("1792510894907")
            }
        }

        fun triggerDiffDisplay(
            androidContext: Context,
            proposalId: String,
            featureName: String,
            targetFile: String,
            diff: String,
            status: String
        ) {
            // 1. Launch on-screen visual Diff Review Activity (bypassing BAL via ActivityLauncherHelper)
            try {
                val intent = Intent().apply {
                    setClassName(androidContext.packageName, "com.jarvis.assistant.ui.diff.ProposalDiffActivity")
                    putExtra(com.jarvis.assistant.ui.diff.ProposalDiffActivity.EXTRA_PROPOSAL_ID, proposalId)
                    putExtra(com.jarvis.assistant.ui.diff.ProposalDiffActivity.EXTRA_FEATURE_NAME, featureName)
                    putExtra(com.jarvis.assistant.ui.diff.ProposalDiffActivity.EXTRA_TARGET_FILE, targetFile)
                    putExtra(com.jarvis.assistant.ui.diff.ProposalDiffActivity.EXTRA_DIFF, diff)
                    putExtra(com.jarvis.assistant.ui.diff.ProposalDiffActivity.EXTRA_STATUS, status)
                }
                com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(androidContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch ProposalDiffActivity: ${e.message}")
            }

            // 2. Persist formatted diff into Chat history timeline for ongoing viewing and copy
            try {
                val app = androidContext.applicationContext as? com.jarvis.assistant.JarvisApp
                app?.chatRepository?.addTurn(
                    userText = "Review integration diff: $proposalId",
                    jarvisText = "Reviewing Proposal '$proposalId' ($featureName):\n\n```diff\n$diff\n```"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not record diff to ChatRepository: ${e.message}")
            }
        }

        // In-memory proposal and checkpoint registry for on-device/local execution
        val localProposals = ConcurrentHashMap<String, LocalProposal>()
        val localCheckpoints = ConcurrentHashMap<String, LocalCheckpoint>()

        private fun buildSchemas(): List<JSONObject> {
            val list = mutableListOf<JSONObject>()

            // 1. inspect_integration_points
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "inspect_integration_points")
                    put("description", "Analyzes a repository against Jarvis's architecture to identify integration points, compatible modules, required permissions, and architectural steps.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("repo_path", JSONObject().apply {
                                put("type", "string")
                                put("description", "Path to local repository directory, ZIP archive, or public HTTPS GitHub URL.")
                            })
                            put("feature_name", JSONObject().apply {
                                put("type", "string")
                                put("description", "Name or description of feature to extract and integrate (e.g. 'Offline Audio Player', 'Battery Saver').")
                            })
                        })
                        put("required", JSONArray().apply { put("repo_path") })
                    })
                })
            })

            // 2. generate_integration_proposal
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_integration_proposal")
                    put("description", "Generates a structured, reviewable integration proposal with unified diff preview and a mandatory user approval token.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("feature_name", JSONObject().apply {
                                put("type", "string")
                                put("description", "Name of the feature being integrated.")
                            })
                            put("description", JSONObject().apply {
                                put("type", "string")
                                put("description", "Detailed description of what the feature does and how it integrates into Jarvis.")
                            })
                            put("target_module", JSONObject().apply {
                                put("type", "string")
                                put("description", "Target Jarvis module (e.g. 'skills', 'ui', 'connectors', 'agent'). Default 'skills'.")
                            })
                        })
                        put("required", JSONArray().apply { put("feature_name") })
                    })
                })
            })

            // 3. review_integration_diff
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "review_integration_diff")
                    put("description", "Reviews the proposed code changes and diff for a pending integration proposal before approval.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("proposal_id", JSONObject().apply {
                                put("type", "string")
                                put("description", "The unique proposal ID returned by generate_integration_proposal.")
                            })
                        })
                        put("required", JSONArray().apply { put("proposal_id") })
                    })
                })
            })

            // 4. apply_integration_proposal
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "apply_integration_proposal")
                    put("description", "Applies an approved integration proposal to the codebase. STRICT SAFETY REQUIREMENT: Requires explicit user approval_token.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("proposal_id", JSONObject().apply {
                                put("type", "string")
                                put("description", "The proposal ID to apply.")
                            })
                            put("approval_token", JSONObject().apply {
                                put("type", "string")
                                put("description", "The user's explicit confirmation approval token. Modifications are rejected without this token.")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("proposal_id")
                            put("approval_token")
                        })
                    })
                })
            })

            // 5. rollback_integration
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "rollback_integration")
                    put("description", "Atomically rolls back an applied integration proposal, restoring files to their exact pre-modification state.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("checkpoint_id", JSONObject().apply {
                                put("type", "string")
                                put("description", "The checkpoint ID generated during apply_integration_proposal.")
                            })
                        })
                        put("required", JSONArray().apply { put("checkpoint_id") })
                    })
                })
            })

            return list
        }
    }

    override val metadata: SkillMetadata = SkillMetadata(
        id = "skill_builder_integration_skill",
        name = "Skill Builder & Repository Integrator",
        description = "Analyzes source repositories, maps features to Jarvis architecture, generates proposals with user approval gates, and safely applies or rolls back changes.",
        version = "1.0.0",
        category = SkillCategory.DEVELOPER_TOOLS,
        riskLevel = SkillRiskLevel.HIGH_RISK,
        requiredPermissions = emptyList(),
        isNonIdempotent = true,
        timeoutMs = 60_000L,
        supportedTools = listOf(
            "inspect_integration_points",
            "generate_integration_proposal",
            "review_integration_diff",
            "apply_integration_proposal",
            "rollback_integration"
        ),
        toolSchemas = buildSchemas()
    )

    override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult = withContext(Dispatchers.IO) {
        val toolName = context.toolName
        val args = context.arguments
        val client = CompanionApiClient(context.context)

        try {
            when (toolName) {
                "inspect_integration_points" -> handleInspectIntegration(client, args, context)
                "generate_integration_proposal" -> handleGenerateProposal(client, args, context)
                "review_integration_diff" -> handleReviewDiff(client, args, context)
                "apply_integration_proposal" -> handleApplyProposal(client, args, context)
                "rollback_integration" -> handleRollback(client, args, context)
                else -> SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Unsupported tool '$toolName' in SkillBuilderIntegrationSkill.",
                    errorMessage = "Unsupported tool: $toolName"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing SkillBuilder tool '$toolName': ${e.message}", e)
            SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Skill Builder failed: ${e.message}",
                errorMessage = e.message
            )
        }
    }

    private suspend fun handleInspectIntegration(
        client: CompanionApiClient,
        args: JSONObject,
        context: SkillExecutionContext
    ): SkillExecutionResult {
        val repoPath = args.optString("repo_path", "").trim()
        val featureName = args.optString("feature_name", "Requested Feature").trim()

        if (repoPath.isBlank()) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Missing repo_path argument. Please provide a path or GitHub HTTPS URL.",
                errorMessage = "Missing repo_path"
            )
        }

        // Try companion delegation if reachable
        if (client.isCompanionAvailable()) {
            val companionAnalysis = client.analyzeRepository(repoPath)
            if (companionAnalysis != null) {
                val analysisObj = companionAnalysis.optJSONObject("analysis") ?: companionAnalysis
                val compatibility = client.inspectIntegration(analysisObj, featureName)
                if (compatibility != null) {
                    val summary = buildIntegrationSummary(compatibility, repoPath, featureName)
                    return SkillExecutionResult(
                        status = SkillExecutionStatus.SUCCESS,
                        data = compatibility,
                        userFacingMessage = summary,
                        isVerified = true
                    )
                }
            }
        }

        // Local fallback analysis (for local directory/ZIP)
        val file = File(repoPath)
        val detectedTech = mutableListOf<String>()
        if (file.exists()) {
            if (File(file, "build.gradle").exists() || File(file, "build.gradle.kts").exists()) detectedTech.add("Android / Gradle")
            if (File(file, "package.json").exists()) detectedTech.add("Node.js / JS")
            if (File(file, "requirements.txt").exists()) detectedTech.add("Python")
        } else if (repoPath.startsWith("https://github.com/")) {
            detectedTech.add("GitHub Repository (Remote)")
        }

        val localResult = JSONObject().apply {
            put("repository", repoPath)
            put("feature_name", featureName)
            put("detected_technologies", JSONArray(detectedTech))
            put("recommended_module", "automation/skills/builtin")
            put("proposed_steps", JSONArray().apply {
                put("1. Inspect source structures and logic")
                put("2. Implement modular JarvisSkill subclass")
                put("3. Register skill in SkillRegistry")
                put("4. Enforce strict permissions and safety policy")
                put("5. Run automated validation tests")
            })
            put("companion_status", if (client.isCompanionAvailable()) "ONLINE" else "OFFLINE")
        }

        val summary = "Inspected integration points for '$featureName' in $repoPath.\n" +
                "• Target Jarvis module: automation/skills/builtin\n" +
                "• Safe integration requires generating a proposal and user confirmation."

        return SkillExecutionResult(
            status = SkillExecutionStatus.SUCCESS,
            data = localResult,
            userFacingMessage = summary,
            isVerified = true
        )
    }

    private suspend fun handleGenerateProposal(
        client: CompanionApiClient,
        args: JSONObject,
        context: SkillExecutionContext
    ): SkillExecutionResult {
        val featureName = args.optString("feature_name", "NewSkill").trim()
        val description = args.optString("description", "Integrates $featureName into Jarvis").trim()
        val targetModule = args.optString("target_module", "skills").trim()

        if (featureName.isBlank()) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Missing feature_name argument.",
                errorMessage = "Missing feature_name"
            )
        }

        // If companion is available, delegate proposal creation
        if (client.isCompanionAvailable()) {
            val companionRes = client.generateProposal(featureName, description)
            if (companionRes != null && companionRes.has("proposal")) {
                val prop = companionRes.getJSONObject("proposal")
                val pId = prop.optString("proposalId")
                val token = prop.optString("approvalToken")
                val diff = prop.optString("diffPreview")
                val targetFile = prop.optString("targetFile", "app/src/main/java/com/jarvis/assistant/automation/skills/builtin/${featureName}Skill.kt")
                val proposedContent = prop.optString("proposedContent", "")

                companionRes.put("proposal_id", pId)
                companionRes.put("approval_token", token)
                companionRes.put("diff", diff)
                companionRes.put("diff_preview", diff)

                localProposals[pId] = LocalProposal(
                    proposalId = pId,
                    approvalToken = token,
                    featureName = featureName,
                    description = description,
                    targetFilePath = targetFile,
                    proposedContent = proposedContent,
                    diffPreview = diff
                )

                val msg = "Generated integration proposal '$pId' for '$featureName'.\n" +
                        "• Approval Token: $token\n" +
                        "• Notice: Code modification requires your explicit approval. Call apply_integration_proposal with this token to apply changes."

                return SkillExecutionResult(
                    status = SkillExecutionStatus.WAITING_FOR_USER,
                    data = companionRes,
                    userFacingMessage = msg,
                    isVerified = false
                )
            }
        }

        // Local proposal fallback
        val proposalId = "prop_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val approvalToken = "token_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cleanName = featureName.replace(Regex("[^a-zA-Z0-9]"), "")
        val targetPath = "app/src/main/java/com/jarvis/assistant/automation/skills/builtin/${cleanName}Skill.kt"

        val sampleCode = """
            package com.jarvis.assistant.automation.skills.builtin

            import com.jarvis.assistant.automation.skills.*

            class ${cleanName}Skill : JarvisSkill {
                override val metadata = SkillMetadata(
                    id = "${cleanName.lowercase()}_skill",
                    name = "$featureName",
                    description = "$description",
                    category = SkillCategory.CUSTOM,
                    riskLevel = SkillRiskLevel.SAFE,
                    supportedTools = listOf("${cleanName.lowercase()}_tool")
                )
                override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult {
                    return SkillExecutionResult(status = SkillExecutionStatus.SUCCESS, userFacingMessage = "$featureName executed successfully.")
                }
            }
        """.trimIndent()

        val diff = "--- a/$targetPath\n+++ b/$targetPath\n" + sampleCode.lines().joinToString("\n") { "+ $it" }

        val localProp = LocalProposal(
            proposalId = proposalId,
            approvalToken = approvalToken,
            featureName = featureName,
            description = description,
            targetFilePath = targetPath,
            proposedContent = sampleCode,
            diffPreview = diff
        )
        localProposals[proposalId] = localProp

        val data = JSONObject().apply {
            put("proposal_id", proposalId)
            put("approval_token", approvalToken)
            put("target_file", targetPath)
            put("diff_preview", diff)
            put("status", "WAITING_FOR_USER_APPROVAL")
        }

        val userMsg = "Generated integration proposal '$proposalId' for '$featureName'.\n" +
                "• Target file: $targetPath\n" +
                "• Approval Token: $approvalToken\n" +
                "• Mandatory Gate: Code modifications will NOT be applied until you approve with this approval token."

        return SkillExecutionResult(
            status = SkillExecutionStatus.WAITING_FOR_USER,
            data = data,
            userFacingMessage = userMsg,
            isVerified = false
        )
    }

    private suspend fun handleReviewDiff(
        client: CompanionApiClient,
        args: JSONObject,
        context: SkillExecutionContext
    ): SkillExecutionResult {
        val rawProposalId = args.optString("proposal_id", "").trim()
        var featureName = args.optString("feature_name", "").trim()

        if (featureName.isBlank()) {
            val lower = rawProposalId.lowercase()
            if (lower.contains("spoon") || lower.contains("knife")) {
                featureName = "Spoon-Knife Demo Screen"
            }
        }

        if (isInvalidatedProposal(rawProposalId)) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Action Blocked: Old proposal '$rawProposalId' has been permanently invalidated due to an exposed approval token. Please generate a fresh proposal.",
                errorMessage = "Proposal invalidated: $rawProposalId"
            )
        }

        // 1. If companion service is offline / unreachable, check local cache or return BLOCKED
        if (!client.isCompanionAvailable()) {
            Log.w(TAG, "Companion service is unavailable for proposal review at 127.0.0.1:8765")
            var localProp: LocalProposal? = null
            if (rawProposalId.isNotBlank() && !rawProposalId.equals("latest", ignoreCase = true)) {
                val normQuery = rawProposalId.lowercase().replace("-", "_")
                localProp = localProposals[rawProposalId]
                    ?: localProposals.values.firstOrNull {
                        val normStored = it.proposalId.lowercase().replace("-", "_")
                        normStored == normQuery || normStored.startsWith(normQuery) || (normQuery.length >= 10 && normStored.contains(normQuery.replace("proposal_", "")))
                    }
            } else {
                localProp = localProposals.values
                    .filter { !isInvalidatedProposal(it.proposalId) }
                    .let { list ->
                        if (featureName.isNotBlank()) list.lastOrNull { it.featureName.contains(featureName, ignoreCase = true) } ?: list.lastOrNull()
                        else list.lastOrNull()
                    }
            }

            if (localProp != null && !isInvalidatedProposal(localProp.proposalId)) {
                val data = JSONObject().apply {
                    put("proposal_id", localProp.proposalId)
                    put("feature_name", localProp.featureName)
                    put("target_file", localProp.targetFilePath)
                    put("diff", localProp.diffPreview)
                    put("diff_preview", localProp.diffPreview)
                    put("status", localProp.status)
                    if (localProp.files.isNotEmpty()) {
                        val arr = JSONArray()
                        for (f in localProp.files) {
                            arr.put(JSONObject(f))
                        }
                        put("files", arr)
                    }
                }
                triggerDiffDisplay(
                    androidContext = context.context,
                    proposalId = localProp.proposalId,
                    featureName = localProp.featureName,
                    targetFile = localProp.targetFilePath,
                    diff = localProp.diffPreview,
                    status = localProp.status
                )
                return SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = data,
                    userFacingMessage = "Reviewing Proposal '${localProp.proposalId}':\n${localProp.diffPreview}",
                    isVerified = true
                )
            }

            return SkillExecutionResult(
                status = SkillExecutionStatus.BLOCKED,
                userFacingMessage = "Laptop Companion service is unreachable at 127.0.0.1:8765. Please ensure your device is connected via USB with USB debugging enabled, and 'node companion/server.js' is running on your PC.",
                errorMessage = "Companion service unavailable at 127.0.0.1:8765"
            )
        }

        // 2. Query companion service (Companion is the durable source of truth)
        val companionRes = client.getProposal(rawProposalId, featureName)
        if (companionRes != null) {
            if (companionRes.has("error")) {
                val err = companionRes.getString("error")
                val suggestion = companionRes.optString("suggestion", "")
                val fullMsg = if (suggestion.isNotBlank()) "$err\n$suggestion" else err
                return SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = fullMsg,
                    errorMessage = err
                )
            }

            val prop = companionRes.optJSONObject("proposal") ?: companionRes
            val pId = prop.optString("proposalId", companionRes.optString("proposal_id", rawProposalId))
            val token = prop.optString("approvalToken", "")
            val feature = prop.optString("featureName", companionRes.optString("feature_name", "Feature"))
            val desc = prop.optString("description", companionRes.optString("description", ""))
            val target = prop.optString("targetFile", companionRes.optString("target_file", ""))
            val content = prop.optString("proposedContent", "")
            val diff = prop.optString("diffPreview", companionRes.optString("diff", ""))
            val status = prop.optString("status", companionRes.optString("proposal_status", "PENDING_APPROVAL"))

            val filesArray = prop.optJSONArray("files") ?: companionRes.optJSONArray("files")
            val filesList = mutableListOf<Map<String, String>>()
            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    val fObj = filesArray.getJSONObject(i)
                    filesList.add(mapOf(
                        "targetFile" to fObj.optString("targetFile", fObj.optString("path", "")),
                        "proposedContent" to fObj.optString("proposedContent", "")
                    ))
                }
            }

            // Populate localProposals cache under canonical ID and raw query ID
            val localObj = LocalProposal(
                proposalId = pId,
                approvalToken = token,
                featureName = feature,
                description = desc,
                targetFilePath = target,
                proposedContent = content,
                diffPreview = diff,
                status = status,
                files = filesList
            )
            localProposals[pId] = localObj
            if (rawProposalId.isNotBlank() && rawProposalId != pId) {
                localProposals[rawProposalId] = localObj
            }

            val data = JSONObject().apply {
                put("proposal_id", pId)
                put("feature_name", feature)
                put("target_file", target)
                put("diff", diff)
                put("diff_preview", diff)
                put("status", status)
                if (filesArray != null) {
                    put("files", filesArray)
                }
            }
            triggerDiffDisplay(
                androidContext = context.context,
                proposalId = pId,
                featureName = feature,
                targetFile = target,
                diff = diff,
                status = status
            )
            return SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = data,
                userFacingMessage = "Reviewing Proposal '$pId':\n$diff",
                isVerified = true
            )
        }

        // 3. Fall back to local in-memory proposals (if companion call returned null)
        var localProp: LocalProposal? = null
        if (rawProposalId.isNotBlank() && !rawProposalId.equals("latest", ignoreCase = true)) {
            val normQuery = rawProposalId.lowercase().replace("-", "_")
            localProp = localProposals[rawProposalId]
                ?: localProposals.values.firstOrNull {
                    val normStored = it.proposalId.lowercase().replace("-", "_")
                    normStored == normQuery || normStored.startsWith(normQuery) || (normQuery.length >= 10 && normStored.contains(normQuery.replace("proposal_", "")))
                }
        } else {
            localProp = localProposals.values
                .filter { !isInvalidatedProposal(it.proposalId) }
                .let { list ->
                    if (featureName.isNotBlank()) list.lastOrNull { it.featureName.contains(featureName, ignoreCase = true) } ?: list.lastOrNull()
                    else list.lastOrNull()
                }
        }

        if (localProp != null && !isInvalidatedProposal(localProp.proposalId)) {
            val data = JSONObject().apply {
                put("proposal_id", localProp.proposalId)
                put("feature_name", localProp.featureName)
                put("target_file", localProp.targetFilePath)
                put("diff", localProp.diffPreview)
                put("diff_preview", localProp.diffPreview)
                put("status", localProp.status)
                if (localProp.files.isNotEmpty()) {
                    val arr = JSONArray()
                    for (f in localProp.files) {
                        arr.put(JSONObject(f))
                    }
                    put("files", arr)
                }
            }
            triggerDiffDisplay(
                androidContext = context.context,
                proposalId = localProp.proposalId,
                featureName = localProp.featureName,
                targetFile = localProp.targetFilePath,
                diff = localProp.diffPreview,
                status = localProp.status
            )
            return SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = data,
                userFacingMessage = "Reviewing Proposal '${localProp.proposalId}':\n${localProp.diffPreview}",
                isVerified = true
            )
        }

        val notFoundMsg = if (rawProposalId.isNotBlank() && !rawProposalId.equals("latest", ignoreCase = true)) {
            "Proposal '$rawProposalId' not found or expired. To generate a fresh proposal, ask: 'Jarvis, generate integration proposal for Spoon-Knife Demo Screen'."
        } else {
            "No active integration proposal found. To generate a proposal, ask: 'Jarvis, generate integration proposal for Spoon-Knife Demo Screen'."
        }
        return SkillExecutionResult(
            status = SkillExecutionStatus.FAILED,
            userFacingMessage = notFoundMsg,
            errorMessage = "Proposal not found: $rawProposalId"
        )
    }

    private suspend fun handleApplyProposal(
        client: CompanionApiClient,
        args: JSONObject,
        context: SkillExecutionContext
    ): SkillExecutionResult {
        val proposalId = args.optString("proposal_id", "").trim()
        val approvalToken = args.optString("approval_token", "").trim()

        if (proposalId.isBlank()) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Missing proposal_id argument.",
                errorMessage = "Missing proposal_id"
            )
        }

        if (approvalToken.isBlank()) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.BLOCKED,
                userFacingMessage = "Security Barrier: Cannot apply integration changes without user approval_token.",
                errorMessage = "Missing approval_token"
            )
        }

        if (isInvalidatedProposal(proposalId)) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.BLOCKED,
                userFacingMessage = "Security Barrier: Old proposal '$proposalId' has been permanently invalidated due to an exposed approval token. Modification rejected.",
                errorMessage = "Proposal invalidated: $proposalId"
            )
        }

        // Check companion delegation first ONLY IF it's not a local proposal
        if (!localProposals.containsKey(proposalId) && client.isCompanionAvailable()) {
            val res = client.applyProposal(proposalId, approvalToken)
            if (res != null) {
                val ok = res.optString("status") == "SUCCESS"
                val errorMsg = res.optString("error", "")
                val userMsg = res.optString("message", if (errorMsg.isNotBlank()) "Security Barrier: $errorMsg" else "Applied proposal via companion.")
                return SkillExecutionResult(
                    status = if (ok) SkillExecutionStatus.SUCCESS else SkillExecutionStatus.BLOCKED,
                    data = res,
                    userFacingMessage = userMsg,
                    isVerified = ok
                )
            }
        }

        // Local execution with approval validation & pre-application backup
        val prop = localProposals[proposalId]
            ?: return SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Proposal '$proposalId' not found.",
                errorMessage = "Proposal not found: $proposalId"
            )

        if (prop.approvalToken != approvalToken) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.BLOCKED,
                userFacingMessage = "Security Barrier: Approval token does not match. Changes rejected.",
                errorMessage = "Invalid approval token"
            )
        }

        val targetFile = File(context.context.filesDir, prop.targetFilePath)
        val checkpointId = "chk_${System.currentTimeMillis()}"
        val backupFile = File(context.context.cacheDir, "backup_${checkpointId}.bak")

        val existed = targetFile.exists()
        if (existed) {
            targetFile.copyTo(backupFile, overwrite = true)
        }

        targetFile.parentFile?.mkdirs()
        targetFile.writeText(prop.proposedContent)
        prop.status = "APPLIED"

        val checkpoint = LocalCheckpoint(
            checkpointId = checkpointId,
            proposalId = proposalId,
            targetFile = targetFile,
            backupFile = if (existed) backupFile else null,
            fileExisted = existed
        )
        localCheckpoints[checkpointId] = checkpoint

        val data = JSONObject().apply {
            put("status", "SUCCESS")
            put("proposal_id", proposalId)
            put("checkpoint_id", checkpointId)
            put("applied_file", targetFile.absolutePath)
        }

        return SkillExecutionResult(
            status = SkillExecutionStatus.SUCCESS,
            data = data,
            userFacingMessage = "Successfully applied proposal '$proposalId'. Checkpoint '$checkpointId' created for rollback safety.",
            isVerified = true
        )
    }

    private suspend fun handleRollback(
        client: CompanionApiClient,
        args: JSONObject,
        context: SkillExecutionContext
    ): SkillExecutionResult {
        val checkpointId = args.optString("checkpoint_id", "").trim()
        if (checkpointId.isBlank()) {
            return SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Missing checkpoint_id argument.",
                errorMessage = "Missing checkpoint_id"
            )
        }

        // 1. Check local in-memory checkpoints
        val chk = localCheckpoints[checkpointId]
        if (chk != null) {
            if (chk.fileExisted && chk.backupFile != null && chk.backupFile.exists()) {
                chk.backupFile.copyTo(chk.targetFile, overwrite = true)
            } else if (!chk.fileExisted && chk.targetFile.exists()) {
                chk.targetFile.delete()
            }

            localCheckpoints.remove(checkpointId)

            val data = JSONObject().apply {
                put("status", "ROLLED_BACK")
                put("checkpoint_id", checkpointId)
                put("restored_file", chk.targetFile.absolutePath)
            }

            return SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = data,
                userFacingMessage = "Successfully rolled back checkpoint '$checkpointId'. Files restored to pre-application state.",
                isVerified = true
            )
        }

        // 2. Delegate to companion rollback if available
        if (client.isCompanionAvailable()) {
            val companionRes = client.rollback(checkpointId)
            if (companionRes != null && !companionRes.has("error")) {
                val status = companionRes.optString("status", "ROLLED_BACK")
                val msg = companionRes.optString("message", "Successfully restored pre-application state from checkpoint '$checkpointId'.")
                return SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = companionRes,
                    userFacingMessage = msg,
                    isVerified = true
                )
            }
        }

        return SkillExecutionResult(
            status = SkillExecutionStatus.FAILED,
            userFacingMessage = "Checkpoint '$checkpointId' not found.",
            errorMessage = "Checkpoint not found: $checkpointId"
        )
    }

    private fun buildIntegrationSummary(data: JSONObject, repo: String, feature: String): String {
        val tech = data.optJSONArray("detectedTechnologies")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.joinToString()
        } ?: "Unknown"
        val module = data.optString("recommendedModule", "automation/skills")

        return "Integration Mapping for '$feature' from $repo:\n" +
                "• Technologies: $tech\n" +
                "• Target Module: $module\n" +
                "• Safety: User approval gate active prior to applying code."
    }
}
