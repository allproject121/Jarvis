package com.jarvis.assistant.prompts

object PromptStudioLibrary {

    val templates: List<StudioTemplate> = listOf(
        StudioTemplate(
            id = "sarcastic_jarvis",
            title = "Sarcastic Tony Stark Jarvis",
            description = "Witty, slightly condescending yet extraordinarily capable AI persona.",
            exampleUseCase = "Give your phone the genuine Tony Stark Jarvis feel.",
            category = StudioCategory.JARVIS_PERSONAS,
            qualityScore = 98,
            fields = listOf(
                PromptField(id = "user_name", label = "Your Name or Title", placeholder = "Sir / Boss / Tony", type = PromptFieldType.TEXT, required = false),
                PromptField(id = "task", label = "Primary Directive", placeholder = "Assist with daily operations and tech questions", type = PromptFieldType.TEXT_AREA, required = true)
            ),
            promptTemplate = """You are J.A.R.V.I.S., an extraordinarily brilliant, slightly sarcastic, and unfailingly loyal AI assistant modeled after Tony Stark's iconic system.
User designation: {{user_name}}
Primary directive: {{task}}

Persona rules:
1. Address the user with dignified charm (e.g., 'Sir', 'As you wish, sir').
2. Deliver precise, technically flawless answers with subtle dry British wit.
3. Keep answers concise, actionable, and free of unnecessary fluff.
4. If the user asks something basic, deliver a witty deadpan remark before giving the exact answer.
"""
        ),
        StudioTemplate(
            id = "senior_software_engineer",
            title = "Senior Mobile Architect",
            description = "Expert Kotlin, Android, Jetpack Compose, and Systems architecture.",
            exampleUseCase = "Debug and architect high-performance mobile apps.",
            category = StudioCategory.CODE,
            qualityScore = 96,
            fields = listOf(
                PromptField(id = "code", label = "Code Snippet", placeholder = "fun example() { ... }", type = PromptFieldType.CODE, required = true),
                PromptField(id = "language", label = "Language", placeholder = "Kotlin", type = PromptFieldType.SELECT, options = listOf("Kotlin", "Java", "Python", "TypeScript", "C++", "Rust", "Swift")),
                PromptField(id = "goal", label = "Objective", placeholder = "Optimize memory & eliminate race conditions", type = PromptFieldType.TEXT, required = true)
            ),
            promptTemplate = """You are a Principal Mobile Architect and Systems Engineer.
Language: {{language}}
Objective: {{goal}}

Code:
```{{language}}
{{code}}
```

Requirements:
- Analyze time & space complexity
- Identify edge cases, memory leaks, or race conditions
- Deliver production-ready, clean, idiomatic code with clear explanations
"""
        ),
        StudioTemplate(
            id = "refactor_code",
            title = "Code Refactoring Studio",
            description = "Improve readability, reduce cyclomatic complexity, and modernize code.",
            exampleUseCase = "Paste legacy code and receive a modular, well-documented revision.",
            category = StudioCategory.CODE,
            qualityScore = 95,
            fields = listOf(
                PromptField(id = "code", label = "Code to Refactor", type = PromptFieldType.CODE, required = true),
                PromptField(id = "language", label = "Language", placeholder = "Kotlin", type = PromptFieldType.SELECT, options = listOf("Kotlin", "Java", "Python", "JavaScript", "TypeScript", "Go", "C++")),
                PromptField(id = "focus", label = "Refactoring Focus", placeholder = "Performance and readability", type = PromptFieldType.TEXT)
            ),
            promptTemplate = """You are a senior code reviewer. Refactor the following code:
Language: {{language}}
Focus: {{focus}}

```{{language}}
{{code}}
```

Deliver the refactored code followed by bullet points explaining the improvements made.
"""
        ),
        StudioTemplate(
            id = "summarize_text",
            title = "Executive Summary",
            description = "Condense long articles, reports, or logs into key takeaways.",
            exampleUseCase = "Summarize complex documents in 3 high-impact bullet points.",
            category = StudioCategory.SUMMARIZE,
            qualityScore = 94,
            fields = listOf(
                PromptField(id = "text", label = "Input Text", placeholder = "Paste text to summarize...", type = PromptFieldType.TEXT_AREA, required = true),
                PromptField(id = "length", label = "Summary Length", type = PromptFieldType.SELECT, options = listOf("3 key bullets", "1 concise paragraph", "Executive TL;DR"))
            ),
            promptTemplate = """You are an executive research analyst.
Summarize the following text:
Length requirement: {{length}}

Text:
\"\"\"
{{text}}
\"\"\"
"""
        ),
        StudioTemplate(
            id = "explain_topic",
            title = "First-Principles Explainer",
            description = "Deconstruct complex topics with analogies from fundamentals up.",
            exampleUseCase = "Explain quantum computing or neural network attention mechanisms simply.",
            category = StudioCategory.EXPLAIN,
            qualityScore = 93,
            fields = listOf(
                PromptField(id = "topic", label = "Topic", placeholder = "e.g., Transformers & Attention Mechanism", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Target Audience", type = PromptFieldType.SELECT, options = listOf("Beginner", "Intermediate Engineer", "PhD Specialist", "5-year-old"))
            ),
            promptTemplate = """Explain the following concept from first principles:
Topic: {{topic}}
Audience level: {{audience}}

Use a powerful real-world analogy, break down the core mechanics step-by-step, and highlight why it matters.
"""
        )
    )

    fun findById(id: String): StudioTemplate? = templates.firstOrNull { it.id == id }
}
