package com.jarvis.assistant.social

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JARVIS Social Media & Daily Story Sentinel:
 * Formulates daily morning stories, social captions, and scheduled post concepts
 * aligned with the user's personality and remembered interests.
 */
object SocialStoryManager {
    private const val TAG = "SocialStoryManager"

    suspend fun generateDailyStory(context: Context): String = withContext(Dispatchers.IO) {
        val app = (context.applicationContext as? JarvisApp) ?: JarvisApp.instance
        val prefs = app.preferences
        val userName = prefs.userName.ifBlank { "Operator" }
        val memories: List<MemoryEntity> = try { app.memoryRepository.getAllMemories() } catch (e: Exception) { emptyList() }
        val dateStr = SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date())

        val interestSnippet = if (memories.isNotEmpty()) {
            "User's priorities and memories: " + memories.take(3).joinToString("; ") { it.rawText }
        } else {
            "Focusing on ambition, precision, and peak productivity."
        }

        val systemPrompt = "You are JARVIS, Tony Stark's sophisticated AI assistant. Formulate an inspiring morning dispatch for your creator/operator."
        val userPrompt = """
Create a motivating, articulate morning dispatch for $userName on $dateStr.
$interestSnippet
Keep it 3 to 4 sentences: visionary, focused on high-impact execution, respectful, and sharp.
        """.trimIndent()

        AiTextSynthesizer.synthesizeText(
            context = context,
            systemInstruction = systemPrompt,
            prompt = userPrompt,
            temperature = 0.7,
            maxTokens = 400,
            fallback = {
                """
🌟 JARVIS MORNING DISPATCH • $dateStr
Good morning, $userName. 

"$interestSnippet"

"The future is not something we enter. The future is something we build."
Your neural systems and protocols are 100% operational. Let's make today exceptional.
                """.trimIndent()
            }
        )
    }

    suspend fun generateDynamicSocialCaption(
        context: Context,
        topic: String,
        tone: String = "Inspirational"
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = "You are JARVIS, an elite social media strategist. Formulate a viral caption with hashtags for Instagram/Twitter/LinkedIn."
        val userPrompt = "Write a high-engagement social media caption for topic: '$topic' with a $tone tone. Include 4-6 relevant hashtags at the end."

        AiTextSynthesizer.synthesizeText(
            context = context,
            systemInstruction = systemPrompt,
            prompt = userPrompt,
            temperature = 0.7,
            maxTokens = 300,
            fallback = { generateSocialCaption(topic, tone) }
        )
    }

    fun generateSocialCaption(topic: String, tone: String = "Inspirational"): String {
        val hashtags = "#JarvisAI #Future #Tech #Innovation #ArtificialIntelligence #StarkEcosystem"
        return when (tone.lowercase()) {
            "witty" -> "They told me the sky was the limit, so I upgraded my propulsion. 🚀 $topic\n\n$hashtags"
            "poetic" -> "Echoes of ambition turning into architecture. ✨ $topic\n\n$hashtags"
            else -> "Engineering reality, one breakthrough at a time. ⚡ $topic\n\n$hashtags"
        }
    }
}
