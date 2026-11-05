package com.jarvis.assistant.androllm.runtime

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central registry of every app runtime — the single place that knows which
 * subsystems exist and whether they are usable (ported from AndroLLM
 * `core/runtime/RuntimeRegistry`; Hilt multibinding replaced by an explicit
 * set, see [JarvisRuntimes]).
 *
 * Independence contract: [statuses] evaluates every runtime inside its own
 * try/catch, so a throwing runtime is reported as failed instead of breaking
 * the other entries. This registry never starts, stops or configures a runtime.
 */
class RuntimeRegistry(runtimes: Collection<Runtime>) {

    private val runtimes: List<Runtime> = runtimes.distinctBy { it.id }

    /** All registered runtimes, stable order by id. */
    val all: List<Runtime> get() = runtimes.sortedBy { it.id }

    val size: Int get() = runtimes.size

    fun byId(id: String): Runtime? = all.firstOrNull { it.id == id }

    fun byCategory(category: RuntimeCategory): List<Runtime> = all.filter { it.category == category }

    /**
     * Evaluates every runtime's availability, each isolated from the others.
     * A runtime whose [Runtime.status] throws is reported as failed with the
     * exception text — it never aborts the sweep.
     */
    suspend fun statuses(): List<Pair<Runtime, RuntimeStatus>> = withContext(Dispatchers.Default) {
        all.map { runtime ->
            runtime to try {
                runtime.status()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RuntimeStatus(
                    available = false,
                    summary = "Status check failed",
                    detail = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: RuntimeRegistry? = null

        /** Process-wide registry of Jarvis' subsystems ([JarvisRuntimes.all]). */
        fun getInstance(context: Context): RuntimeRegistry =
            instance ?: synchronized(this) {
                instance ?: RuntimeRegistry(JarvisRuntimes.all(context.applicationContext)).also { instance = it }
            }
    }
}
