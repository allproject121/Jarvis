package com.jarvis.assistant.models.catalog

import org.json.JSONObject

object CatalogParser {

    fun parse(jsonText: String): List<CatalogModel> {
        val root = JSONObject(jsonText)
        val modelsArray = root.optJSONArray("models") ?: return emptyList()
        val list = mutableListOf<CatalogModel>()

        for (i in 0 until modelsArray.length()) {
            val obj = modelsArray.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isBlank()) continue

            val name = obj.optString("name", id)
            val description = obj.optString("description", "")
            val family = obj.optString("family", "")
            val architecture = obj.optString("architecture", "")
            val license = obj.optString("license", "Apache-2.0")
            val author = obj.optString("author", "")
            val fileName = obj.optString("fileName", "")
            val downloadUrl = obj.optString("downloadUrl", "")
            val sizeBytes = obj.optLong("sizeBytes", 0L)
            val parameters = obj.optString("parameters", "")
            val quantization = obj.optString("quantization", "")
            val contextLength = obj.optInt("contextLength", 4096)
            val minRamGb = obj.optDouble("minRamGb", 4.0).toFloat()
            val recommendedRamGb = obj.optDouble("recommendedRamGb", 8.0).toFloat()
            val downloads = obj.optLong("downloads", 0L)
            val likes = obj.optLong("likes", 0L)
            val trendingScore = obj.optLong("trendingScore", 0L)
            val sha256 = if (obj.has("sha256")) obj.optString("sha256") else null
            val modality = obj.optString("modality", "TEXT")
            val status = obj.optString("status", "STABLE")
            val recommended = obj.optBoolean("recommended", false)
            val hidden = obj.optBoolean("hidden", false)
            val streamable = obj.optBoolean("streamable", true)
            val estimatedRam = obj.optLong("estimatedRuntimeRamMb", (sizeBytes / (1024 * 1024)).coerceAtLeast(1200L))

            val categories = mutableListOf<String>()
            val catArr = obj.optJSONArray("categories")
            if (catArr != null) {
                for (c in 0 until catArr.length()) categories.add(catArr.optString(c))
            }

            val tags = mutableListOf<String>()
            val tagArr = obj.optJSONArray("tags")
            if (tagArr != null) {
                for (t in 0 until tagArr.length()) tags.add(tagArr.optString(t))
            }

            list.add(
                CatalogModel(
                    id = id,
                    name = name,
                    description = description,
                    family = family,
                    architecture = architecture,
                    categories = categories,
                    tags = tags,
                    license = license,
                    author = author,
                    fileName = fileName,
                    downloadUrl = downloadUrl,
                    sizeBytes = sizeBytes,
                    parameters = parameters,
                    quantization = quantization,
                    contextLength = contextLength,
                    minRamGb = minRamGb,
                    recommendedRamGb = recommendedRamGb,
                    downloads = downloads,
                    likes = likes,
                    trendingScore = trendingScore,
                    sha256 = sha256,
                    modality = modality,
                    status = status,
                    recommended = recommended,
                    hidden = hidden,
                    streamable = streamable,
                    estimatedRuntimeRamMb = estimatedRam
                )
            )
        }

        return list
    }
}
