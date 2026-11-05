package com.jarvis.assistant.androllm.agent.validation

import com.jarvis.assistant.androllm.agent.core.ToolCall
import com.jarvis.assistant.automation.guard.PromptInjectionDetector
import com.jarvis.assistant.androllm.agent.registry.ToolRegistry
import kotlinx.serialization.json.JsonObject
import com.jarvis.assistant.androllm.engine.support.AlLog as Timber

/**
 * Hardened execution pipeline — enforces strict order and never bypasses validation:
 *
 * Assistant -> Generate response -> Tool detector -> JSON validator -> Registry validation -> Argument validation -> Execute -> Return result -> Final assistant response
 *
 * Fail-safe: any validation failure stops execution, removes invalid call, and recovers gracefully.
 */
class ToolExecutionPipeline constructor(
    private val registry: ToolRegistry,
    private val validator: ToolCallValidator,
    private val logger: ToolExecutionLogger
) {

    sealed interface PipelineResult {
        data class Success(val call: ToolCall) : PipelineResult
        data class Rejected(
            val call: ToolCall,
            val reason: String,
            val retryable: Boolean,
            val validationErrors: List<String>
        ) : PipelineResult
        data class InvalidJson(
            val raw: String,
            val error: String
        ) : PipelineResult
    }

    /**
     * Validates a single tool call through the full pipeline.
     * Returns Success only if all stages pass; otherwise Rejected.
     */
    fun validateAndPrepare(rawJson: String, call: ToolCall): PipelineResult {
        // Stage 1: JSON validator — check raw is valid JSON (if provided)
        if (rawJson.isNotBlank()) {
            val jsonResult = validator.validateRawJson(rawJson)
            if (jsonResult is ValidationResult.Invalid) {
                logger.logValidation(call.name, jsonResult)
                return PipelineResult.InvalidJson(rawJson, jsonResult.firstError)
            }
        }

        // Stage 2: Tool detector — ensure call looks like a tool call (has name)
        if (call.name.isBlank()) {
            val err = "Empty tool name"
            logger.logValidation("<empty>", ValidationResult.Invalid(err, retryable = false))
            return PipelineResult.Rejected(call, err, retryable = false, listOf(err))
        }

        // Stage 3: Prompt injection check
        if (PromptInjectionDetector.isHallucinatedToolName(call.name, registry.all().map { it.spec.name }.toSet())) {
            // Still validate via registry — hallucinated names will be rejected there
            Timber.w("ToolExecutionPipeline: potential hallucinated tool name '${call.name}'")
        }

        // Stage 4: Registry validation + Stage 5: Argument validation (via ToolCallValidator)
        val validationResult = validator.validate(call)
        logger.logValidation(call.name, validationResult)

        return when (validationResult) {
            is ValidationResult.Valid -> PipelineResult.Success(call)
            is ValidationResult.Invalid -> PipelineResult.Rejected(
                call = call,
                reason = validationResult.firstError,
                retryable = validationResult.retryable,
                validationErrors = validationResult.errors
            )
        }
    }

    /**
     * Validates a batch through pipeline, returning only valid calls.
     * Invalid calls are logged and discarded per recovery policy.
     */
    fun filterValidCalls(calls: List<ToolCall>): List<ToolCall> {
        val valid = mutableListOf<ToolCall>()
        for (call in calls) {
            when (val result = validator.validate(call)) {
                is ValidationResult.Valid -> {
                    logger.logValidation(call.name, result)
                    valid += call
                }
                is ValidationResult.Invalid -> {
                    logger.logValidation(call.name, result)
                    Timber.w("ToolExecutionPipeline: rejecting invalid call '${call.name}': ${result.errors}")
                    // Recovery: remove invalid call, do not execute
                }
            }
        }
        return valid
    }

    /**
     * Recovery: given invalid calls, determine whether to ask user for missing info
     * or answer normally.
     */
    fun recoveryMessage(invalidCalls: List<Pair<ToolCall, ValidationResult.Invalid>>): String? {
        if (invalidCalls.isEmpty()) return null
        // If any call missing required params, ask for missing info
        val missingParams = invalidCalls.flatMap { (call, result) ->
            result.errors.filter { "Missing required" in it }.map { call.name to it }
        }
        if (missingParams.isNotEmpty()) {
            val paramNames = missingParams.map { it.second.substringAfter("'").substringBefore("'") }.distinct()
            return "I need more information to complete that action: missing ${paramNames.joinToString(", ")}. Could you provide it?"
        }
        // Otherwise, generic recovery — answer normally without tool
        return null
    }

    /**
     * Sequential multi-tool execution with fail-fast.
     * Executes tools one by one, passing validated outputs, stopping immediately if one fails.
     */
    suspend fun executeSequential(
        calls: List<ToolCall>,
        executor: suspend (ToolCall) -> com.jarvis.assistant.androllm.agent.core.ToolResult
    ): SequentialResult {
        val validated = filterValidCalls(calls)
        if (validated.isEmpty() && calls.isNotEmpty()) {
            return SequentialResult.AllRejected("All tool calls failed validation")
        }
        val results = mutableListOf<Pair<ToolCall, com.jarvis.assistant.androllm.agent.core.ToolResult>>()
        for (call in validated) {
            val start = System.currentTimeMillis()
            val result = try {
                executor(call)
            } catch (e: Exception) {
                com.jarvis.assistant.androllm.agent.core.ToolResult.Failure("Tool '${call.name}' threw: ${e.message}")
            }
            val duration = System.currentTimeMillis() - start
            logger.logExecution(call.name, duration, result.isSuccess, if (result is com.jarvis.assistant.androllm.agent.core.ToolResult.Failure) result.summary else null)
            results += call to result
            if (result is com.jarvis.assistant.androllm.agent.core.ToolResult.Failure) {
                Timber.w("ToolExecutionPipeline: stopping sequential execution — tool '${call.name}' failed: ${result.summary}")
                return SequentialResult.StoppedOnFailure(results, call, result)
            }
            // Pass validated output to next tool implicitly via results list
        }
        return SequentialResult.Completed(results)
    }

    sealed interface SequentialResult {
        data class Completed(val results: List<Pair<ToolCall, com.jarvis.assistant.androllm.agent.core.ToolResult>>) : SequentialResult
        data class StoppedOnFailure(
            val priorResults: List<Pair<ToolCall, com.jarvis.assistant.androllm.agent.core.ToolResult>>,
            val failedCall: ToolCall,
            val failure: com.jarvis.assistant.androllm.agent.core.ToolResult.Failure
        ) : SequentialResult
        data class AllRejected(val reason: String) : SequentialResult
    }
}
