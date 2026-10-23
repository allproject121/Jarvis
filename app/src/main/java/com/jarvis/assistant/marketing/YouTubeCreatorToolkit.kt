package com.jarvis.assistant.marketing

import android.content.Context
import com.jarvis.assistant.ai.AiTextSynthesizer

object YouTubeCreatorToolkit {

    /**
     * Synthesizes a retention-optimized creator script using active AI (Gemini / OpenAI / LocalHost)
     * with graceful zero-delay fallback to high-retention structural blueprint.
     */
    suspend fun generateDynamicScript(
        context: Context,
        topic: String,
        format: String = "Reel",
        inHinglish: Boolean = true
    ): String {
        val cleanTopic = topic.trim().ifBlank { "Viral Tech Innovation" }
        val langStr = if (inHinglish) "Hinglish (Hindi written in clean English script, highly conversational & relatable)" else "English"

        val systemInstruction = "You are JARVIS, an elite YouTube strategist and viral scriptwriter for Tony Stark. You craft ultra-high-retention content scripts tailored for viral reach."
        val prompt = """
Write a complete, ready-to-record $format script on the topic: "$cleanTopic".
Language: $langStr.

Structure:
1. 🔥 THE HOOK (0 - 3s): Extreme curiosity or bold pattern-interrupt line.
2. ⚠️ THE PROBLEM / TENSION (3 - 15s): Why 99% of people struggle or make this mistake.
3. 💡 THE CORE VALUE / SOLUTION (15 - 45s): 2 to 3 practical, step-by-step actionable insights or tools.
4. 🚀 THE SECRET PRO TIP: One counter-intuitive insight that guarantees retention.
5. 📲 CALL TO ACTION (CTA): Viral save & share prompt.

Format cleanly with [Visual Cues] and [Spoken Words]. Keep it snappy, punchy, and modern.
        """.trimIndent()

        return AiTextSynthesizer.synthesizeText(
            context = context,
            systemInstruction = systemInstruction,
            prompt = prompt,
            temperature = 0.75,
            maxTokens = 1200,
            fallback = { generateScriptOutline(cleanTopic, format, inHinglish) }
        )
    }

    /**
     * Builds a structured creator script framework according to retention-optimized guidelines.
     */
    fun generateScriptOutline(topic: String, format: String = "Reel", inHinglish: Boolean = true): String {
        return if (inHinglish) {
            """
            # Creator Script: $topic ($format)
            
            ## 1. THE HOOK (Pehle 3 Seconds)
            - Direct shock / curiosity line: "Agar aap bhi yeh galti kar rahe ho, to abhi ruk jao..."
            - Problem visual setup
            
            ## 2. THE REASON (Kyu zaroori hai?)
            - Point 1: Asli dikkat kahan aati hai
            - Point 2: Market me log kya miss karte hain
            
            ## 3. THE SOLUTION (Simple Step-by-Step)
            - Step A: Seedha practical tarika
            - Step B: Best tool / shortcut
            
            ## 4. CALL TO ACTION (CTA)
            - "Isko save kar lo taaki baad me dhoondhna na pade, aur share karo doston ke sath!"
            """.trimIndent()
        } else {
            """
            # Creator Script: $topic ($format)
            
            ## 1. THE HOOK (First 3 Seconds)
            - High-retention question / bold statement
            
            ## 2. THE PROBLEM / SETUP
            - The core obstacle viewers face
            
            ## 3. THE SOLUTION
            - Actionable advice in 2-3 clear steps
            
            ## 4. CALL TO ACTION
            - Save this for later & subscribe for more tips!
            """.trimIndent()
        }
    }
}
