package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens Google Maps navigation to a destination ("navigate to the airport"),
 * falling back to a plain geo: query when Google Maps is missing.
 *
 * Jarvis-first: delegates to Jarvis' open_navigation (androllm.tools
 * .AndroLlmTools — the earlier port of this tool, which also maps the travel
 * mode onto Google's d/w/b codes).
 */
class MapsTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_navigation",
        description = "Open turn-by-turn navigation in Google Maps to a destination or address.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("destination") {
                    put("type", "string")
                    put("description", "Destination name, address or 'lat,lon'")
                }
                putJsonObject("mode") {
                    put("type", "string")
                    put("description", "Optional travel mode: drive, walk, transit or bicycle")
                }
            }
            putJsonArray("required") { add("destination") }
        },
        permission = ToolPermission.MAPS,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        JarvisToolBridge.runAndroLlmTool(context, "open_navigation", arguments)
}

/**
 * Searches for places near the user's location and shows them on a map
 * ("find the nearest hospital"). Delegates to Jarvis' search_places.
 */
class MapsSearchTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "search_places",
        description = "Search nearby places (hospitals, restaurants, ATMs, gas stations...) and show the results on a map.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to find, e.g. 'nearest hospital' or 'coffee near me'")
                }
            }
            putJsonArray("required") { add("query") }
        },
        permission = ToolPermission.MAPS,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        JarvisToolBridge.runAndroLlmTool(context, "search_places", arguments)
}
