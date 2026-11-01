package com.jarvis.assistant.androllm.agent.confirmation

import com.jarvis.assistant.androllm.agent.core.ToolCall
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.agent.core.runtimePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tool action waiting for user approval. The chat UI renders a card from
 * [pending]; the voice assistant reads [speakableQuestion] aloud and listens
 * for a yes/no reply.
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

/**
 * Central hub for high-risk action confirmations, shared by the chat UI and
 * the voice assistant.
 *
 * - Chat: [awaitDecision] always publishes the request to [pending] and
 *   suspends until the user taps Approve/Deny on the confirmation card
 *   ([confirm]/[deny]). The card is ALWAYS live.
 * - Voice: the voice service registers a [voiceResponder] that speaks the
 *   question and listens for yes/no. It runs CONCURRENTLY with the card — the
 *   first decision (spoken or tapped) wins. A responder returning null
 *   abstains (e.g. muted / no TTS), leaving the card in charge.
 *
 * The chat card can never be bypassed by the voice assistant running in the
 * background: approving in chat resumes the executor even while the assistant
 * is speaking the same question.
 */
class ToolConfirmationManager constructor() {

    /** The action currently awaiting approval, or null when none. */
    private val _pending = MutableStateFlow<PendingToolConfirmation?>(null)
    val pending: StateFlow<PendingToolConfirmation?> = _pending

    private val responses = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Optional voice-mode responder. Set by a voice surface when the
     * assistant is running; cleared on stop.
     *
     * Returns a DECISION (true = approve, false = deny) or null to ABSTAIN
     * (the voice surface could not ask — muted, no TTS, no reply heard) so the
     * chat card remains the decision surface.
     */
    /**
     * When true (default) every request is also shown through Jarvis' own
     * confirmation dialog — the dialog that is actually mounted in the app.
     */
    @Volatile
    var bridgeToJarvisDialog: Boolean = true

    @Volatile
    private var voiceResponder: (suspend (PendingToolConfirmation) -> Boolean?)? = null

    fun setVoiceResponder(responder: (suspend (PendingToolConfirmation) -> Boolean?)?) {
        voiceResponder = responder
    }

    /**
     * Suspends until the user decides. Returns true when approved. Times out
     * (deny) after [CONFIRMATION_TIMEOUT_MS] so a turn can never hang forever
     * on an unanswered card.
     */
    suspend fun awaitDecision(confirmation: PendingToolConfirmation): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        responses[confirmation.id] = deferred
        _pending.value = confirmation
        return try {
            val responder = voiceResponder
            coroutineScope {
                // Jarvis integration: the approve/deny dialog Jarvis already
                // mounts (SectionAutomation.ToolConfirmationPrompt, driven by
                // Jarvis' own automation.agent.ToolConfirmationManager) is the
                // card surface for the agent pipeline too. Its decision (or
                // its timeout, a safe deny) completes this request; a tap on
                // any surface that renders [pending] still wins if first.
                val jarvisCardJob = if (bridgeToJarvisDialog) launch {
                    val approved = try {
                        com.jarvis.assistant.automation.agent.ToolConfirmationManager.awaitApproval(
                            com.jarvis.assistant.automation.agent.PendingAction(
                                id = JARVIS_ID_PREFIX + confirmation.id,
                                toolName = confirmation.toolName,
                                description = confirmation.actionSummary,
                                speakableQuestion = confirmation.speakableQuestion
                            )
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        false
                    }
                    deferred.complete(approved)
                } else null
                // The spoken question is ONE input; the chat card stays
                // live as the other. The first real decision wins and the
                // mic/utterance is cancelled immediately afterwards.
                val voiceJob = if (responder != null) launch {
                    val spoken = try {
                        // The voice surface must never run on the caller's
                        // dispatcher (a chat caller is on Main).
                        withContext(Dispatchers.Default) { responder(confirmation) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // A broken voice surface abstains — the card decides.
                        null
                    }
                    if (spoken != null) deferred.complete(spoken)
                } else null
                val decision =
                    withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { deferred.await() } ?: false
                voiceJob?.cancel()
                if (jarvisCardJob?.isActive == true) {
                    // Decided on another surface — close the Jarvis dialog.
                    com.jarvis.assistant.automation.agent.ToolConfirmationManager
                        .submitDecision(JARVIS_ID_PREFIX + confirmation.id, decision)
                    jarvisCardJob.cancel()
                }
                decision
            }
        } finally {
            responses.remove(confirmation.id)
            if (_pending.value?.id == confirmation.id) _pending.value = null
        }
    }

    /** Approves the pending action with [id] (chat card "Approve"). */
    fun confirm(id: String) {
        responses[id]?.complete(true)
    }

    /** Denies the pending action with [id] (chat card "Deny"). */
    fun deny(id: String) {
        responses[id]?.complete(false)
    }

    /** Clears any pending card (conversation switch, turn cancel). */
    fun cancelPending() {
        responses.values.forEach { it.complete(false) }
        responses.clear()
        _pending.value = null
    }

    companion object {
        /** How long a chat confirmation card stays live before auto-denying. */
        const val CONFIRMATION_TIMEOUT_MS = 300_000L

        /** Id prefix used for requests mirrored into Jarvis' dialog. */
        private const val JARVIS_ID_PREFIX = "agent_"
    }
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
