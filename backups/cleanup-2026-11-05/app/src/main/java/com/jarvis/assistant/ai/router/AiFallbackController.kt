package com.jarvis.assistant.ai.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.jarvis.assistant.ai.provider.AiProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Intelligent Zero-Delay Fallback Controller.
 * Monitors network quality, WebSocket session stability, and hardware state
 * to automatically switch between Online Gemini Live and On-Device Local Core.
 */
class AiFallbackController private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AiFallbackController"

        @Volatile
        private var instance: AiFallbackController? = null

        fun getInstance(context: Context, scope: CoroutineScope): AiFallbackController {
            return instance ?: synchronized(this) {
                instance ?: AiFallbackController(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _activeProvider = MutableStateFlow(AiProviderType.GEMINI_LIVE_ONLINE)
    val activeProvider: StateFlow<AiProviderType> = _activeProvider.asStateFlow()

    private val _isFallbackEngaged = MutableStateFlow(false)
    val isFallbackEngaged: StateFlow<Boolean> = _isFallbackEngaged.asStateFlow()

    private val _networkAvailable = MutableStateFlow(false)
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _networkAvailable.value = true
                    Log.i(TAG, "Internet connectivity restored.")
                    scope.launch(Dispatchers.Main) {
                        if (_isFallbackEngaged.value) {
                            Log.i(TAG, "Network restored; gracefully transitioning back to Online Gemini Live.")
                            _isFallbackEngaged.value = false
                            _activeProvider.value = AiProviderType.GEMINI_LIVE_ONLINE
                        }
                    }
                }

                override fun onLost(network: Network) {
                    _networkAvailable.value = false
                    Log.w(TAG, "Internet connectivity lost. Engaging Local Offline Core.")
                    triggerOfflineFallback("Network disconnected")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed registering network callback: ${e.message}")
        }
    }

    /**
     * Instantly triggers zero-delay offline failover to LocalPhoneProvider.
     */
    fun triggerOfflineFallback(reason: String) {
        scope.launch(Dispatchers.Main) {
            Log.w(TAG, "ENGAGING OFFLINE FALLBACK: $reason")
            _isFallbackEngaged.value = true
            _activeProvider.value = AiProviderType.LOCAL_PHONE_OFFLINE
        }
    }

    /**
     * Explicit mode selection by user.
     */
    fun setProvider(providerType: AiProviderType) {
        _activeProvider.value = providerType
        _isFallbackEngaged.value = (providerType == AiProviderType.LOCAL_PHONE_OFFLINE)
        Log.i(TAG, "Active provider set to: ${providerType.displayName}")
    }

    fun isOnlineAvailable(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
