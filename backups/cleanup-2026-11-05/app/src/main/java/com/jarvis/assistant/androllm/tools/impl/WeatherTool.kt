package com.jarvis.assistant.androllm.tools.impl

import com.jarvis.assistant.androllm.agent.core.PrivacyLevel
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolCost
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Weather via the free, keyless Open-Meteo API (geocoding + forecast).
 * Returns temperature, humidity, condition, rain chance, wind and a 3-day
 * forecast, both as a spoken summary and structured JSON.
 *
 * Also used by Jarvis' dispatcher get_weather for named locations (an upgrade
 * over the one-line wttr.in answer, which remains the fallback and the path
 * for "auto"/IP-based location).
 */
class WeatherTool : Tool {

    override val spec = ToolSpec(
        name = "get_weather",
        description = "Get current weather and a 3-day forecast for a city or location. Returns temperature, humidity, condition, rain chance, wind and daily forecast.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "City name or 'lat,lon' coordinates, e.g. Delhi or 28.61,77.20")
                }
            }
            putJsonArray("required") { add("location") }
        },
        permission = ToolPermission.WEATHER,
        category = ToolCategory.INFORMATION,
        supportedTasks = listOf(
            "weather", "forecast", "temperature outside", "rain", "humidity",
            "today's weather", "weekend forecast"
        ),
        cacheable = true,
        // Geocode + forecast are two network hops; give them headroom over
        // the default 20s budget so a slow lookup is not discarded.
        executionTimeoutMs = 45_000L,
        privacyLevel = PrivacyLevel.NETWORK,
        cost = ToolCost.NETWORK,
        failureModes = listOf("network", "timeout")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val location = ToolArgs.str(arguments, "location", "city")
            ?: return ToolResult.Failure("Missing required argument: location")
        return lookup(location)
    }

    /** Geocodes [location] and fetches the forecast. Never throws. */
    suspend fun lookup(location: String): ToolResult = runCatching {
        val geo = resolveCoordinates(location)
        if (geo == null) {
            ToolResult.Failure("Could not find a location matching '$location'.")
        } else {
            fetchForecast(geo)
        }
    }.getOrElse {
        ToolResult.Failure("Weather lookup failed: ${it.message ?: it.javaClass.simpleName}")
    }

    private data class Geo(val name: String, val lat: Double, val lon: Double)

    private suspend fun resolveCoordinates(location: String): Geo? {
        // "lat,lon" form needs no geocoding round-trip.
        val coords = location.split(",").map { it.trim() }
        if (coords.size == 2) {
            val lat = coords[0].toDoubleOrNull()
            val lon = coords[1].toDoubleOrNull()
            if (lat != null && lon != null) return Geo(location, lat, lon)
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            java.net.URLEncoder.encode(location, "UTF-8") +
            "&count=1&language=en&format=json"
        val body = ToolHttp.get(url).body
        val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = (first["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (first["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val name = (first["name"] as? JsonPrimitive)?.contentOrNull ?: location
        val country = (first["country"] as? JsonPrimitive)?.contentOrNull
        return Geo(if (country.isNullOrBlank()) name else "$name, $country", lat, lon)
    }

    private suspend fun fetchForecast(geo: Geo): ToolResult {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${geo.lat}&longitude=${geo.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
        val resp = ToolHttp.get(url)
        if (resp.code !in 200..299) return ToolResult.Failure("Weather service returned HTTP ${resp.code}.")
        val root = runCatching { JSON.parseToJsonElement(resp.body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Weather service returned an unreadable response.")

        val current = root["current"] as? JsonObject ?: return ToolResult.Failure("No current weather available.")
        val daily = root["daily"] as? JsonObject ?: JsonObject(emptyMap())

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.list(key: String): List<String> =
            (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyList()

        val temp = current.text("temperature_2m") ?: "?"
        val feels = current.text("apparent_temperature") ?: "?"
        val humidity = current.text("relative_humidity_2m") ?: "?"
        val precip = current.text("precipitation") ?: "0"
        val wind = current.text("wind_speed_10m") ?: "?"
        val code = current.text("weather_code")?.toDoubleOrNull()?.toInt() ?: 0

        val dates = daily.list("time")
        val maxes = daily.list("temperature_2m_max")
        val mins = daily.list("temperature_2m_min")
        val rainChance = daily.list("precipitation_probability_max")
        val codes = daily.list("weather_code")

        val condition = conditionLabel(code)

        data class Day(val label: String, val condition: String, val max: String, val min: String, val rain: String)

        val forecastDays = dates.indices.mapNotNull { i ->
            val label = when (i) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> dates.getOrNull(i) ?: ""
            }
            if (label.isBlank()) return@mapNotNull null
            Day(
                label = label,
                condition = conditionLabel(codes.getOrNull(i)?.toDoubleOrNull()?.toInt() ?: 0),
                max = maxes.getOrNull(i)?.ifBlank { null } ?: "?",
                min = mins.getOrNull(i)?.ifBlank { null } ?: "?",
                rain = rainChance.getOrNull(i)?.ifBlank { null } ?: "0"
            )
        }

        val data = buildJsonObject {
            put("location", geo.name)
            put("temperatureC", temp)
            put("feelsLikeC", feels)
            put("humidityPercent", humidity)
            put("condition", condition)
            put("rainMm", precip)
            put("windKmh", wind)
            putJsonArray("forecast") {
                forecastDays.forEach { d ->
                    add(buildJsonObject {
                        put("day", d.label)
                        put("condition", d.condition)
                        put("maxC", d.max)
                        put("minC", d.min)
                        put("rainChancePercent", d.rain)
                    })
                }
            }
        }

        val sb = StringBuilder()
        sb.append(geo.name).append(": ").append(temp).append("°C (feels like ").append(feels).append("°C), ")
            .append(condition.lowercase())
        if (precip.toDoubleOrNull()?.let { it > 0.0 } == true) sb.append(", rain ").append(precip).append(" mm")
        sb.append(", humidity ").append(humidity).append("%").append(", wind ").append(wind).append(" km/h")
        forecastDays.take(2).forEach { day ->
            sb.append(". ").append(day.label).append(": ").append(day.condition.lowercase()).append(", ")
                .append(day.min).append("–").append(day.max).append("°C")
            if (day.rain.toDoubleOrNull()?.let { it > 0 } == true) sb.append(", ").append(day.rain).append("% chance of rain")
        }
        return ToolResult.Success(summary = sb.toString(), data = data)
    }

    private fun conditionLabel(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
