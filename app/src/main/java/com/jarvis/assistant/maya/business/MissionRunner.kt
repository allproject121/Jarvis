package com.jarvis.assistant.maya.business

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MissionStep(
    val index: Int,
    val tool: String,
    val args: JSONObject,
    val why: String,
    var status: String = "pending", // pending | running | ok | failed | skipped
    var result: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("index", index).put("tool", tool).put("args", args).put("why", why)
        .put("status", status).put("result", result)

    companion object {
        fun fromJson(o: JSONObject) = MissionStep(o.optInt("index"), o.optString("tool"), o.optJSONObject("args") ?: JSONObject(),
            o.optString("why"), o.optString("status", "pending"), o.optString("result"))
    }
}

data class Mission(
    val id: String = UUID.randomUUID().toString().take(8),
    val goal: String,
    val kind: String = "mission", // mission | delegated
    val role: String = "",
    var status: String = "planning", // planning | running | done | failed | cancelled | recorded
    val steps: MutableList<MissionStep> = mutableListOf(),
    var summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("goal", goal); put("kind", kind); put("role", role); put("status", status)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        put("summary", summary); put("createdAt", createdAt); put("finishedAt", finishedAt)
    }

    fun log(): String {
        val sb = StringBuilder("[$id] $goal - $status\n")
        steps.forEach { sb.append("  ").append(it.index + 1).append(". ").append(it.tool).append(" [").append(it.status).append("] ")
            .append(it.result.take(160).replace('\n', ' ')).append('\n') }
        if (summary.isNotBlank()) sb.append("Summary: ").append(summary)
        return sb.toString().trimEnd()
    }

    companion object {
        fun fromJson(o: JSONObject): Mission {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = JsonFiles.toList(arr).map { MissionStep.fromJson(it) }.toMutableList()
            return Mission(o.optString("id"), o.optString("goal"), o.optString("kind", "mission"), o.optString("role"),
                o.optString("status"), steps, o.optString("summary"), o.optLong("createdAt"), o.optLong("finishedAt"))
        }
    }
}

/**
 * Background mission agent (Maya's run_mission): plans a sequence of real tool calls from the FULL
 * tool catalog, runs them one by one via the dispatcher feeding each result back, re-plans once when
 * a step fails, then reports. Limits: 25 steps, 10 minutes, cancellable.
 */
object MissionRunner {
    private const val TAG = "MissionRunner"
    const val FILE = "missions.json"
    const val DECISIONS_FILE = "decisions.json"
    private const val MAX_STEPS = 25
    private const val BUDGET_MS = 10 * 60 * 1000L
    private val running = ConcurrentHashMap<String, Job>()

    /** Tools a mission may never call (recursion, or things the owner must do themselves). */
    private val BLOCKED = setOf(
        "run_mission", "cancel_mission", "mission_status", "delegate_task", "run_agent_task",
        "publish_content", "post_to_social", "post_photo_to_social", "post_story", "send_lead_message",
        "send_report", "go_to_sleep", "handle_phone_call", "call_contact", "delete"
    )

    fun all(context: Context): List<Mission> = JsonFiles.toList(JsonFiles.readArray(context, FILE)).map { Mission.fromJson(it) }

    fun get(context: Context, id: String): Mission? = all(context).firstOrNull { it.id.equals(id, true) }

    fun isRunning(id: String) = running[id]?.isActive == true

