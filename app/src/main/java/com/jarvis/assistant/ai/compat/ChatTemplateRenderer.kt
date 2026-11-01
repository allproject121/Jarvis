package com.jarvis.assistant.ai.compat

import com.jarvis.assistant.data.model.ChatTurn
import org.json.JSONObject

/**
 * Renders standardized prompts formatted according to the exact chat template
 * required by the active SLM family (ChatML for Qwen/DeepSeek, Turn-based for Gemma, Header-based for Llama 3).
 */
object ChatTemplateRenderer {

    fun render(
        family: ModelFamily,
        cleanPrompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        tools: List<JSONObject>
    ): String {
        return when (family) {
            ModelFamily.GEMMA -> renderGemma(cleanPrompt, systemPrompt, memoryContext, chatHistory, tools)
            ModelFamily.LLAMA3 -> renderLlama3(cleanPrompt, systemPrompt, memoryContext, chatHistory, tools)
            ModelFamily.QWEN, ModelFamily.DEEPSEEK, ModelFamily.SMOLLM -> renderChatMl(cleanPrompt, systemPrompt, memoryContext, chatHistory, tools)
        }
    }

    private fun renderChatMl(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        tools: List<JSONObject>
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt.ifBlank { "You are JARVIS, Tony Stark's ultra-intelligent Android AI assistant created by Roshan Sir." }.trim())

        if (memoryContext.isNotBlank()) {
            sb.append("\n\n[PERSISTENT MEMORY CONTEXT]:\n").append(memoryContext.trim())
        }

        if (tools.isNotEmpty()) {
            sb.append("\n\n# Tools Available\nYou have access to the following device control tools:\n<tools>\n")
            for (t in tools) {
                sb.append(t.toString()).append("\n")
            }
            sb.append("</tools>\n")
            sb.append("When you need to execute a tool, reply ONLY with:\n")
            sb.append("<tool_call>\n{\"name\": \"function_name\", \"arguments\": { ... }}\n</tool_call>\n")
        }
        sb.append("<|im_end|>\n")

        for (turn in chatHistory.takeLast(4)) {
            if (turn.userTranscript.isNotBlank()) {
                sb.append("<|im_start|>user\n").append(turn.userTranscript.trim()).append("<|im_end|>\n")
            }
            if (turn.jarvisResponse.isNotBlank()) {
                sb.append("<|im_start|>assistant\n").append(turn.jarvisResponse.trim()).append("<|im_end|>\n")
            }
        }

        sb.append("<|im_start|>user\n").append(prompt.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun renderGemma(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        tools: List<JSONObject>
    ): String {
        val sb = StringBuilder()
        val sysContent = buildString {
            append(systemPrompt.ifBlank { "You are JARVIS, Tony Stark's ultra-intelligent Android AI assistant created by Roshan Sir." }.trim())
            if (memoryContext.isNotBlank()) append("\n\n[PERSISTENT MEMORY]:\n").append(memoryContext.trim())
            if (tools.isNotEmpty()) {
                append("\n\nAvailable tools:\n")
                for (t in tools) append(t.toString()).append("\n")
                append("To call a tool, reply with: <tool_call>{\"name\": \"...\", \"arguments\": {...}}</tool_call>")
            }
        }

        for (turn in chatHistory.takeLast(4)) {
            if (turn.userTranscript.isNotBlank()) {
                sb.append("<start_of_turn>user\n").append(turn.userTranscript.trim()).append("<end_of_turn>\n")
            }
            if (turn.jarvisResponse.isNotBlank()) {
                sb.append("<start_of_turn>model\n").append(turn.jarvisResponse.trim()).append("<end_of_turn>\n")
            }
        }

        sb.append("<start_of_turn>user\n")
        if (chatHistory.isEmpty()) {
            sb.append(sysContent).append("\n\n")
        }
        sb.append(prompt.trim()).append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun renderLlama3(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        tools: List<JSONObject>
    ): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
        sb.append(systemPrompt.ifBlank { "You are JARVIS, Tony Stark's ultra-intelligent Android AI assistant created by Roshan Sir." }.trim())

        if (memoryContext.isNotBlank()) {
            sb.append("\n\n[PERSISTENT MEMORY CONTEXT]:\n").append(memoryContext.trim())
        }
        if (tools.isNotEmpty()) {
            sb.append("\n\n# Tools\n")
            for (t in tools) sb.append(t.toString()).append("\n")
        }
        sb.append("<|eot_id|>\n")

        for (turn in chatHistory.takeLast(4)) {
            if (turn.userTranscript.isNotBlank()) {
                sb.append("<|start_header_id|>user<|end_header_id|>\n\n").append(turn.userTranscript.trim()).append("<|eot_id|>\n")
            }
            if (turn.jarvisResponse.isNotBlank()) {
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n").append(turn.jarvisResponse.trim()).append("<|eot_id|>\n")
            }
        }

        sb.append("<|start_header_id|>user<|end_header_id|>\n\n").append(prompt.trim()).append("<|eot_id|>\n")
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }
}
