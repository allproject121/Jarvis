package com.jarvis.assistant.ui.compose.feature.prompts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Hand-off slot for a rendered prompt: the prompts screen stores the final text
 * here right before navigating to ComposeRoutes.CHAT. The chat screen may read
 * and clear it (`PendingPrompt.text?.let { ...; PendingPrompt.text = null }`).
 */
object PendingPrompt {
    @Volatile
    var text: String? = null
}

/** Studio categories (11), ported from AndroLLM. */
internal enum class StudioCategory(val label: String, val icon: ImageVector) {
    CODE("Code", Icons.Filled.Code),
    WRITING("Writing", Icons.Filled.EditNote),
    SUMMARIZE("Summarize", Icons.Filled.Summarize),
    EXPLAIN("Explain", Icons.Filled.MenuBook),
    REWRITE("Rewrite", Icons.Filled.Description),
    RESEARCH("Research", Icons.Filled.Search),
    BRAINSTORM("Brainstorm", Icons.Filled.Lightbulb),
    EMAIL("Email", Icons.Filled.Email),
    SOCIAL_POST("Social post", Icons.Filled.Share),
    PROMPT_ENGINEERING("Prompt engineering", Icons.Filled.AutoAwesome),
    CUSTOM("Custom", Icons.Filled.Build)
}

internal enum class PromptFieldType { TEXT, TEXT_AREA, CODE, SELECT, FILE }

internal data class PromptField(
    val id: String,
    val label: String,
    val placeholder: String = "",
    val type: PromptFieldType = PromptFieldType.TEXT_AREA,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
    val isAdvanced: Boolean = false,
    val helperText: String = ""
)

internal data class PromptStructure(
    val role: String = "",
    val task: String = "",
    val contextBlock: String = "",
    val constraints: List<String> = emptyList(),
    val outputFormat: String = "",
    val safetyRules: List<String> = emptyList()
)

internal data class StudioTemplate(
    val id: String,
    val title: String,
    val description: String,
    val exampleUseCase: String,
    val category: StudioCategory,
    val icon: ImageVector = category.icon,
    val qualityScore: Int = 85,
    val usefulnessTag: String = "High quality",
    val fields: List<PromptField>,
    val promptTemplate: String,
    val structure: PromptStructure = PromptStructure(),
    val exampleFilledPrompt: String = "",
    val supportedModels: List<String> = listOf("local", "cloud", "code", "small")
)

internal enum class WizardStep(val label: String) {
    TEMPLATE("Template"),
    FORM("Details"),
    PREVIEW("Preview"),
    SEND("Send")
}

internal enum class PreviewMode(val label: String) {
    FORM("Form"),
    PREVIEW("Preview"),
    RAW("Raw")
}

internal enum class QualityHelper(val label: String, val transform: (String) -> String) {
    SHORTER("Make it shorter", { p -> "$p\n\n[Instruction: Keep the answer concise and under 150 words.]" }),
    DETAILED("Make it more detailed", { p -> "$p\n\n[Instruction: Provide extensive detail, examples, and nuance.]" }),
    TECHNICAL("Make it more technical", { p -> "$p\n\n[Instruction: Use precise technical terminology and assume expert audience.]" }),
    BEGINNER("Beginner-friendly", { p -> "$p\n\n[Instruction: Explain in simple terms suitable for beginners, with analogies.]" }),
    CODE_EXAMPLES("Add code examples", { p -> "$p\n\n[Instruction: Include clear, runnable code examples with comments.]" }),
    BULLET_POINTS("Add bullet points", { p -> "$p\n\n[Instruction: Structure the answer with bullet points for clarity.]" }),
    STRICT("Make it strict", { p -> "$p\n\n[Instruction: Be strict, do not hallucinate, only state verified facts.]" }),
    CONCISE("Make it concise", { p -> "$p\n\n[Instruction: Be as concise as possible while retaining key information.]" })
}

internal data class PromptSession(
    val id: String,
    val templateId: String,
    val templateTitle: String,
    val finalPrompt: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)

internal data class PromptStudioSettings(
    val defaultTemplateId: String? = null,
    val autoPreviewOn: Boolean = true,
    val showAdvancedFields: Boolean = false,
    val savePromptHistory: Boolean = true,
    val enableRefinementSuggestions: Boolean = true
)

/** Library categories (filter chips). */
internal enum class PromptCategory(val label: String) {
    ALL("All"),
    GENERAL("General"),
    PROGRAMMING("Programming"),
    ANDROID("Android"),
    WRITING("Writing"),
    REASONING("Reasoning"),
    MATH("Math"),
    TRANSLATION("Translation")
}

/** A single prompt library entry (AndroLLM's `PromptTemplate`). */
internal data class LibraryPrompt(
    val id: String,
    val title: String,
    val description: String,
    val category: PromptCategory,
    val text: String
)