    fun save(context: Context, m: Mission) {
        JsonFiles.updateArray(context, FILE) { arr ->
            val out = JSONArray().put(m.toJson())
            var n = 1
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == m.id) continue
                if (n++ >= 40) break
                out.put(o)
            }
            out
        }
    }

    fun start(context: Context, goal: String): Mission {
        val app = context.applicationContext
        val mission = Mission(goal = goal)
        save(app, mission)
        val job = BusinessRuntime.scope.launch {
            try {
                withTimeout(BUDGET_MS) { execute(app, mission) }
            } catch (e: CancellationException) {
                mission.status = if (mission.status == "done") "done" else "cancelled"
                if (mission.summary.isBlank()) mission.summary = "Mission rok di gayi / time khatam (10 min)."
                mission.finishedAt = System.currentTimeMillis()
                save(app, mission)
            } catch (e: Exception) {
                Log.e(TAG, "mission failed: ${e.message}", e)
                mission.status = "failed"
                mission.summary = "Mission fail: ${e.message}"
                mission.finishedAt = System.currentTimeMillis()
                save(app, mission)
            }
            val ok = mission.status == "done"
            BusinessEvents.notifyAndInject(
                app, 0x5300 + (mission.id.hashCode() and 0xFF),
                if (ok) "Mission done" else "Mission ${mission.status}", mission.summary.take(300),
                "Mission '${mission.goal.take(80)}' (id ${mission.id}) ${mission.status}: ${mission.summary.take(400)}. " +
                    "User ko 1-2 line me batao; jo hua wahi bolo, 'shuru kiya' ko 'ho gaya' mat bolo.",
                BusinessActivity.TAB_MISSIONS
            )
        }
        running[mission.id] = job
        job.invokeOnCompletion { running.remove(mission.id) }
        return mission
    }

    fun cancel(context: Context, id: String?): Mission? {
        val target = if (id.isNullOrBlank()) running.keys.firstOrNull() else id
        if (target == null) return null
        running[target]?.cancel()
        return get(context, target)
    }

    private fun catalog(context: Context): String {
        val schemas = try { JarvisToolDispatcher.getInstance(context).getAvailableToolSchemas() } catch (e: Exception) { emptyList() }
        val sb = StringBuilder()
        for (s in schemas) {
            val f = s.optJSONObject("function") ?: continue
            val name = f.optString("name")
            if (name in BLOCKED) continue
            val params = f.optJSONObject("parameters")?.optJSONObject("properties")
            val keys = params?.keys()?.asSequence()?.toList().orEmpty()
            sb.append("- ").append(name).append('(').append(keys.joinToString(", ")).append("): ")
                .append(f.optString("description").take(140).replace('\n', ' ')).append('\n')
            if (sb.length > 24000) break
        }
        return sb.toString()
    }

    private suspend fun plan(context: Context, goal: String, catalog: String, history: String): List<MissionStep> {
        val prompt = buildString {
            append("GOAL: ").append(goal).append("\n\n")
            if (history.isNotBlank()) append("STEPS ALREADY RUN (with results):\n").append(history).append("\nPlan only the REMAINING steps.\n\n")
            append("TOOLS (name(args): description):\n").append(catalog).append('\n')
            append("Return ONLY JSON: {\"steps\":[{\"tool\":\"<exact tool name>\",\"args\":{...},\"why\":\"<short>\"}]}. ")
            append("Use only listed tools, at most ").append(MAX_STEPS).append(" steps, fewest that reach the goal. ")
            append("Never publish, post, pay or message a customer - those need the owner. If nothing is left, return {\"steps\":[]}.")
        }
        val raw = BusinessRuntime.llm(context, "You are a precise task planner for a phone assistant. Output strict JSON only.", prompt, 0.2, 1800)
        return parsePlan(raw)
    }

    fun parsePlan(raw: String): List<MissionStep> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val arr: JSONArray = try {
            if (start >= 0 && end > start) JSONObject(raw.substring(start, end + 1)).optJSONArray("steps") ?: JSONArray()
            else JSONArray(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1))
        } catch (_: Exception) {
            try { JSONArray(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1)) } catch (_: Exception) { JSONArray() }
        }
        val out = ArrayList<MissionStep>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tool = o.optString("tool", o.optString("name")).trim()
            if (tool.isBlank()) continue
            val args = o.optJSONObject("args") ?: o.optJSONObject("arguments") ?: JSONObject()
            out.add(MissionStep(i, tool, args, o.optString("why")))
        }
        return out
    }

    private suspend fun execute(context: Context, m: Mission) {
        val catalog = catalog(context)
        val known = catalogNames(context)
        var queue = plan(context, m.goal, catalog, "").toMutableList()
        if (queue.isEmpty()) {
            m.status = "failed"; m.summary = "Plan nahi ban paaya - goal thoda saaf batao."
            m.finishedAt = System.currentTimeMillis(); save(context, m); return
        }
        m.status = "running"
        var replans = 0
        val dispatcher = JarvisToolDispatcher.getInstance(context)
        while (queue.isNotEmpty() && m.steps.size < MAX_STEPS) {
            val next = queue.removeAt(0)
            val step = next.copy(index = m.steps.size)
            m.steps.add(step)
            if (step.tool in BLOCKED || (known.isNotEmpty() && step.tool !in known)) {
                step.status = "skipped"
                step.result = if (step.tool in BLOCKED) "Mission ye tool nahi chala sakta (owner ka kaam)." else "Tool '${step.tool}' catalog me nahi hai."
                save(context, m)
                continue
            }
            step.status = "running"; save(context, m)
            // Feed earlier results into argument placeholders like {{step1}}.
            val argsText = step.args.toString().let { t ->
                var s = t
                m.steps.forEach { prev -> s = s.replace("{{step${prev.index + 1}}}", JSONObject.quote(prev.result.take(400)).trim('"')) }
                s
            }
            val args = try { JSONObject(argsText) } catch (_: Exception) { step.args }
            val res = try {
                dispatcher.dispatchTool(AiToolCall("mission-${m.id}-${step.index}", step.tool, args))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val ok = res?.isSuccess == true
            val msg = res?.resultJson?.let { j -> j.optString("message", "").ifBlank { j.toString() } } ?: "no result"
            step.status = if (ok) "ok" else "failed"
            step.result = (res?.errorMessage?.takeIf { !ok && it.isNotBlank() } ?: msg).take(1200)
            save(context, m)
            if (!ok && replans < 2) {
                replans++
                val history = m.steps.joinToString("\n") { "${it.index + 1}. ${it.tool} ${it.args} -> [${it.status}] ${it.result.take(300)}" }
                queue = plan(context, m.goal, catalog, history).toMutableList()
            }
        }
        val history = m.steps.joinToString("\n") { "${it.index + 1}. ${it.tool} -> [${it.status}] ${it.result.take(400)}" }
        m.summary = BusinessRuntime.llm(
            context, "Summarise what a background mission actually did, in 2-3 lines of simple Hinglish. Be honest about failures.",
            "GOAL: ${m.goal}\nSTEPS:\n$history", 0.2, 300
        ).ifBlank { "${m.steps.count { it.status == "ok" }}/${m.steps.size} steps ho gaye." }
        m.status = if (m.steps.any { it.status == "ok" }) "done" else "failed"
        m.finishedAt = System.currentTimeMillis()
        save(context, m)
    }

    private fun catalogNames(context: Context): Set<String> = try {
        JarvisToolDispatcher.getInstance(context).getAvailableToolSchemas()
            .mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet() + MayaToolRegistry.all().map { it.name }
    } catch (_: Exception) { emptySet() }

    /**
     * delegate_task: records a decision / delegated task. If the Brain pack is present at runtime
     * (com.jarvis.assistant.maya.brain.BrainToolPack, looked up reflectively - never referenced at
     * compile time) it is only noted; nothing else is executed.
     */
    fun delegate(context: Context, role: String, task: String): Mission {
        val brainPresent = try { Class.forName("com.jarvis.assistant.maya.brain.BrainToolPack"); true } catch (_: Throwable) { false }
        val m = Mission(goal = task, kind = "delegated", role = role, status = "recorded",
            summary = "Delegated to '$role'" + if (brainPresent) " (brain pack present - recorded for it)." else " (recorded).")
        save(context, m)
        JsonFiles.updateArray(context, DECISIONS_FILE) { arr ->
            arr.put(JSONObject().put("id", m.id).put("role", role).put("task", task).put("createdAt", m.createdAt)
                .put("status", "open").put("brainPresent", brainPresent))
            arr
        }
        return m
    }
}
