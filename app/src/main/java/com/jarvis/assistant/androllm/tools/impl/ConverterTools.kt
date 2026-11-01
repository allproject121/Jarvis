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
 * Converts between common units: length, mass, temperature, data, volume,
 * speed and time.
 *
 * Jarvis-first: the conversion itself is Jarvis' existing convert_units
 * (androllm.tools.AndroLlmTools — the earlier port of this exact AndroLLM
 * tool, plus a dimension check that rejects "km → kg"); this class only
 * exposes it to the agent registry with AndroLLM's spec.
 */
class UnitConverterTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "convert_units",
        description = "Convert a value between units: length (m, km, mi, ft, in, cm), mass (kg, g, lb, oz), temperature (°C, °F, K), data (B, KB, MB, GB, TB), volume (L, mL, gal, cup), speed (km/h, mph, m/s), time (s, min, h, day).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") { put("type", "number") }
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
            }
            putJsonArray("required") { add("value"); add("from"); add("to") }
        },
        permission = ToolPermission.CALCULATOR,
        category = ToolCategory.INFORMATION,
        cacheable = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        JarvisToolBridge.runAndroLlmTool(context, "convert_units", arguments)
}

/**
 * Currency conversion with an offline reference-rate table, explicitly
 * labelled approximate. Delegates to Jarvis' existing convert_currency
 * (same AndroLLM rate table).
 */
class CurrencyTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "convert_currency",
        description = "Convert an amount between currencies (USD, EUR, GBP, JPY, INR, CAD, AUD, CHF, CNY, BRL, KRW, MXN, SEK, NOK, NZD, SGD, HKD, ZAR). Rates are approximate (fixed reference table) — mention they may not be live.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("amount") { put("type", "number") }
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
            }
            putJsonArray("required") { add("amount"); add("from"); add("to") }
        },
        permission = ToolPermission.CALCULATOR,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        JarvisToolBridge.runAndroLlmTool(context, "convert_currency", arguments)
}
