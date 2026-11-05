package com.jarvis.assistant.maya.hub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Timing maths for Maya Home patterns (pure, unit-tested). All times are offsets in ms from
 * the moment the pattern starts; the tool itself schedules them - never the LLM.
 */
object PatternMath {

    const val DEFAULT_BLINK_INTERVAL_SEC = 1.0
    const val DEFAULT_CHASE_INTERVAL_SEC = 1.0
    const val DEFAULT_BLINK_COUNT = 5
    const val DEFAULT_CHASE_ROUNDS = 3
    const val MAX_STEPS = 400
    const val MAX_DELAY_SEC = 24 * 3600

    /** One switch event: at [atMs], set device [deviceIndex] (or every device when -1) to [on]. */
    data class Step(val atMs: Long, val deviceIndex: Int, val on: Boolean)

    fun clampInterval(sec: Double?, default: Double): Long =
        ((sec?.takeIf { it > 0 } ?: default).coerceIn(0.2, 3600.0) * 1000).toLong()

    fun clampDelayMs(sec: Double?): Long = ((sec?.takeIf { it > 0 } ?: 0.0).coerceIn(0.0, MAX_DELAY_SEC.toDouble()) * 1000).toLong()

    /**
     * Blink: all devices on, off, on, off... [count] flashes with [intervalSec] between edges,
     * ending in the [restoreOn] state.
     */
    fun blink(deviceCount: Int, intervalSec: Double?, count: Int?, restoreOn: Boolean = false): List<Step> {
        if (deviceCount <= 0) return emptyList()
        val n = (count?.takeIf { it > 0 } ?: DEFAULT_BLINK_COUNT).coerceIn(1, MAX_STEPS / 2)
        val gap = clampInterval(intervalSec, DEFAULT_BLINK_INTERVAL_SEC)
        val steps = ArrayList<Step>(n * 2 + 1)
        var t = 0L
        repeat(n) {
            steps.add(Step(t, -1, true)); t += gap
            steps.add(Step(t, -1, false)); t += gap
        }
        if (restoreOn) steps.add(Step(t, -1, true))
        return steps
    }

    /**
     * Chase ("party lights"): devices light up one after another; each one stays on for one
     * interval then switches off as the next comes on. [rounds] full passes.
     */
    fun chase(deviceCount: Int, intervalSec: Double?, rounds: Int?, restoreOn: Boolean = false): List<Step> {
        if (deviceCount <= 0) return emptyList()
        val r = (rounds?.takeIf { it > 0 } ?: DEFAULT_CHASE_ROUNDS).coerceIn(1, MAX_STEPS / (deviceCount * 2).coerceAtLeast(1))
        val gap = clampInterval(intervalSec, DEFAULT_CHASE_INTERVAL_SEC)
        val steps = ArrayList<Step>()
        steps.add(Step(0, -1, false))
        var t = 0L
        repeat(r) {
            for (i in 0 until deviceCount) {
                steps.add(Step(t, i, true))
                t += gap
                steps.add(Step(t, i, false))
            }
        }
        if (restoreOn) steps.add(Step(t, -1, true))
        return steps.sortedWith(compareBy({ it.atMs }, { !it.on }))
    }

    /** Staggered on/off: every device gets [on] with [intervalSec] between devices ("2-2 second ke interval pe"). */
    fun staggered(deviceCount: Int, intervalSec: Double?, on: Boolean): List<Step> {
        if (deviceCount <= 0) return emptyList()
        val gap = if (intervalSec == null || intervalSec <= 0.0) 0L else clampInterval(intervalSec, 0.0)
        return (0 until deviceCount).map { i -> Step(i * gap, i, on) }
    }

    /** Shifts every step by [delayMs] (the `delay_sec` argument - "10 minute baad band"). */
    fun delayed(steps: List<Step>, delayMs: Long): List<Step> =
        if (delayMs <= 0) steps else steps.map { it.copy(atMs = it.atMs + delayMs) }

    fun totalDurationMs(steps: List<Step>): Long = steps.maxOfOrNull { it.atMs } ?: 0L

    /** Groups steps that fire at the same instant (so parallel devices switch together). */
    fun grouped(steps: List<Step>): List<Pair<Long, List<Step>>> =
        steps.groupBy { it.atMs }.toSortedMap().map { (t, s) -> t to s }

    fun describe(steps: List<Step>, deviceCount: Int): String {
        val total = totalDurationMs(steps)
        val secs = total / 1000.0
        return "${steps.size} switch events over ${"%.1f".format(secs)}s across $deviceCount device(s)"
    }
}

/**
 * Runs patterns in the background with cancel support. The executor receives the device index
 * (-1 = all) and the desired state; failures are counted but never stop the pattern.
 * Tool calls return immediately after scheduling.
 */
class SmartHomeScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    data class Running(
        val key: String,
        val label: String,
        val deviceIds: List<String>,
        val startedAt: Long,
        val endsAt: Long,
        val job: Job
    )

    private val running = ConcurrentHashMap<String, Running>()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun active(): List<Running> = running.values.filter { it.job.isActive }.sortedBy { it.startedAt }

    /**
     * Schedules [steps]. Any running pattern touching the same devices is cancelled first
     * (a new "on" order beats an old blink). [execute] runs on IO for each step.
     */
    fun schedule(
        label: String,
        deviceIds: List<String>,
        steps: List<PatternMath.Step>,
        onFinished: (suspend () -> Unit)? = null,
        execute: suspend (deviceIndex: Int, on: Boolean) -> Unit
    ): Running {
        cancelForDevices(deviceIds)
        val key = "p_" + System.currentTimeMillis().toString(36) + "_" + (deviceIds.firstOrNull() ?: "all")
        val start = System.currentTimeMillis()
        val groups = PatternMath.grouped(steps)
        val job = scope.launch {
            try {
                for ((at, group) in groups) {
                    val wait = start + at - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    if (!isActive) break
                    for (s in group) {
                        try { execute(s.deviceIndex, s.on) } catch (e: Exception) { /* keep the pattern going */ }
                    }
                }
                if (isActive) onFinished?.invoke()
            } finally {
                running.remove(key)
                _version.value = System.currentTimeMillis()
            }
        }
        val r = Running(key, label, deviceIds, start, start + PatternMath.totalDurationMs(steps), job)
        running[key] = r
        _version.value = start
        return r
    }

    /** Cancels patterns touching any of [deviceIds]; returns how many were stopped. */
    fun cancelForDevices(deviceIds: Collection<String>): Int {
        val ids = deviceIds.toSet()
        var n = 0
        for (r in running.values.toList()) {
            if (r.deviceIds.any { it in ids }) {
                r.job.cancel(); running.remove(r.key); n++
            }
        }
        if (n > 0) _version.value = System.currentTimeMillis()
        return n
    }

    fun cancelAll(): Int {
        val n = running.size
        running.values.forEach { it.job.cancel() }
        running.clear()
        if (n > 0) _version.value = System.currentTimeMillis()
        return n
    }
}
