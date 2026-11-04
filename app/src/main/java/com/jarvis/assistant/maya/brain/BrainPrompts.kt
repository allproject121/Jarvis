package com.jarvis.assistant.maya.brain

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.PromptGenerator

/**
 * System prompts for the Advanced Agentic brain. Uses Jarvis's own persona prompt
 * ([PromptGenerator]) so the cloud brain sounds like Jarvis, with a short Hinglish
 * companion fallback when preferences are unavailable.
 */
object BrainPrompts {

    const val TOOL_RULES =
        "[TOOLS]: You have native function tools. Call a tool whenever an action on the phone or fresh " +
            "information is needed; never pretend to have done something. After the tools finish, answer " +
            "the user in ONE or TWO short spoken sentences (this reply is read aloud). No markdown, no lists, " +
            "no code fences in the spoken answer."

    const val FALLBACK_PROMPT =
        "You are JARVIS, the user's sharp, warm Hinglish voice companion created by Roshan Sir. " +
            "Speak naturally in short conversational Hinglish (Hindi + English in Latin script) unless the " +
            "user speaks another language. Be concise: one or two sentences, confident and friendly."

    fun defaultSystemPrompt(context: Context, memoryContext: String = ""): String {
        return runCatching {
            val prefs = (context.applicationContext as? JarvisApp)?.preferences ?: JarvisApp.instance.preferences
            PromptGenerator.generateSystemPrompt(
                personality = prefs.personality.ifBlank { GeminiConstants.PERSONALITY_ASSISTANT },
                userName = prefs.userName,
                memoryContext = memoryContext,
                language = runCatching { prefs.language }.getOrDefault(GeminiConstants.DEFAULT_LANGUAGE)
            )
        }.getOrElse { FALLBACK_PROMPT }
    }

    fun subAgentPrompt(role: SubAgentRole): String = when (role) {
        SubAgentRole.CODING ->
            "You are Jarvis's coding sub-agent. Write correct, complete, runnable code. Return the code and a one-line explanation. No filler."
        SubAgentRole.RESEARCH ->
            "You are Jarvis's research sub-agent. Give accurate, well-structured findings with the key facts first. Say clearly when something is uncertain."
        SubAgentRole.MISSION ->
            "You are Jarvis's mission sub-agent. Break the goal into concrete numbered steps, execute what you can with tools, and report what was done and what remains."
        SubAgentRole.EYES ->
            "You are Jarvis's eyes. Describe exactly what is in the image or screen: visible text, UI elements, people, objects, and anything the user asked about. Be factual; never guess what you cannot see."
    }
}
