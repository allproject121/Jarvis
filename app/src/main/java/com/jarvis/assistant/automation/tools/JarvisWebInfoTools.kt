package com.jarvis.assistant.automation.tools

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Jarvis agent web-info tools (dispatched by JarvisToolDispatcher):
 *  - get_news         : headlines + links from public RSS feeds (BBC, Hindustan Times,
 *                       Times of India, Business Standard, NDTV) with an optional topic filter
 *  - get_public_ip    : the device's public IP address
 *  - wikipedia_search : MediaWiki full-text search + summary of the best match
 */
object JarvisWebInfoTools {

    private const val TAG = "JarvisWebInfoTools"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Jarvis-Assistant/2.0"

    val NAMES: Set<String> = setOf("get_news", "get_public_ip", "wikipedia_search")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private data class Feed(val source: String, val url: String)

    private data class NewsItem(
        val source: String,
        val title: String,
        val link: String,
        val published: String,
        val summary: String
    )

    private val SOURCE_LABELS = linkedMapOf(
        "bbc" to "BBC News",
        "hindustan_times" to "Hindustan Times",
        "times_of_india" to "Times of India",
        "business_standard" to "Business Standard",
        "ndtv" to "NDTV"
    )

    private val TOP_FEEDS = mapOf(
        "bbc" to "https://feeds.bbci.co.uk/news/rss.xml",
        "hindustan_times" to "https://www.hindustantimes.com/feeds/rss/topnews/rssfeed.xml",
        "times_of_india" to "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",
        "business_standard" to "https://www.business-standard.com/rss/home_page_top_stories.rss",
        "ndtv" to "https://feeds.feedburner.com/ndtvnews-top-stories"
    )

    /** Category feeds used when the topic is a known section. */
    private val CATEGORY_FEEDS: Map<String, List<Feed>> = mapOf(
        "world" to listOf(
            Feed("bbc", "https://feeds.bbci.co.uk/news/world/rss.xml"),
            Feed("hindustan_times", "https://www.hindustantimes.com/feeds/rss/world-news/rssfeed.xml"),
            Feed("ndtv", "https://feeds.feedburner.com/ndtvnews-world-news")
        ),
        "india" to listOf(
            Feed("hindustan_times", "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml"),
            Feed("times_of_india", "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms"),
            Feed("ndtv", "https://feeds.feedburner.com/ndtvnews-india-news"),
            Feed("bbc", "https://feeds.bbci.co.uk/news/world/asia/india/rss.xml")
        ),
        "business" to listOf(
            Feed("business_standard", "https://www.business-standard.com/rss/latest.rss"),
            Feed("bbc", "https://feeds.bbci.co.uk/news/business/rss.xml"),
            Feed("times_of_india", "https://timesofindia.indiatimes.com/rssfeeds/1898055.cms"),
            Feed("hindustan_times", "https://www.hindustantimes.com/feeds/rss/business/rssfeed.xml")
        ),
        "technology" to listOf(
            Feed("bbc", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
            Feed("times_of_india", "https://timesofindia.indiatimes.com/rssfeeds/66949542.cms"),
            Feed("hindustan_times", "https://www.hindustantimes.com/feeds/rss/technology/rssfeed.xml")
        ),
        "sports" to listOf(
            Feed("times_of_india", "https://timesofindia.indiatimes.com/rssfeeds/4719148.cms"),
            Feed("hindustan_times", "https://www.hindustantimes.com/feeds/rss/sports/rssfeed.xml"),
            Feed("ndtv", "https://feeds.feedburner.com/ndtvsports-latest")
        ),
        "science" to listOf(
            Feed("bbc", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml")
        ),
        "entertainment" to listOf(
            Feed("bbc", "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml"),
            Feed("hindustan_times", "https://www.hindustantimes.com/feeds/rss/entertainment/rssfeed.xml"),
            Feed("times_of_india", "https://timesofindia.indiatimes.com/rssfeeds/1081479906.cms")
        )
    )

    private val CATEGORY_ALIASES = mapOf(
        "tech" to "technology", "technology" to "technology", "gadgets" to "technology", "ai" to "technology",
        "business" to "business", "market" to "business", "markets" to "business", "economy" to "business",
        "finance" to "business", "stock" to "business", "stocks" to "business",
        "world" to "world", "international" to "world", "global" to "world",
        "india" to "india", "national" to "india", "desh" to "india",
        "sport" to "sports", "sports" to "sports", "cricket" to "sports", "football" to "sports",
        "science" to "science", "environment" to "science", "climate" to "science",
        "entertainment" to "entertainment", "bollywood" to "entertainment", "movies" to "entertainment"
    )

    // ── Schemas (OpenAI / Hermes function format, same as JarvisToolDispatcher) ──

    fun schemas(): List<JSONObject> = listOf(
        schema(
            name = "get_news",
            description = "Gets the latest news headlines with links from public RSS feeds (BBC, Hindustan Times, " +
                "Times of India, Business Standard, NDTV). Optional topic filter such as india, world, business, " +
                "technology, sports, science, entertainment or any keyword (e.g. 'ISRO', 'Sensex').",
            properties = JSONObject().apply {
                put("topic", JSONObject().put("type", "string")
                    .put("description", "Optional section or keyword to filter headlines"))
                put("source", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("all") + SOURCE_LABELS.keys))
                    .put("description", "Optional single source; default all"))
                put("limit", JSONObject().put("type", "integer")
                    .put("description", "Number of headlines to return (1-20, default 8)"))
            },
            required = emptyList()
        ),
        schema(
            name = "get_public_ip",
            description = "Returns the device's current public (internet-facing) IP address.",
            properties = JSONObject(),
            required = emptyList()
        ),
        schema(
            name = "wikipedia_search",
            description = "Searches Wikipedia and returns the best matching articles with links plus a summary of the top result.",
            properties = JSONObject().apply {
                put("query", JSONObject().put("type", "string").put("description", "What to look up"))
                put("language", JSONObject().put("type", "string")
                    .put("description", "Wikipedia language code, e.g. en (default) or hi"))
                put("limit", JSONObject().put("type", "integer")
                    .put("description", "Number of results (1-10, default 5)"))
            },
            required = listOf("query")
        )
    )

