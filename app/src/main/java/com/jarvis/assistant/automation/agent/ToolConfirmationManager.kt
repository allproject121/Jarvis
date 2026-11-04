package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.androllm.agent.core.ToolCall
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.agent.core.runtimePermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class PendingAction(
    val id: String,
    val toolName: String,
    val description: String,
    val speakableQuestion: String
)

/**
 * A tool action waiting for user approval (the AndroLLM agent-pipeline shape).
 * The chat UI renders a card from [ToolConfirmationManager.pending]; the voice
 * assistant reads [speakableQuestion] aloud and listens for a yes/no reply.
 */
data class PendingToolConfirmation(
    val id: String,
    val toolName: String,
    val toolDisplayName: String,
    val actionSummary: String,
    val speakableQuestion: String,
    /**
     * Android runtime permissions the tool needs before it can run. The chat
     * card requests them (system dialog) when the user approves while they
     * are still missing, so approving a message actually lets it through.
     */
    val requiredPermissions: List<String> = emptyList()
)

/** The [PendingAction] Jarvis' confirmation dialog renders for this request. */
fun PendingToolConfirmation.toPendingAction(): PendingAction = PendingAction(
    id = id,
    toolName = toolName,
    description = actionSummary,
    speakableQuestion = speakableQuestion
)

/**
 * Manages user approvals for high-risk device operations (making calls,
 * deleting items, executing macros, sensitive agent-pipeline tools).
 *
 * One process-wide hub shared by Jarvis' automation stack and the AndroLLM
 * agent pipeline:
 *
 * - Card: [awaitApproval] / [awaitDecision] publish the request to [pending]
 *   and suspend until the user taps Approve/Deny ([submitDecision],
 *   [confirm], [deny]) or the timeout denies it. The card is ALWAYS live.
 * - Voice: a voice surface may register a [setVoiceResponder] that speaks
 *   the question and listens for yes/no. It runs CONCURRENTLY with the card —
 *   the first decision (spoken or tapped) wins. A responder returning null
 *   abstains (muted / no TTS / nothing heard), leaving the card in charge.
 *   With no responder registered the behaviour is the plain card + timeout.
 */
object ToolConfirmationManager {

    private const val TIMEOUT_MS = 15_000L

    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    private val responses = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Optional voice-mode responder. Set by a voice surface when the
     * assistant is running; cleared on stop.
     *
     * Returns a DECISION (true = approve, false = deny) or null to ABSTAIN
     * (the voice surface could not ask — muted, no TTS, no reply heard) so the
     * card remains the decision surface.
     */
    @Volatile
    private var voiceResponder: (suspend (PendingToolConfirmation) -> Boolean?)? = null

    fun setVoiceResponder(responder: (suspend (PendingToolConfirmation) -> Boolean?)?) {
        voiceResponder = responder
    }

    /**
     * Suspends until the user decides on [action]. Returns true when approved;
     * times out (deny) after [timeoutMs] so a turn can never hang forever on
     * an unanswered card.
     */
    suspend fun awaitApproval(action: PendingAction, timeoutMs: Long = TIMEOUT_MS): Boolean =
        await(action, action.toToolConfirmation(), timeoutMs)

    /**
     * Agent-pipeline entry point: same as [awaitApproval] for
     * `confirmation.toPendingAction()`, but the voice responder (when one is
     * registered) receives the full [PendingToolConfirmation] — display name
     * and required permissions included.
     */
    suspend fun awaitDecision(confirmation: PendingToolConfirmation, timeoutMs: Long = TIMEOUT_MS): Boolean =
        await(confirmation.toPendingAction(), confirmation, timeoutMs)

    private suspend fun await(
        action: PendingAction,
        confirmation: PendingToolConfirmation,
        timeoutMs: Long
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        responses[action.id] = deferred
        _pending.value = action

        return try {
            val responder = voiceResponder
            coroutineScope {
                // The spoken question is ONE input; the card stays live as the
                // other. The first real decision wins and the mic/utterance is
                // cancelled immediately afterwards.
                val voiceJob = if (responder != null) launch {
                    val spoken = try {
                        // The voice surface must never run on the caller's
                        // dispatcher (a chat caller is on Main).
                        withContext(Dispatchers.Default) { responder(confirmation) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // A broken voice surface abstains — the card decides.
                        null
                    }
                    if (spoken != null) deferred.complete(spoken)
                } else null
                val decision = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
                voiceJob?.cancel()
                decision
            }
        } finally {
            responses.remove(action.id)
            if (_pending.value?.id == action.id) {
                _pending.value = null
            }
        }
    }

    fun submitDecision(id: String, approved: Boolean) {
        responses[id]?.complete(approved)
    }

    /** Approves the pending action with [id] (chat card "Approve"). */
    fun confirm(id: String) = submitDecision(id, true)

    /** Denies the pending action with [id] (chat card "Deny"). */
    fun deny(id: String) = submitDecision(id, false)

    /** Clears any pending card (conversation switch, turn cancel) — every waiter is denied. */
    fun cancelPending() {
        responses.values.forEach { it.complete(false) }
        responses.clear()
        _pending.value = null
    }

    private fun PendingAction.toToolConfirmation(): PendingToolConfirmation = PendingToolConfirmation(
        id = id,
        toolName = toolName,
        toolDisplayName = toolName.replace('_', ' '),
        actionSummary = description,
        speakableQuestion = speakableQuestion
    )
}

/** Builds a [PendingToolConfirmation] for a call the executor is about to run. */
fun buildConfirmation(call: ToolCall, spec: ToolSpec): PendingToolConfirmation {
    val displayName = spec.permission?.displayName ?: spec.name.replace('_', ' ')
    val args = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=${v.toString().take(40)}" }
    val summary = if (args.isBlank()) "Run $displayName" else "Run $displayName — $args"
    // Templates like "send the SMS to {phone}" get their args substituted so
    // the spoken question names the actual recipient/contact.
    var phrase = spec.confirmationPrompt.ifBlank { spec.name.replace('_', ' ') }
    for ((key, value) in call.arguments) {
        val placeholder = "{$key}"
        if (placeholder in phrase) {
            phrase = phrase.replace(placeholder, value.toString().trim('"').take(60))
        }
    }
    return PendingToolConfirmation(
        id = call.id,
        toolName = call.name,
        toolDisplayName = displayName,
        actionSummary = summary,
        speakableQuestion = "Do you want me to $phrase? Say yes to confirm, or no to cancel.",
        requiredPermissions = spec.permission?.runtimePermissions().orEmpty()
    )
}
