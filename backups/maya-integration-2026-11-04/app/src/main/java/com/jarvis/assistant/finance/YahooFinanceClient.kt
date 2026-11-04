package com.jarvis.assistant.finance

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class StockQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val marketState: String
)

class YahooFinanceClient(private val context: Context) {
    companion object {
        private const val TAG = "YahooFinanceClient"
        private const val PREFS_NAME = "jarvis_finance_watchlist"
        private const val KEY_WATCHLIST = "watchlist"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getQuote(symbol: String): StockQuote? = withContext(Dispatchers.IO) {
        val cleanSymbol = normalizeSymbol(symbol)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$cleanSymbol?interval=1d&range=1d"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val chart = json.getJSONObject("chart")
                val result = chart.getJSONArray("result").getJSONObject(0)
                val meta = result.getJSONObject("meta")

                val regularMarketPrice = meta.optDouble("regularMarketPrice", 0.0)
                val previousClose = meta.optDouble("previousClose", regularMarketPrice)
                val change = regularMarketPrice - previousClose
                val changePercent = if (previousClose > 0) (change / previousClose) * 100.0 else 0.0
                val currency = meta.optString("currency", "USD")
                val shortName = meta.optString("shortName", cleanSymbol)
                val marketState = meta.optString("tradingPeriod", "REGULAR")

                return@withContext StockQuote(
                    symbol = cleanSymbol,
                    name = shortName,
                    price = regularMarketPrice,
                    change = change,
                    changePercent = changePercent,
                    currency = currency,
                    marketState = marketState
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching quote for $symbol: ${e.message}")
            null
        }
    }

    suspend fun getTrending(): List<String> = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v1/finance/trending/IN"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val finance = json.getJSONObject("finance")
                val result = finance.getJSONArray("result").getJSONObject(0)
                val quotes = result.getJSONArray("quotes")
                val symbols = mutableListOf<String>()
                for (i in 0 until quotes.length()) {
                    symbols.add(quotes.getJSONObject(i).getString("symbol"))
                }
                return@withContext symbols
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trending stocks: ${e.message}")
            emptyList()
        }
    }

    fun getWatchlist(): Set<String> {
        return prefs.getStringSet(KEY_WATCHLIST, setOf("RELIANCE.NS", "TCS.NS", "AAPL", "BTC-USD")) ?: emptySet()
    }

    fun addToWatchlist(symbol: String): Boolean {
        val current = getWatchlist().toMutableSet()
        val normalized = normalizeSymbol(symbol)
        current.add(normalized)
        prefs.edit().putStringSet(KEY_WATCHLIST, current).apply()
        return true
    }

    fun removeFromWatchlist(symbol: String): Boolean {
        val current = getWatchlist().toMutableSet()
        val normalized = normalizeSymbol(symbol)
        current.remove(normalized)
        prefs.edit().putStringSet(KEY_WATCHLIST, current).apply()
        return true
    }

    private fun normalizeSymbol(raw: String): String {
        val s = raw.trim().uppercase()
        return when (s) {
            "RELIANCE" -> "RELIANCE.NS"
            "TCS" -> "TCS.NS"
            "INFOSYS", "INFY" -> "INFY.NS"
            "HDFC", "HDFCBANK" -> "HDFCBANK.NS"
            "TATA MOTORS", "TATAMOTORS" -> "TATAMOTORS.NS"
            "NIFTY", "NIFTY50" -> "^NSEI"
            "SENSEX" -> "^BSESN"
            "BITCOIN", "BTC" -> "BTC-USD"
            "ETHEREUM", "ETH" -> "ETH-USD"
            else -> s
        }
    }
}
