package com.jarvis.assistant.androllm.memory.extraction

import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Wire format returned by the extraction LLM call. */
@Serializable
data class ExtractionResponse(
    val memories: List<ExtractionItem> = emptyList()
)

@Serializable
data class ExtractionItem(
    val content: String = "",
    val category: String = "CUSTOM",
    val importance: Int = 1,
    val tags: List<String> = emptyList(),
    val project: String? = null
)

/**
 * JSON schema describing the extraction output. Jarvis' text backends do not
 * support grammar-constrained decoding, so the schema is appended to the
 * prompt as a format contract (see [ExtractionPrompts]).
 */
object ExtractionSchema {
    const val JSON_SCHEMA: String = """
    {
      "type": "object",
      "properties": {
        "memories": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "content": { "type": "string" },
              "category": {
                "type": "string",
                "enum": ["IDENTITY","PREFERENCES","PROJECTS","GOALS","SKILLS","PROGRAMMING_LANGUAGES","FRAMEWORKS","DEVICES","PINNED_FACTS","DEVELOPER_NOTES","CUSTOM"]
              },
              "importance": { "type": "integer" },
              "tags": { "type": "array", "items": { "type": "string" } },
              "project": { "type": "string" }
            },
            "required": ["content"]
          }
        }
      },
      "required": ["memories"]
    }
    """
}

/**
 * Parses raw LLM output into extracted memories. Lenient on purpose:
 * tolerant of markdown code fences, leading prose, truncated JSON and
 * casing variants of category names. Never throws; returns an empty list
 * when nothing usable is found.
 */
object ExtractionJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String?): List<ExtractedMemory> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```")
        // Prefer the first balanced {...} block; when the output is truncated
        // (no closing brace) fall back to scanning the whole text.
        val block = extractJsonBlock(cleaned) ?: cleaned

        val response = try {
            json.decodeFromString(ExtractionResponse.serializer(), block)
        } catch (_: Exception) {
            parseFallback(block)
        }

        return response.memories
            .asSequence()
            .mapNotNull { item ->
                val content = item.content.trim()
                if (content.isEmpty() || content.length > 800) return@mapNotNull null
                ExtractedMemory(
                    content = content,
                    category = MemoryCategory.fromName(item.category),
                    importance = item.importance.coerceIn(1, 5),
                    tags = item.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10),
                    projectName = item.project?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
            .distinctBy { it.content.lowercase() }
            .toList()
    }

    /** Extracts the first balanced {...} object, skipping fences and surrounding prose. */
    private fun extractJsonBlock(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```")
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Tries an object, then an array, then scans for `"content": "..."` pairs. */
    private fun parseFallback(block: String): ExtractionResponse {
        runCatching {
            val obj = json.parseToJsonElement(block).jsonObject
            val items = parseItems(obj["memories"])
            if (items != null) return ExtractionResponse(items)
        }

        runCatching {
            val arr = json.parseToJsonElement(block).jsonArray
            val items = arr.mapNotNull { el ->
                val obj = (el as? JsonObject) ?: return@mapNotNull null
                toItem(obj)
            }
            if (items.isNotEmpty()) return ExtractionResponse(items)
        }

        val items = mutableListOf<ExtractionItem>()
        val regex = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (match in regex.findAll(block)) {
            items.add(ExtractionItem(content = match.groupValues[1]))
        }
        return ExtractionResponse(items)
    }

    private fun parseItems(element: JsonElement?): List<ExtractionItem>? {
        val arr = (element as? JsonArray) ?: return null
        return arr.mapNotNull { el ->
            val obj = (el as? JsonObject) ?: return@mapNotNull null
            toItem(obj)
        }
    }

    private fun toItem(obj: JsonObject): ExtractionItem {
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.content?.trim().orEmpty()
        fun strList(key: String): List<String> =
            (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it.isNotBlank() }
                ?: emptyList()
        return ExtractionItem(
            content = str("content"),
            category = str("category").ifEmpty { "CUSTOM" },
            importance = (obj["importance"] as? JsonPrimitive)?.intOrNull ?: 1,
            tags = strList("tags"),
            project = str("project").ifEmpty { null }
        )
    }
}
