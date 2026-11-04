package com.jarvis.assistant.automation.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class PendingAction(
    val id: String,
    val toolName: String,
    val description: String,
    val speakableQuestion: String
)

/**
 * Manages user approvals for high-risk device operations (making calls, deleting items, executing macros).
 */
object ToolConfirmationManager {

    private const val TIMEOUT_MS = 15_000L

    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    private val responses = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun awaitApproval(action: PendingAction): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        responses[action.id] = deferred
        _pending.value = action

        return try {
            withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
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
}
