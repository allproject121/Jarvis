package com.jarvis.assistant.prompts

enum class StudioCategory(val label: String) {
    CODE("Code"),
    WRITING("Writing"),
    SUMMARIZE("Summarize"),
    EXPLAIN("Explain"),
    REWRITE("Rewrite"),
    RESEARCH("Research"),
    BRAINSTORM("Brainstorm"),
    EMAIL("Email"),
    SOCIAL_POST("Social post"),
    PROMPT_ENGINEERING("Prompt engineering"),
    JARVIS_PERSONAS("Jarvis Personas"),
    CUSTOM("Custom")
}

enum class PromptFieldType {
    TEXT,
    TEXT_AREA,
    CODE,
    SELECT
}

data class PromptField(
    val id: String,
    val label: String,
    val placeholder: String = "",
    val type: PromptFieldType = PromptFieldType.TEXT_AREA,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
    val isAdvanced: Boolean = false,
    val helperText: String = ""
)

data class PromptStructure(
    val role: String = "",
    val task: String = "",
    val constraints: List<String> = emptyList(),
    val outputFormat: String = ""
)

data class StudioTemplate(
    val id: String,
    val title: String,
    val description: String,
    val exampleUseCase: String,
    val category: StudioCategory,
    val qualityScore: Int = 90,
    val fields: List<PromptField>,
    val promptTemplate: String,
    val structure: PromptStructure = PromptStructure(),
    val exampleFilledPrompt: String = ""
) {
    fun render(values: Map<String, String>): String {
        var rendered = promptTemplate
        values.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
            rendered = rendered.replace("{{#$key}}$key: $value{{/$key}}", "$key: $value")
        }
        // Remove empty mustache blocks
        rendered = rendered.replace(Regex("\\{\\{#[^}]+\\}\\}[^\\{]*\\{\\{/[^}]+\\}\\}"), "")
        return rendered.trim()
    }
}
