package com.jarvis.assistant.ui.compose.feature.prompts

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/** UI state for the prompt library (search / category / favorites). */
internal data class PromptLibraryUiState(
    val query: String = "",
    val selectedCategory: PromptCategory = PromptCategory.ALL,
    val prompts: List<LibraryPrompt> = PromptLibraryData.prompts,
    val favorites: Set<String> = emptySet(),
    val categories: List<PromptCategory> = PromptCategory.entries
) {
    val favoriteCount: Int get() = favorites.size
}

/** UI state for the Studio wizard. */
internal data class StudioUiState(
    val selectedTemplate: StudioTemplate? = null,
    val wizardStep: WizardStep = WizardStep.TEMPLATE,
    val studioCategory: StudioCategory? = null,
    val formValues: Map<String, String> = emptyMap(),
    val previewMode: PreviewMode = PreviewMode.PREVIEW,
    val validationErrors: Map<String, String> = emptyMap(),
    val previewText: String = "",
    val rawPrompt: String = "",
    val history: List<PromptSession> = emptyList(),
    val settings: PromptStudioSettings = PromptStudioSettings()
)

/**
 * Port of AndroLLM's PromptLibraryViewModel without Hilt / DataStore /
 * kotlinx.serialization. Favorites, history and studio settings persist in a
 * private SharedPreferences file (JSON via org.json).
 */
