package com.jarvis.assistant.automation.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Task states matching the required unified lifecycle.
 */
enum class UnifiedTaskState {
    QUEUED,
    RUNNING,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_USER,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Persistent representation of an automation task.
 * Preserves task status across process recreations, app restarts, and background interruptions.
 */
data class PersistentTaskRecord(
    val taskId: String = UUID.randomUUID().toString(),
    val originalRequest: String,
    val normalizedGoal: String,
    var currentStep: String? = null,
    var status: String = UnifiedTaskState.QUEUED.name,
    val startedAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
    var recoveryCount: Int = 0,
    var lastError: String? = null,
    var recoveryAction: String? = null,
    var verificationEvidence: String? = null,
    var finalResult: String? = null,
    var targetApp: String? = null,
    var isNonIdempotent: Boolean = false,
    val completedSteps: MutableList<String> = mutableListOf(),
    val pendingSteps: MutableList<String> = mutableListOf()
)

/**
 * Thread-safe persistent storage for task lifecycle and history using Android SharedPreferences.
 */
class PersistentTaskStore private constructor(context: Context) {

    companion object {
        private const val TAG = "PersistentTaskStore"
        private const val PREFS_NAME = "jarvis_persistent_task_prefs"
        private const val KEY_ACTIVE_TASK_ID = "key_active_task_id"
        private const val KEY_TASK_HISTORY_JSON = "key_task_history_json"
        private const val MAX_HISTORY_SIZE = 25

        @Volatile
        private var instance: PersistentTaskStore? = null

        fun getInstance(context: Context): PersistentTaskStore {
            return instance ?: synchronized(this) {
                instance ?: PersistentTaskStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    init {
        // Detect interrupted active tasks from previous process lifecycle
        reconcileInterruptedTasks()
    }

    /**
     * Checks if a task was left in an active state when process was destroyed.
     * Marks it as FAILED with a clear interruption reason, preventing blind replay.
     */
    private fun reconcileInterruptedTasks() = synchronized(lock) {
        val activeId = prefs.getString(KEY_ACTIVE_TASK_ID, null) ?: return
        val history = loadHistoryInternal()
        val activeTask = history.find { it.taskId == activeId }

        if (activeTask != null) {
            val isActiveStatus = activeTask.status in setOf(
                UnifiedTaskState.QUEUED.name,
                UnifiedTaskState.RUNNING.name,
                UnifiedTaskState.RECOVERING.name
            )
            if (isActiveStatus) {
                Log.w(TAG, "Reconciling interrupted task ${activeTask.taskId}: process was killed/recreated while task was ${activeTask.status}")
                activeTask.status = UnifiedTaskState.FAILED.name
                activeTask.lastError = "Task was interrupted because the Android process was terminated or restarted."
                activeTask.recoveryAction = "Safely preserved task state. Refused blind re-execution of interrupted actions."
                activeTask.updatedAt = System.currentTimeMillis()
                saveHistoryInternal(history)
            }
        }
        prefs.edit().remove(KEY_ACTIVE_TASK_ID).apply()
    }

    /**
     * Creates and persists a new task.
     */
    fun createTask(
        taskId: String = UUID.randomUUID().toString(),
        request: String,
        isNonIdempotent: Boolean = false,
        targetApp: String? = null,
        initialPlan: List<String> = emptyList()
    ): PersistentTaskRecord = synchronized(lock) {
        val record = PersistentTaskRecord(
            taskId = taskId,
            originalRequest = request,
            normalizedGoal = request.trim().lowercase(),
            currentStep = initialPlan.firstOrNull() ?: "Initialize task",
            status = UnifiedTaskState.QUEUED.name,
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            targetApp = targetApp,
            isNonIdempotent = isNonIdempotent,
            pendingSteps = initialPlan.toMutableList()
        )

        val history = loadHistoryInternal()
        history.add(0, record)
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.lastIndex)
        }

        saveHistoryInternal(history)
        prefs.edit().putString(KEY_ACTIVE_TASK_ID, taskId).apply()
        Log.i(TAG, "Created persistent task: ${record.taskId} ('${record.originalRequest}')")
        record
    }

    /**
     * Updates an existing task's status and evidence.
     */
    fun updateTask(
        taskId: String,
        status: UnifiedTaskState? = null,
        currentStep: String? = null,
        lastError: String? = null,
        recoveryAction: String? = null,
        verificationEvidence: String? = null,
        finalResult: String? = null,
        retryCountIncrement: Boolean = false
    ): PersistentTaskRecord? = synchronized(lock) {
        val history = loadHistoryInternal()
        val task = history.find { it.taskId == taskId } ?: return null

        if (status != null) {
            task.status = status.name
            if (status == UnifiedTaskState.COMPLETED || status == UnifiedTaskState.FAILED || status == UnifiedTaskState.CANCELLED) {
                if (prefs.getString(KEY_ACTIVE_TASK_ID, null) == taskId) {
                    prefs.edit().remove(KEY_ACTIVE_TASK_ID).apply()
                }
            }
        }
        if (currentStep != null) task.currentStep = currentStep
        if (lastError != null) task.lastError = lastError
        if (recoveryAction != null) task.recoveryAction = recoveryAction
        if (verificationEvidence != null) task.verificationEvidence = verificationEvidence
        if (finalResult != null) task.finalResult = finalResult
        if (retryCountIncrement) task.retryCount++

        task.updatedAt = System.currentTimeMillis()
        saveHistoryInternal(history)
        Log.d(TAG, "Updated persistent task $taskId: status=${task.status}, step=${task.currentStep}")
        task
    }

    /**
     * Records a completed step.
     */
    fun recordStepCompleted(taskId: String, stepTitle: String, evidence: String?): PersistentTaskRecord? = synchronized(lock) {
        val history = loadHistoryInternal()
        val task = history.find { it.taskId == taskId } ?: return null

        if (!task.completedSteps.contains(stepTitle)) {
            task.completedSteps.add(stepTitle)
        }
        task.pendingSteps.remove(stepTitle)
        task.verificationEvidence = evidence
        task.updatedAt = System.currentTimeMillis()

        saveHistoryInternal(history)
        task
    }

    /**
     * Retrieves the currently active task, if any.
     */
    fun getActiveTask(): PersistentTaskRecord? = synchronized(lock) {
        val activeId = prefs.getString(KEY_ACTIVE_TASK_ID, null) ?: return null
        val history = loadHistoryInternal()
        val task = history.find { it.taskId == activeId } ?: return null

        val isActive = task.status in setOf(
            UnifiedTaskState.QUEUED.name,
            UnifiedTaskState.RUNNING.name,
            UnifiedTaskState.RECOVERING.name,
            UnifiedTaskState.WAITING_FOR_USER.name,
            UnifiedTaskState.WAITING_FOR_PERMISSION.name
        )

        if (isActive) task else null
    }

    /**
     * Retrieves the most recent task record.
     */
    fun getLatestTask(): PersistentTaskRecord? = synchronized(lock) {
        val history = loadHistoryInternal()
        history.firstOrNull()
    }

    /**
     * Retrieves recent failed tasks.
     */
    fun getFailedTasks(limit: Int = 5): List<PersistentTaskRecord> = synchronized(lock) {
        val history = loadHistoryInternal()
        history.filter { it.status == UnifiedTaskState.FAILED.name }.take(limit)
    }

    /**
     * Cancels the active task.
     */
    fun cancelActiveTask(reason: String = "User requested cancellation"): PersistentTaskRecord? = synchronized(lock) {
        val active = getActiveTask() ?: return null
        active.status = UnifiedTaskState.CANCELLED.name
        active.lastError = reason
        active.updatedAt = System.currentTimeMillis()

        val history = loadHistoryInternal()
        val idx = history.indexOfFirst { it.taskId == active.taskId }
        if (idx >= 0) history[idx] = active
        saveHistoryInternal(history)

        prefs.edit().remove(KEY_ACTIVE_TASK_ID).apply()
        Log.i(TAG, "Cancelled active task ${active.taskId}")
        active
    }

    /**
     * Evaluates whether a task can be safely resumed.
     * Strict safety rules:
     * - Cannot resume completed or cancelled tasks.
     * - Cannot auto-repeat non-idempotent actions (sending messages, dialing calls, deleting files).
     */
    fun canSafelyResume(taskId: String? = null): Pair<Boolean, String> = synchronized(lock) {
        val task = if (taskId != null) {
            loadHistoryInternal().find { it.taskId == taskId }
        } else {
            getActiveTask() ?: getLatestTask()
        }

        if (task == null) {
            return Pair(false, "No task found to resume.")
        }

        if (task.status == UnifiedTaskState.COMPLETED.name) {
            return Pair(false, "Task '${task.originalRequest}' is already completed.")
        }

        if (task.status == UnifiedTaskState.CANCELLED.name) {
            return Pair(false, "Task '${task.originalRequest}' was cancelled and cannot be resumed directly. Please submit a new instruction.")
        }

        if (task.isNonIdempotent) {
            return Pair(false, "Task '${task.originalRequest}' involves a sensitive action (such as sending a message or placing a call). To prevent duplicates, automatic resumption is blocked. Please confirm or re-state your command.")
        }

        if (task.status == UnifiedTaskState.WAITING_FOR_PERMISSION.name) {
            return Pair(false, "Task is blocked waiting for required permissions: ${task.lastError ?: "permissions missing"}. Please grant the required permissions in Settings first.")
        }

        if (task.retryCount >= 4) {
            return Pair(false, "Task exceeded maximum safe retry attempts (${task.retryCount}). Automatic resumption is blocked to prevent execution loops.")
        }

        return Pair(true, "Task '${task.originalRequest}' can be safely resumed.")
    }

    private fun loadHistoryInternal(): MutableList<PersistentTaskRecord> {
        val json = prefs.getString(KEY_TASK_HISTORY_JSON, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<PersistentTaskRecord>>() {}.type
            gson.fromJson<MutableList<PersistentTaskRecord>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing persistent task history: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveHistoryInternal(history: List<PersistentTaskRecord>) {
        try {
            val json = gson.toJson(history)
            prefs.edit().putString(KEY_TASK_HISTORY_JSON, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving persistent task history: ${e.message}")
        }
    }
}
