package com.jarvis.assistant.voice.normalize

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-stage toggles for the text-normalization pipeline (ported from AndroLLM's
 * `NormalizationSettings`; mirrored 1:1 into Settings → Voice). All stages default
 * to enabled, which matches the behaviour Jarvis's pipeline had before the toggles
 * existed.
 */
data class NormalizationSettings(
    val enabled: Boolean = true,
    val numbers: Boolean = true,
    val dates: Boolean = true,
    val currency: Boolean = true,
    val units: Boolean = true,
    val math: Boolean = true,
    val emoji: Boolean = true,
    val urlsEmails: Boolean = true,
    val phones: Boolean = true,
    val abbreviations: Boolean = true,
    /** Built-in custom rule roll ([NormalizationRule.builtIns]). */
    val builtInRules: Boolean = true,
    /** User-defined regex rules ([NormalizationSettingsStore.customRules]). */
    val customRules: Boolean = true,
    /** Trace each stage to logcat for authoring. */
    val debug: Boolean = false
) {
    companion object {
        val ALL_ENABLED = NormalizationSettings()
    }
}

/** Persisted, user-defined rule (regex → replacement). */
data class CustomRuleSpec(
    val name: String,
    val regex: String,
    val replacement: String,
    val priority: Int = 100,
    val enabled: Boolean = true
) {
    fun toRule(): NormalizationRule? = NormalizationRule.of(name, regex, replacement, priority, enabled)
}

/**
 * SharedPreferences-backed store for [NormalizationSettings] and user rules.
 *
 * The pipeline is a process-wide singleton used from the TTS threads, so the
 * store keeps a volatile in-memory snapshot. It lazily loads itself from
 * `JarvisApp.instance` on first access, so persisted toggles apply even if the
 * settings screen was never opened in this process.
 */
object NormalizationSettingsStore {

    private const val TAG = "TtsNormalization"
    private const val PREFS = "jarvis_tts_normalization"
    private const val KEY_RULES = "custom_rules_json"

    private val _settings = MutableStateFlow(NormalizationSettings.ALL_ENABLED)
    val settings: StateFlow<NormalizationSettings> = _settings.asStateFlow()

    private val _customRules = MutableStateFlow<List<CustomRuleSpec>>(emptyList())
    val customRules: StateFlow<List<CustomRuleSpec>> = _customRules.asStateFlow()

    @Volatile private var loaded = false

    /** Bumped on every change so the pipeline can rebuild its compiled user rules. */
    @Volatile var revision: Int = 0
        private set

    fun current(): NormalizationSettings {
        ensureLoaded(null)
        return _settings.value
    }

    fun currentRules(): List<CustomRuleSpec> {
        ensureLoaded(null)
        return _customRules.value
    }

    fun load(context: Context) = ensureLoaded(context.applicationContext)

    fun update(context: Context, transform: (NormalizationSettings) -> NormalizationSettings) {
        ensureLoaded(context.applicationContext)
        val next = transform(_settings.value)
        _settings.value = next
        revision++
        prefs(context).edit().apply {
            putBoolean("enabled", next.enabled)
            putBoolean("numbers", next.numbers)
            putBoolean("dates", next.dates)
            putBoolean("currency", next.currency)
            putBoolean("units", next.units)
            putBoolean("math", next.math)
            putBoolean("emoji", next.emoji)
            putBoolean("urls_emails", next.urlsEmails)
            putBoolean("phones", next.phones)
            putBoolean("abbreviations", next.abbreviations)
            putBoolean("built_in_rules", next.builtInRules)
            putBoolean("custom_rules", next.customRules)
            putBoolean("debug", next.debug)
        }.apply()
    }

    /** Adds a rule; returns false (and stores nothing) when the regex does not compile. */
    fun addRule(context: Context, spec: CustomRuleSpec): Boolean {
        ensureLoaded(context.applicationContext)
        if (spec.toRule() == null) return false
        saveRules(context, _customRules.value + spec)
        return true
    }

    fun removeRule(context: Context, index: Int) {
        ensureLoaded(context.applicationContext)
        val list = _customRules.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        saveRules(context, list)
    }

    fun setRuleEnabled(context: Context, index: Int, enabled: Boolean) {
        ensureLoaded(context.applicationContext)
        val list = _customRules.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(enabled = enabled)
        saveRules(context, list)
    }

    private fun saveRules(context: Context, rules: List<CustomRuleSpec>) {
        _customRules.value = rules
        revision++
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("regex", r.regex)
                    .put("replacement", r.replacement)
                    .put("priority", r.priority)
                    .put("enabled", r.enabled)
            )
        }
        prefs(context).edit().putString(KEY_RULES, arr.toString()).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureLoaded(context: Context?) {
        if (loaded) return
        val ctx: Context = context
            ?: runCatching { com.jarvis.assistant.JarvisApp.instance as Context }.getOrNull()
            ?: return
        runCatching {
            val p = prefs(ctx)
            _settings.value = NormalizationSettings(
                enabled = p.getBoolean("enabled", true),
                numbers = p.getBoolean("numbers", true),
                dates = p.getBoolean("dates", true),
                currency = p.getBoolean("currency", true),
                units = p.getBoolean("units", true),
                math = p.getBoolean("math", true),
                emoji = p.getBoolean("emoji", true),
                urlsEmails = p.getBoolean("urls_emails", true),
                phones = p.getBoolean("phones", true),
                abbreviations = p.getBoolean("abbreviations", true),
                builtInRules = p.getBoolean("built_in_rules", true),
                customRules = p.getBoolean("custom_rules", true),
                debug = p.getBoolean("debug", false)
            )
            val raw = p.getString(KEY_RULES, null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                val rules = ArrayList<CustomRuleSpec>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    rules.add(
                        CustomRuleSpec(
                            name = o.optString("name"),
                            regex = o.optString("regex"),
                            replacement = o.optString("replacement"),
                            priority = o.optInt("priority", 100),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
                _customRules.value = rules
            }
            revision++
        }.onFailure { Log.w(TAG, "Failed to load normalization settings: ${it.message}") }
        loaded = true
    }
}