internal class PromptsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Library ──────────────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow(PromptCategory.ALL)
    private val _favorites = MutableStateFlow(prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet())

    val uiState: StateFlow<PromptLibraryUiState> = combine(
        _searchQuery,
        _selectedCategory,
        _favorites
    ) { query, category, favorites ->
        val q = query.trim()
        val filtered = PromptLibraryData.prompts.filter { template ->
            val matchesCategory = category == PromptCategory.ALL || template.category == category
            val matchesQuery = q.isEmpty() ||
                template.title.contains(q, ignoreCase = true) ||
                template.description.contains(q, ignoreCase = true) ||
                template.text.contains(q, ignoreCase = true)
            matchesCategory && matchesQuery
        }
        PromptLibraryUiState(
            query = query,
            selectedCategory = category,
            prompts = filtered,
            favorites = favorites
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PromptLibraryUiState(favorites = _favorites.value))

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectCategory(category: PromptCategory) {
        _selectedCategory.value = category
    }

    /** Shared favorite set for library prompts and studio templates (as in AndroLLM). */
    fun toggleFavorite(id: String) {
        val current = _favorites.value
        val updated = if (id in current) current - id else current + id
        _favorites.value = updated
        prefs.edit().putStringSet(KEY_FAVORITES, HashSet(updated)).apply()
    }

    // ── Studio wizard ────────────────────────────────────────────────────────

    /** Manual edits / quality-helper output that replace the rendered template. */
    private var rawOverride: String? = null

    private val _studioUiState = MutableStateFlow(
        StudioUiState(
            settings = loadSettings(),
            history = loadHistory()
        )
    )
    val studioUiState: StateFlow<StudioUiState> = _studioUiState.asStateFlow()

    init {
        _studioUiState.value.settings.defaultTemplateId
            ?.let { StudioTemplateLibrary.byId(it) }
            ?.let { update { s -> s.copy(selectedTemplate = it) } }
    }

    /** Applies [transform] and recomputes the derived preview text. */
    private fun update(transform: (StudioUiState) -> StudioUiState) {
        val next = transform(_studioUiState.value)
        val preview = next.selectedTemplate?.let {
            runCatching { generatePreviewInternal(it, next.formValues, rawOverride) }.getOrDefault("")
        } ?: ""
        _studioUiState.value = next.copy(previewText = preview, rawPrompt = rawOverride ?: preview)
    }

    fun selectStudioCategory(category: StudioCategory?) = update { it.copy(studioCategory = category) }

    fun selectStudioTemplate(template: StudioTemplate) {
        rawOverride = null
        update {
            it.copy(
                selectedTemplate = template,
                formValues = emptyMap(),
                validationErrors = emptyMap(),
                wizardStep = WizardStep.FORM
            )
        }
    }

    fun updateField(fieldId: String, value: String) = update {
        it.copy(
            formValues = it.formValues + (fieldId to value),
            validationErrors = it.validationErrors - fieldId
        )
    }

    fun setPreviewMode(mode: PreviewMode) = update { it.copy(previewMode = mode) }

    fun setWizardStep(step: WizardStep) = update { it.copy(wizardStep = step) }

    fun nextStep() {
        val next = when (_studioUiState.value.wizardStep) {
            WizardStep.TEMPLATE -> WizardStep.FORM
            WizardStep.FORM -> if (validateForm()) WizardStep.PREVIEW else WizardStep.FORM
            WizardStep.PREVIEW -> WizardStep.SEND
            WizardStep.SEND -> WizardStep.SEND
        }
        setWizardStep(next)
    }

    fun previousStep() {
        val prev = when (_studioUiState.value.wizardStep) {
            WizardStep.TEMPLATE -> WizardStep.TEMPLATE
            WizardStep.FORM -> WizardStep.TEMPLATE
            WizardStep.PREVIEW -> WizardStep.FORM
            WizardStep.SEND -> WizardStep.PREVIEW
        }
        setWizardStep(prev)
    }

    fun resetForm() {
        rawOverride = null
        update { it.copy(formValues = emptyMap(), validationErrors = emptyMap()) }
    }

    fun resetWizard() {
        rawOverride = null
        update {
            it.copy(
                selectedTemplate = null,
                formValues = emptyMap(),
                validationErrors = emptyMap(),
                wizardStep = WizardStep.TEMPLATE,
                previewMode = PreviewMode.PREVIEW
            )
        }
    }

    fun validateForm(): Boolean {
        val state = _studioUiState.value
        val template = state.selectedTemplate ?: return false
        // A session reused from history carries its own final prompt; no form needed.
        if (rawOverride != null && state.formValues.isEmpty()) {
            update { it.copy(validationErrors = emptyMap()) }
            return true
        }
        val errors = template.fields
            .filter { it.required && state.formValues[it.id].isNullOrBlank() }
            .associate { it.id to "${it.label} is required" }
        update { it.copy(validationErrors = errors) }
        return errors.isEmpty()
    }

    fun generatePreview(): String {
        val state = _studioUiState.value
        val template = state.selectedTemplate ?: return ""
        return generatePreviewInternal(template, state.formValues, rawOverride)
    }

    fun applyQualityHelper(helper: QualityHelper) {
        rawOverride = helper.transform(generatePreview())
        update { it.copy(previewMode = PreviewMode.PREVIEW) }
    }

    fun updateRawPrompt(text: String) {
        rawOverride = text
        update { it }
    }

    fun getFinalPrompt(): String = generatePreview()

    /** Returns the final prompt and records it in history when enabled. */
    fun sendPrompt(): String {
        val final = getFinalPrompt()
        if (_studioUiState.value.settings.savePromptHistory) saveToHistory(final)
        return final
    }

    private fun saveToHistory(finalPrompt: String) {
        val template = _studioUiState.value.selectedTemplate ?: return
        val session = PromptSession(
            id = System.currentTimeMillis().toString(),
            templateId = template.id,
            templateTitle = template.title,
            finalPrompt = finalPrompt
        )
        val trimmed = (listOf(session) + _studioUiState.value.history).take(50)
        update { it.copy(history = trimmed) }
        persistHistory(trimmed)
    }

    fun clearHistory() {
        update { it.copy(history = emptyList()) }
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun duplicateSession(session: PromptSession) {
        val template = StudioTemplateLibrary.byId(session.templateId) ?: return
        rawOverride = session.finalPrompt
        update {
            it.copy(
                selectedTemplate = template,
                formValues = emptyMap(),
                validationErrors = emptyMap(),
                wizardStep = WizardStep.PREVIEW,
                previewMode = PreviewMode.PREVIEW
            )
        }
    }

    fun updateSettings(settings: PromptStudioSettings) {
        update { it.copy(settings = settings) }
        prefs.edit()
            .putString(KEY_DEFAULT_TEMPLATE, settings.defaultTemplateId)
            .putBoolean(KEY_AUTO_PREVIEW, settings.autoPreviewOn)
            .putBoolean(KEY_SHOW_ADVANCED, settings.showAdvancedFields)
            .putBoolean(KEY_SAVE_HISTORY, settings.savePromptHistory)
            .putBoolean(KEY_REFINEMENT, settings.enableRefinementSuggestions)
            .apply()
    }

    // ── Rendering (verbatim logic from AndroLLM) ─────────────────────────────

    private fun generatePreviewInternal(
        template: StudioTemplate,
        values: Map<String, String>,
        rawOverride: String?
    ): String {
        if (rawOverride != null) return rawOverride
        return runCatching {
            var prompt = template.promptTemplate
            for ((key, value) in values) {
                val hasValue = value.isNotBlank()
                val pattern = """\{\{#$key\}\}(.*?)\{\{/$key\}\}"""
                val conditionalRegex = runCatching {
                    Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL))
                }.getOrNull() ?: continue
                prompt = conditionalRegex.replace(prompt) { match ->
                    if (hasValue) {
                        var inner = match.groupValues[1]
                        inner = inner.replace("{{$key}}", value)
                        for ((k2, v2) in values) inner = inner.replace("{{$k2}}", v2)
                        inner
                    } else ""
                }
            }
            runCatching {
                prompt = Regex("""\{\{#\w+\}\}.*?\{\{/\w+\}\}""", setOf(RegexOption.DOT_MATCHES_ALL)).replace(prompt, "")
            }
            for ((key, value) in values) prompt = prompt.replace("{{$key}}", value)
            runCatching { prompt = Regex("""\{\{\w+\}\}""").replace(prompt, "") }
            prompt = prompt.trim().replace(Regex("\n{3,}"), "\n\n")
            prompt = tuneForModel(prompt, values["audience"] ?: values["model_target"] ?: "")
            prompt.trim()
        }.getOrElse {
            var fallback = template.promptTemplate
            for ((k, v) in values) fallback = fallback.replace("{{$k}}", v)
            fallback = runCatching { Regex("""\{\{.*?\}\}""").replace(fallback, "") }.getOrElse { fallback }
            fallback.trim()
        }
    }

    private fun tuneForModel(prompt: String, modelTarget: String): String {
        val lower = modelTarget.lowercase()
        val isSmall = lower.contains("small")
        val isLocal = lower.contains("local")
        var tuned = prompt
        if (isSmall || isLocal) {
            if (tuned.length > 1200) {
                tuned = tuned.replace(
                    Regex("Requirements:\n(- .*\n)+"),
                    "Requirements:\n- Preserve functionality\n- Be concise\n"
                )
            }
            tuned = tuned.replace("Please provide extensive detail, examples, and nuance.", "Be concise.")
        }
        return tuned
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun loadSettings(): PromptStudioSettings = PromptStudioSettings(
        defaultTemplateId = prefs.getString(KEY_DEFAULT_TEMPLATE, null),
        autoPreviewOn = prefs.getBoolean(KEY_AUTO_PREVIEW, true),
        showAdvancedFields = prefs.getBoolean(KEY_SHOW_ADVANCED, false),
        savePromptHistory = prefs.getBoolean(KEY_SAVE_HISTORY, true),
        enableRefinementSuggestions = prefs.getBoolean(KEY_REFINEMENT, true)
    )

    private fun loadHistory(): List<PromptSession> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PromptSession(
                id = o.optString("id"),
                templateId = o.optString("templateId"),
                templateTitle = o.optString("templateTitle"),
                finalPrompt = o.optString("finalPrompt"),
                timestamp = o.optLong("timestamp", 0L),
                isFavorite = o.optBoolean("isFavorite", false)
            )
        }
    }.getOrDefault(emptyList())

    private fun persistHistory(list: List<PromptSession>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("templateId", s.templateId)
                        .put("templateTitle", s.templateTitle)
                        .put("finalPrompt", s.finalPrompt)
                        .put("timestamp", s.timestamp)
                        .put("isFavorite", s.isFavorite)
                )
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "compose_prompts_studio"
        const val KEY_FAVORITES = "favorite_prompt_ids"
        const val KEY_HISTORY = "studio_history_json"
        const val KEY_DEFAULT_TEMPLATE = "studio_default_template"
        const val KEY_AUTO_PREVIEW = "studio_auto_preview"
        const val KEY_SHOW_ADVANCED = "studio_show_advanced"
        const val KEY_SAVE_HISTORY = "studio_save_history"
        const val KEY_REFINEMENT = "studio_enable_refinement"
    }
}