    private fun schema(name: String, description: String, properties: JSONObject, required: List<String>) =
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", JSONArray(required))
                })
            })
        }

    // ── Execution ───────────────────────────────────────────────────────────

    /** Runs one of [NAMES]; always returns a JSON object with "success" and "message". */
    suspend fun execute(context: Context, name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "get_news" -> getNews(
                    topic = args.optString("topic", "").trim(),
                    source = args.optString("source", "all").trim().lowercase(Locale.ROOT),
                    limit = args.optInt("limit", 8).coerceIn(1, 20)
                )
                "get_public_ip" -> getPublicIp()
                "wikipedia_search" -> wikipediaSearch(
                    query = args.optString("query", "").ifBlank { args.optString("topic", "") }.trim(),
                    language = args.optString("language", "en").trim().lowercase(Locale.ROOT)
                        .takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en",
                    limit = args.optInt("limit", 5).coerceIn(1, 10)
                )
                else -> fail("Unsupported web tool '$name'.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$name failed: ${e.message}")
            fail("Could not complete $name: ${e.message ?: "network error"}")
        }
    }

    private fun fail(message: String) = JSONObject().put("success", false).put("message", message)

    // ── get_news ────────────────────────────────────────────────────────────

    private suspend fun getNews(topic: String, source: String, limit: Int): JSONObject {
        val sources = if (source.isBlank() || source == "all" || source !in SOURCE_LABELS) {
            SOURCE_LABELS.keys.toList()
        } else listOf(source)

        val topicWords = topic.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        val category = topicWords.firstNotNullOfOrNull { CATEGORY_ALIASES[it] }

        val feeds = buildList {
            if (category != null) {
                CATEGORY_FEEDS[category].orEmpty().filter { it.source in sources }.forEach { add(it) }
            }
            if (isEmpty()) sources.forEach { s -> TOP_FEEDS[s]?.let { add(Feed(s, it)) } }
        }

        val all = coroutineScope {
            feeds.map { feed -> async { runCatching { fetchFeed(feed) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten()

        if (all.isEmpty()) return fail("News feeds are unreachable right now. Check the internet connection.")

        // Keyword filter (skip when the topic was just a section name we already used).
        val keywords = topicWords.filter { CATEGORY_ALIASES[it] == null }
        val filtered = if (keywords.isEmpty()) all else all.filter { item ->
            val hay = (item.title + " " + item.summary).lowercase(Locale.ROOT)
            keywords.any { hay.contains(it) }
        }
        val usedFilter = keywords.isNotEmpty() && filtered.isNotEmpty()
        val pool = if (filtered.isNotEmpty()) filtered else all

        // Round-robin across sources so one outlet doesn't dominate, then de-duplicate titles.
        val bySource = pool.groupBy { it.source }.mapValues { it.value.toMutableList() }
        val picked = mutableListOf<NewsItem>()
        val seen = HashSet<String>()
        while (picked.size < limit && bySource.values.any { it.isNotEmpty() }) {
            for (list in bySource.values) {
                if (picked.size >= limit) break
                val next = list.removeFirstOrNull() ?: continue
                val key = next.title.lowercase(Locale.ROOT).take(60)
                if (seen.add(key)) picked += next
            }
        }

        val arr = JSONArray()
        picked.forEach { n ->
            arr.put(JSONObject().apply {
                put("source", SOURCE_LABELS[n.source] ?: n.source)
                put("title", n.title)
                put("link", n.link)
                if (n.published.isNotBlank()) put("published", n.published)
                if (n.summary.isNotBlank()) put("summary", n.summary.take(240))
            })
        }
        val spoken = picked.take(5).joinToString(" ") { "${SOURCE_LABELS[it.source] ?: it.source}: ${it.title}." }
        val note = when {
            topic.isBlank() -> "Top headlines"
            usedFilter || category != null -> "Headlines for '$topic'"
            else -> "No headline matched '$topic'; showing top headlines instead"
        }
        return JSONObject().apply {
            put("success", true)
            put("topic", topic)
            put("count", picked.size)
            put("headlines", arr)
            put("message", "$note: $spoken")
        }
    }

    private fun fetchFeed(feed: Feed): List<NewsItem> {
        val req = Request.Builder()
            .url(feed.url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()
        return parseRss(feed.source, body)
    }

    private fun parseRss(source: String, xml: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var inItem = false
        var title = ""
        var link = ""
        var pub = ""
        var desc = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    if (tag == "item" || tag == "entry") {
                        inItem = true
                        title = ""; link = ""; pub = ""; desc = ""
                    } else if (inItem) {
                        when (tag) {
                            "title" -> title = safeText(parser)
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: safeText(parser)
                            }
                            "pubdate", "published", "updated", "dc:date" -> if (pub.isBlank()) pub = safeText(parser)
                            "description", "summary" -> if (desc.isBlank()) desc = safeText(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    if ((tag == "item" || tag == "entry") && inItem) {
                        inItem = false
                        val cleanTitle = stripHtml(title)
                        if (cleanTitle.isNotBlank()) {
                            items += NewsItem(source, cleanTitle, link.trim(), pub.trim(), stripHtml(desc))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun safeText(parser: XmlPullParser): String = try {
        parser.nextText() ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun stripHtml(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('￼', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── get_public_ip ───────────────────────────────────────────────────────

    private fun getPublicIp(): JSONObject {
        val endpoints = listOf(
            "https://api.ipify.org?format=json",
            "https://api64.ipify.org?format=json",
            "https://icanhazip.com"
        )
        for (url in endpoints) {
            val ip = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty().trim()
                    if (body.startsWith("{")) JSONObject(body).optString("ip") else body
                }
            }.getOrNull()?.trim()
            if (!ip.isNullOrBlank() && ip.length <= 45 && ip.matches(Regex("[0-9a-fA-F:.]+"))) {
                return JSONObject().apply {
                    put("success", true)
                    put("ip", ip)
                    put("version", if (ip.contains(':')) "IPv6" else "IPv4")
                    put("message", "Your public IP address is $ip.")
                }
            }
        }
        return fail("Could not determine the public IP address (no internet connection?).")
    }

    // ── wikipedia_search ────────────────────────────────────────────────────

    private fun wikipediaSearch(query: String, language: String, limit: Int): JSONObject {
        if (query.isBlank()) return fail("A query is required for wikipedia_search.")
        val base = "https://$language.wikipedia.org"
        val searchUrl = "$base/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=$limit&srsearch=" +
            URLEncoder.encode(query, "UTF-8")
        val searchJson = getJson(searchUrl) ?: return fail("Wikipedia is unreachable right now.")
        val hits = searchJson.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        if (hits.length() == 0) return fail("No Wikipedia article found for '$query'.")

        val results = JSONArray()
        for (i in 0 until hits.length()) {
            val h = hits.optJSONObject(i) ?: continue
            val title = h.optString("title")
            results.put(JSONObject().apply {
                put("title", title)
                put("snippet", stripHtml(h.optString("snippet")))
                put("url", "$base/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/"))
            })
        }

        val topTitle = hits.optJSONObject(0)?.optString("title").orEmpty()
        val summaryJson = if (topTitle.isNotBlank()) {
            getJson("$base/api/rest_v1/page/summary/" + URLEncoder.encode(topTitle.replace(' ', '_'), "UTF-8"))
        } else null
        val extract = summaryJson?.optString("extract").orEmpty()
        val description = summaryJson?.optString("description").orEmpty()

        return JSONObject().apply {
            put("success", true)
            put("query", query)
            put("language", language)
            put("title", topTitle)
            if (description.isNotBlank()) put("description", description)
            if (extract.isNotBlank()) put("extract", extract)
            put("results", results)
            put(
                "message",
                if (extract.isNotBlank()) "$topTitle: $extract"
                else "Top Wikipedia matches: " + (0 until results.length()).joinToString { results.getJSONObject(it).optString("title") }
            )
        }
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Jarvis-Assistant-Android/2.0 (Wikipedia tool)")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else JSONObject(resp.body?.string().orEmpty())
        }
    }.getOrNull()
}
