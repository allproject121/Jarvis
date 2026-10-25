package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.jarvis.assistant.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String, isFatal: Boolean = false, errorCode: String? = null)
        fun onToolCall(callId: String, name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        // Shared OkHttpClient across all sessions — reuses connection pool, DNS cache, and TLS sessions
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(60, TimeUnit.SECONDS)
            .build()
    }

    private val client = sharedClient

    private var activeModel: String = when (model) {
        GeminiConstants.MODEL_3_8_LIVE -> GeminiConstants.MODEL_3_8_LIVE
        GeminiConstants.MODEL_3_1_FLASH_AGENT -> GeminiConstants.MODEL_3_1_FLASH
        else -> if (model.isNotBlank() && !model.contains("2.5")) model else GeminiConstants.DEFAULT_MODEL
    }

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null
    var promptProvider: (() -> String)? = null

    fun isSessionReady(): Boolean = isConnected.get() && isSetupComplete.get()

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API Key is empty. Aborting connection.")
            listener.onError("Gemini API Key is missing. Please configure it in Settings.", isFatal = true, errorCode = "API_KEY_MISSING")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to Gemini Live. Sending BidiSetup...")
                    isConnected.set(true)
                    isSetupComplete.set(false)
                    listener.onConnectionStateChanged("CONFIGURING...")
                    // Send setup message FIRST before enabling audio streaming
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = reason.contains("API_KEY_INVALID", ignoreCase = true) ||
                                       reason.contains("API key not valid", ignoreCase = true) ||
                                       reason.contains("API key", ignoreCase = true) ||
                                       reason.contains("key invalid", ignoreCase = true)
                    if (isApiKeyError) {
                        Log.e(TAG, "Gemini rejected session due to invalid API key (code=$code, reason=$reason)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    val isQuotaError = reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                                       reason.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        Log.w(TAG, "Gemini rate-limited or connection collision (code=$code, reason=$reason). Retrying in cooldown...")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (code == 1008) {
                        val isModelError = reason.contains("BidiGenerateContent", ignoreCase = true) ||
                                           reason.contains("not found", ignoreCase = true) ||
                                           reason.contains("not supported", ignoreCase = true)
                        if (isModelError) {
                            if (activeModel != GeminiConstants.MODEL_3_8_LIVE) {
                                Log.w(TAG, "Model $activeModel rejected by Gemini ($reason). Auto-falling back to ${GeminiConstants.MODEL_3_8_LIVE}")
                                activeModel = GeminiConstants.MODEL_3_8_LIVE
                                listener.onConnectionStateChanged("RECONNECTING...")
                                scheduleReconnect()
                                return
                            }
                        }
                        listener.onError("Gemini setup was rejected: $reason", isFatal = true, errorCode = "SETUP_REJECTED")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val responseBody = try { response?.body?.string() } catch (_: Exception) { null }
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: code=$code, errorMsg=$errorMsg, body=$responseBody", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()

                    val isApiKeyError = code == 400 || code == 401 || code == 403 ||
                            errorMsg.contains("400") || errorMsg.contains("403") || errorMsg.contains("401") ||
                            errorMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                            errorMsg.contains("API key not valid", ignoreCase = true) ||
                            (responseBody?.contains("API_KEY_INVALID", ignoreCase = true) == true) ||
                            (responseBody?.contains("API key not valid", ignoreCase = true) == true)

                    val isQuotaError = code == 429 ||
                            errorMsg.contains("429") ||
                            errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                            errorMsg.contains("quota", ignoreCase = true) ||
                            (responseBody?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true)

                    if (isApiKeyError) {
                        Log.e(TAG, "Fatal failure: Invalid API Key (HTTP $code: $errorMsg)")
                        listener.onError("Invalid API Key: Google rejected the key. Please verify your Gemini API key in Settings.", isFatal = true, errorCode = "API_KEY_INVALID")
                        listener.onConnectionStateChanged("ERROR • INVALID KEY")
                        return
                    }

                    if (isQuotaError) {
                        Log.w(TAG, "Quota / rate limit hit (HTTP $code: $errorMsg). Retrying in cooldown...")
                        listener.onConnectionStateChanged("COOLDOWN • RETRYING")
                        scheduleReconnect()
                        return
                    }

                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...", isFatal = false, errorCode = "CONNECTION_ERROR")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}", isFatal = true, errorCode = "CONNECTION_FAILED")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val effectivePrompt = systemPrompt
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", activeModel)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", effectivePrompt)))
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                    put("temperature", 0.6)
                    // Gemini 2.5 Flash native audio has thinking enabled by default (budget=2048),
                    // which causes a 2-3 second delay before generating speech.
                    // Setting thinking_budget to 0 disables chain-of-thought, yielding instant (<1s) conversational response.
                    if (activeModel.contains("2.5")) {
                        put("thinking_config", JSONObject().apply {
                            put("thinking_budget", 0)
                        })
                    }
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", JSONArray().apply {
                        // 1. open_app
                        put(JSONObject().apply {
                            put("name", "open_app")
                            put("description", "Opens an app installed on the user's phone, e.g. YouTube, WhatsApp, Camera, Settings. If multiple instances exist, app_number specifies 1 or 2.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("app_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The name of the app to open (e.g. \"YouTube\", \"WhatsApp\", \"Instagram\").")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) when opening a dual/cloned app instance.")
                                    })
                                })
                                put("required", JSONArray().put("app_name"))
                            })
                        })

                        // 1B. execute_connected_app_action
                        put(JSONObject().apply {
                            put("name", "execute_connected_app_action")
                            put("description", "Executes deep autonomous actions inside ANY installed application on the user's phone (e.g. Google Drive, Files, WhatsApp, Gmail, Photos, Instagram, Settings). Use whenever the user asks to search files/documents, delete items, upload/save documents, or inspect content inside a specific app (e.g. \"Drive me invoice.pdf search karo\", \"Drive se ye file delete karo\", \"Files me documents check karo\"). When connected, you have 100% full autonomous access and permission. NEVER refuse or say you cannot access it.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("app_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The target app name, e.g. \"Drive\", \"Google Drive\", \"Files\", \"WhatsApp\", \"Gmail\", \"Photos\", \"Instagram\".")
                                    })
                                    put("action_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("search"); put("delete"); put("upload"); put("create"); put("read_content"); put("navigate"); put("custom")
                                        })
                                        put("description", "Action to perform inside the app.")
                                    })
                                    put("target_query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Specific filename, document name, person, or search query.")
                                    })
                                    put("user_goal", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The complete user goal description.")
                                    })
                                })
                                put("required", JSONArray().apply { put("app_name"); put("action_type") })
                            })
                        })

                        // 2. open_website
                        put(JSONObject().apply {
                            put("name", "open_website")
                            put("description", "Opens one or more websites/URLs in Google Chrome in separate tabs. Use whenever the user asks to open a website, visit a URL, or open multiple websites/URLs in different tabs (e.g. \"open github.com and names21st.dev in different tabs\", \"open website google.com\", \"open vercel.com\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("urls", JSONObject().apply {
                                        put("type", "ARRAY")
                                        put("description", "Array of website URLs or domain names to open, each in its own tab (e.g. [\"github.com\", \"names21st.dev\"]).")
                                        put("items", JSONObject().apply { put("type", "STRING") })
                                    })
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional string containing the website URL(s) or query.")
                                    })
                                })
                            })
                        })

                        // 3. search_in_chrome
                        put(JSONObject().apply {
                            put("name", "search_in_chrome")
                            put("description", "Opens Google Chrome and searches for a topic/question, or opens a website URL in a tab (e.g. \"search xyz in Chrome\", \"google xyz\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Query or topic to search in Google Chrome, or website URL.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 4. search_and_play_youtube
                        put(JSONObject().apply {
                            put("name", "search_and_play_youtube")
                            put("description", "Searches YouTube and IMMEDIATELY OPENS AND PLAYS the requested video or song. Use whenever the user directly commands you to PLAY a video or song (e.g. \"play Tum Hi Ho\", \"play Believer on YouTube\", \"play XYZ song or youtube\", \"YouTube pe Arijit Singh chalao\", \"Jarvis video play karo\"). CRITICAL RULE: If the user says 'play', ALWAYS use search_and_play_youtube, NEVER use search_youtube. STRICT PROHIBITION: NEVER use this tool when the user is casually conversing or humming without an explicit play command.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "What video/song to search for and immediately play, e.g. \"Tum Hi Ho Arijit Singh\".")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 5. search_youtube
                        put(JSONObject().apply {
                            put("name", "search_youtube")
                            put("description", "Opens YouTube and searches for a topic/query, displaying the SEARCH RESULTS SCREEN ONLY WITHOUT auto-playing or tapping any video. Use ONLY when the user explicitly asks to SEARCH on YouTube without asking to play (e.g. \"search this on YouTube\", \"YouTube pe search karo xyz\", \"search Python tutorial on YouTube\"). NEVER use this tool when the user says to play a video or song.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The query/topic to search on YouTube, e.g. \"Python tutorial\", \"latest news\".")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 6. youtube_accessibility_action
                        put(JSONObject().apply {
                            put("name", "youtube_accessibility_action")
                            put("description", "Performs an in-app YouTube action using accessibility: skipping an ad ('skip_ad'), liking the video ('like'), subscribing to the channel ('subscribe'), opening the channel ('open_channel'), or toggling fullscreen ('fullscreen').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("skip_ad"); put("like"); put("subscribe"); put("open_channel"); put("fullscreen"); put("click_first")
                                        })
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 7. media_playback_control
                        put(JSONObject().apply {
                            put("name", "media_playback_control")
                            put("description", "Controls whatever video/audio is currently playing (e.g. YouTube): play, pause, next, previous, stop.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("enum", JSONArray().apply {
                                            put("play"); put("pause"); put("next"); put("previous"); put("stop")
                                        })
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 8. send_whatsapp_message
                        put(JSONObject().apply {
                            put("name", "send_whatsapp_message")
                            put("description", "Opens WhatsApp and queues sending a text to a named contact (e.g. \"message Rahul that I will be late\"). The tool response distinguishes opening/queuing from a verified send; inspect the current chat before claiming the message was sent. If 2 WhatsApp apps exist and app_number is not specified, ask the user which one.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the contact as spoken by the user (e.g. \"Rahul\", \"Priya\", \"Dad\").")
                                    })
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The text message content to send to the recipient.")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if user already confirmed sending.")
                                    })
                                })
                                put("required", JSONArray().apply { put("recipient_name"); put("message") })
                            })
                        })

                        // 8b. get_contact_count
                        put(JSONObject().apply {
                            put("name", "get_contact_count")
                            put("description", "Queries the total count of contacts saved in the phone address book using the Android Contacts Provider. Call whenever the user asks how many contacts they have on their phone (e.g. \"mere phone mein kitne contacts hain\", \"phone me kitne contact hai\", \"how many contacts on my phone\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 8c. get_whatsapp_contact_count
                        put(JSONObject().apply {
                            put("name", "get_whatsapp_contact_count")
                            put("description", "Queries the count of contacts in the phone address book synced with WhatsApp, explaining data limitations honestly. Call whenever user asks how many WhatsApp contacts they have (e.g. \"WhatsApp par kitne contacts hain\", \"WhatsApp me kitne contact hai\", \"how many WhatsApp contacts\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 8d. get_app_usage_stats
                        put(JSONObject().apply {
                            put("name", "get_app_usage_stats")
                            put("description", "Queries actual app usage duration for today using Android UsageStatsManager. Call whenever the user asks how long they used an app today (e.g. \"aaj WhatsApp kitni der use kiya\", \"how much did I use WhatsApp today\", \"how long have I used YouTube today\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("app_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the app to query usage for, e.g. \"whatsapp\", \"youtube\", \"chrome\", \"instagram\".")
                                    })
                                })
                            })
                        })

                        // 9. whatsapp_call
                        put(JSONObject().apply {
                            put("name", "whatsapp_call")
                            put("description", "Initiates a WhatsApp voice call or WhatsApp video call to a contact (e.g. \"WhatsApp call Mom\", \"WhatsApp video call Rahul\").")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the contact to call as spoken by the user.")
                                    })
                                    put("call_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "\"voice\" for voice call or \"video\" for video call.")
                                    })
                                    put("app_number", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based index (1 or 2) if dual WhatsApp is installed.")
                                    })
                                    put("confirmed", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if user already confirmed calling.")
                                    })
                                })
                                put("required", JSONArray().apply { put("recipient_name"); put("call_type") })
                            })
                        })

                        // 10. call_contact
                        put(JSONObject().apply {
                            put("name", "call_contact")
                            put("description", "Places a standard phone call to a saved contact by spoken name, e.g. \"call mom\", \"call Rahul\", \"phone Dad\".")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contact_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The contact's name as the user said it, e.g. \"mom\", \"Rahul\".")
                                    })
                                })
                                put("required", JSONArray().put("contact_name"))
                            })
                        })

                        // 11. set_volume
                        put(JSONObject().apply {
                            put("name", "set_volume")
                            put("description", "Adjusts media volume on device. Supports relative commands ('increase', 'decrease') or exact level ('set').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'increase', 'decrease', or 'set'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target percentage level from 0 to 100 if specified.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 12. set_brightness
                        put(JSONObject().apply {
                            put("name", "set_brightness")
                            put("description", "Adjusts screen brightness on device. Supports relative commands ('increase', 'decrease') or exact level ('set').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'increase', 'decrease', or 'set'.")
                                    })
                                    put("percentage", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Target percentage level from 0 to 100 if specified.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 13. builtin_chrome_search
                        put(JSONObject().apply {
                            put("name", "builtin_chrome_search")
                            put("description", "Performs invisible, background web research using JARVIS's built-in Chrome engine. Use whenever the user asks a question, topic, real-time factual query, news, weather, or live information that you don't know off-hand.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Search query or topic to look up on the web.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 14. go_to_background
                        put(JSONObject().apply {
                            put("name", "go_to_background")
                            put("description", "Sends JARVIS to the background, minimizes the app to the home screen, and enters standby wake-word listening. Use immediately whenever user says 'go to background', 'go background', 'minimize', 'close', 'background me jao', 'go to home screen', 'go to sleep', or 'enter standby'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 15. set_flashlight
                        put(JSONObject().apply {
                            put("name", "set_flashlight")
                            put("description", "Turns the phone's torch or flashlight on or off. ALWAYS call this tool unconditionally whenever the user commands to turn on, enable, turn off, or disable the torch/flashlight (e.g. \"turn on flash lite\", \"turn on flashlight\", \"turn off flashlight\", \"torch on\", \"torch off\", \"torch chalu karo\", \"torch band karo\"). NEVER claim it is already on or off without calling this tool.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("enabled", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to turn on the torch/flashlight, false to turn it off.")
                                    })
                                })
                                put("required", JSONArray().put("enabled"))
                            })
                        })

                        // 16. click_ui_element
                        put(JSONObject().apply {
                            put("name", "click_ui_element")
                            put("description", "Clicks or toggles a specific UI button, switch, link, icon, tab, or element. Prefer a unique current element_id or target_name. When Screen Share is active, normalized coordinates x/y (0-1000) may be used for a clearly visible unlabeled target; the tool checks the hit target and returns whether a screen change was verified. Never treat a dispatched gesture alone as proof of success.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("target_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Label, text, icon name, or description of the UI element to click (e.g., 'Generate', 'Arrow', 'Video', 'Search', 'Install').")
                                    })
                                    put("x", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized X coordinate (0-1000) of the visual center of the element on screen.")
                                    })
                                    put("y", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized Y coordinate (0-1000) of the visual center of the element on screen.")
                                    })
                                    put("element_id", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional 1-based element ID if known from current screen state summary.")
                                    })
                                    put("is_toggle", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True if flipping a toggle switch or checkbox.")
                                    })
                                    put("description", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Additional visual context.")
                                    })
                                })
                                put("required", JSONArray().put("target_name"))
                            })
                        })

                        // 17. type_text
                        put(JSONObject().apply {
                            put("name", "type_text")
                            put("description", "Types text into a grounded input field. The result is successful only when the text is read back from the target; a clipboard paste or dispatched gesture alone is not proof. For Google Flow, use the named prompt field or, only with a confirmed live Screen Share frame, current normalized x/y coordinates of the visible prompt box. Never report text entered unless success=true and verified=true.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("text", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The exact text query or prompt to type.")
                                    })
                                    put("x", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized X coordinate (0-1000) of the text box center on screen.")
                                    })
                                    put("y", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Normalized Y coordinate (0-1000) of the text box center on screen.")
                                    })
                                    put("target_input_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional name or hint of the input field (e.g., 'What do you want to create', 'Search').")
                                    })
                                    put("submit", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "Whether to submit or press enter after typing.")
                                    })
                                })
                                put("required", JSONArray().put("text"))
                            })
                        })

                        // 18. scroll_screen
                        put(JSONObject().apply {
                            put("name", "scroll_screen")
                            put("description", "Scrolls the active screen in a given direction to reveal elements that are offscreen. Supports gentle line-by-line scrolling for inspecting photos and lists.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("direction", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Direction to scroll: 'down', 'up', 'left', or 'right'.")
                                    })
                                    put("distance", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional scroll distance: 'small' (gentle line-by-line scroll, moves 1-2 rows at a time with zero fling, mandatory for browsing photos and documents), 'medium' (half screen), or 'large' (full page). Defaults to 'small'.")
                                    })
                                })
                                put("required", JSONArray().put("direction"))
                            })
                        })

                        // 19. press_key
                        put(JSONObject().apply {
                            put("name", "press_key")
                            put("description", "Executes system-level navigation gestures: 'back', 'home', or 'recents'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("key", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The system action: 'back', 'home', or 'recents'.")
                                    })
                                })
                                put("required", JSONArray().put("key"))
                            })
                        })

                        // 20. task_completed
                        put(JSONObject().apply {
                            put("name", "task_completed")
                            put("description", "Call this tool only after the user's requested task is visibly confirmed complete. Do not call it right after typing into a search bar; when searching for a chat or file, first open the matching result and verify the resulting screen.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("summary", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Brief summary of what was completed (e.g., 'Opened Bharat's chat on Telegram.').")
                                    })
                                })
                                put("required", JSONArray().put("summary"))
                            })
                        })

                        // 20a. get_task_status
                        put(JSONObject().apply {
                            put("name", "get_task_status")
                            put("description", "Queries the current status, step progress, or verification result of an active or recent automation task. Call when the user asks about task status (e.g. 'what is the status of my task?', 'task ka kya hua?', 'status batao').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("task_id", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional unique task ID to query; if omitted, queries the active or most recent task.")
                                    })
                                })
                            })
                        })

                        // 20b. get_active_task
                        put(JSONObject().apply {
                            put("name", "get_active_task")
                            put("description", "Returns what Jarvis is currently doing right now, including step progress and last screen observation. Call when the user asks 'what are you doing right now?', 'abhi kya kar rahe ho?', 'current task batao'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 20c. get_failed_tasks
                        put(JSONObject().apply {
                            put("name", "get_failed_tasks")
                            put("description", "Queries recent failed tasks with their failure reasons and recovery evidence. Call when the user asks to see failed tasks or what went wrong (e.g. 'which tasks failed?', 'kaunse tasks fail hue?').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("limit", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Maximum number of recent failed tasks to return (default 5).")
                                    })
                                })
                            })
                        })

                        // 20d. cancel_active_task
                        put(JSONObject().apply {
                            put("name", "cancel_active_task")
                            put("description", "Cancels the active automation task cleanly and releases all resources. Call when the user asks to stop or cancel (e.g. 'cancel task', 'task band kar do', 'ruko', 'stop what you are doing').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("reason", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Reason for cancellation.")
                                    })
                                })
                            })
                        })

                        // 20e. get_last_failure_reason
                        put(JSONObject().apply {
                            put("name", "get_last_failure_reason")
                            put("description", "Explains why the last task failed, what was observed on screen, and whether user action is required. Call when the user asks 'why did it fail?', 'kyu fail hua?', 'pichla task kyu nahi chala?'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 20f. resume_task
                        put(JSONObject().apply {
                            put("name", "resume_task")
                            put("description", "Resumes an interrupted or paused task if safe (blocks automatic resumption for sensitive/non-idempotent actions like sending messages or calls). Call when the user asks to resume or continue (e.g. 'resume task', 'task wapas shuru karo', 'continue karo').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("task_id", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional task ID to resume.")
                                    })
                                })
                            })
                        })

                        // 20g. control_smart_home
                        put(JSONObject().apply {
                            put("name", "control_smart_home")
                            put("description", "Controls smart home devices (lights, switches, plugs, climate, scenes) via Home Assistant or Webhook. Call when the user asks to turn lights on/off, toggle switches, change lights, blink lights, etc. (e.g. 'light on karo', 'bedroom switch off karo', 'turn off all lights', 'dim lights', 'blink the lights').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("target", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name or entity of the device or group, e.g. 'living room lights', 'bedroom', 'fan', 'all', or 'light.living_room'.")
                                    })
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Action to perform: 'on', 'off', 'toggle', 'blink', 'party', 'dim'.")
                                    })
                                    put("delay_sec", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Optional delay in seconds before triggering.")
                                    })
                                })
                                put("required", JSONArray().apply {
                                    put("target")
                                    put("action")
                                })
                            })
                        })

                        // 20h. get_smart_home_devices
                        put(JSONObject().apply {
                            put("name", "get_smart_home_devices")
                            put("description", "Retrieves the current states of smart home devices connected to Home Assistant. Call when the user asks what devices are available or if lights/switches are currently on or off.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 20g. analyze_code_repository
                        put(JSONObject().apply {
                            put("name", "analyze_code_repository")
                            put("description", "Analyzes a local code repository directory, ZIP archive, or public HTTPS GitHub URL in read-only mode, reporting structure, detected frameworks, dependencies, and integration points with security protections. Call when user asks to analyze, inspect, or understand a local codebase, ZIP file, or GitHub repo.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("repo_path", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Absolute path to local repo directory, ZIP archive (e.g. '/sdcard/Download/project.zip'), or public HTTPS GitHub URL (e.g. 'https://github.com/owner/repo').")
                                    })
                                    put("max_depth", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Maximum directory scan depth (1 to 8, default 4).")
                                    })
                                    put("include_dependencies", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "Whether to extract and summarize declared dependencies (default true).")
                                    })
                                })
                                put("required", JSONArray().apply { put("repo_path") })
                            })
                        })

                        // 20h. inspect_integration_points
                        put(JSONObject().apply {
                            put("name", "inspect_integration_points")
                            put("description", "Inspects cross-codebase mapping and architecture compatibility between an external repository and Jarvis modules (skills, connectors, UI, agent) for a requested feature.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("repo_path", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Path to repository directory, ZIP archive, or public HTTPS GitHub URL.")
                                    })
                                    put("feature_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of feature to integrate (e.g. 'Offline Music Player', 'Battery Optimizer').")
                                    })
                                })
                                put("required", JSONArray().apply { put("repo_path") })
                            })
                        })

                        // 20i. generate_integration_proposal
                        put(JSONObject().apply {
                            put("name", "generate_integration_proposal")
                            put("description", "Generates a structured, reviewable integration proposal with diff preview and a mandatory user approval token. Modifications cannot be applied without this approval token.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("feature_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the feature being integrated.")
                                    })
                                    put("description", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Description of integration architecture and Jarvis mapping.")
                                    })
                                    put("target_module", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target Jarvis subsystem: 'skills', 'ui', 'connectors', or 'agent'.")
                                    })
                                })
                                put("required", JSONArray().apply { put("feature_name") })
                            })
                        })

                        // 20j. review_integration_diff
                        put(JSONObject().apply {
                            put("name", "review_integration_diff")
                            put("description", "Reviews the proposed code changes and unified diff preview for a pending integration proposal before approval. Provide the proposal_id (or 'latest' for the newest proposal, or feature name like 'Spoon-Knife').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("proposal_id", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The unique proposal ID (e.g. 'proposal_1792514327636_6306288f'), 'latest', or feature name (e.g. 'Spoon-Knife').")
                                    })
                                    put("feature_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional name of the feature being reviewed (e.g. 'Spoon-Knife Demo Screen').")
                                    })
                                })
                                put("required", JSONArray().apply { put("proposal_id") })
                            })
                        })

                        // 20k. apply_integration_proposal
                        put(JSONObject().apply {
                            put("name", "apply_integration_proposal")
                            put("description", "Applies an approved integration proposal to the codebase. STRICT REQUIREMENT: Requires explicit user approval_token. Automatically creates pre-application backup checkpoints.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("proposal_id", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Proposal ID returned by generate_integration_proposal.")
                                    })
                                    put("approval_token", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Explicit user confirmation approval token.")
                                    })
                                })
                                put("required", JSONArray().apply {
                                    put("proposal_id")
                                    put("approval_token")
                                })
                            })
                        })

                        // 20l. rollback_integration
                        put(JSONObject().apply {
                            put("name", "rollback_integration")
                            put("description", "Atomically rolls back an applied integration proposal, restoring files to their exact pre-modification state using the checkpoint_id.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("checkpoint_id", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The checkpoint ID generated during apply_integration_proposal.")
                                    })
                                })
                                put("required", JSONArray().apply { put("checkpoint_id") })
                            })
                        })

                        // 20m. open_spoon_knife_demo
                        put(JSONObject().apply {
                            put("name", "open_spoon_knife_demo")
                            put("description", "Opens the optional Spoon-Knife demo screen rendering the static HTML/CSS frontend in an isolated WebView.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 20n. close_spoon_knife_demo
                        put(JSONObject().apply {
                            put("name", "close_spoon_knife_demo")
                            put("description", "Closes the currently visible Spoon-Knife demo screen and returns to Jarvis.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 21. inspect_screen
                        put(JSONObject().apply {
                            put("name", "inspect_screen")
                            put("description", "Reads the foreground app, screen title, and accessible UI elements (text, buttons, tabs). This tool returns accessibility text, not a visual image. Only claim live visual access when Screen Share is confirmed active and a recent captured frame has been received. Call when you need current accessible labels or element IDs before interacting, or when the user asks about screen content. Do not use for normal conversation.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 22. open_gods_eye_view
                        put(JSONObject().apply {
                            put("name", "open_gods_eye_view")
                            put("description", "Opens the full-screen interactive God's Eye View 3D geospatial Earth console. Call when the user asks to open God's Eye View, launch the globe, show Earth console, or inspect planetary tracking (e.g. 'open God's Eye View', 'God's Eye chalu karo', 'show globe').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("style", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Visual HUD style: 'default', 'thermal', 'nvg', or 'crt'.")
                                    })
                                })
                            })
                        })

                        // 23. gods_eye_show_view
                        put(JSONObject().apply {
                            put("name", "gods_eye_show_view")
                            put("description", "Visualizes any location, city, coordinate, or region on the full-screen God's Eye View 3D Earth console and brings it on screen. Use whenever user asks to look at or view a place on God's Eye View or the globe (e.g. 'show Tokyo in God's Eye View', 'look at Ukraine on the globe', 'show earthquakes in Japan on God's Eye View').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("location", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of city, region, or coordinates (e.g. 'Tokyo', 'Hawaii', 'London', 'Persian Gulf').")
                                    })
                                    put("style", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Visual HUD style: 'default', 'thermal', 'nvg', or 'crt'.")
                                    })
                                })
                                put("required", JSONArray().put("location"))
                            })
                        })

                        // 24. gods_eye_situation_brief
                        put(JSONObject().apply {
                            put("name", "gods_eye_situation_brief")
                            put("description", "Generates an integrated live situational awareness briefing for a region or global theater, analyzing active military flights, civilian air traffic, weather, seismic activity, fires, and satellite passes. Use whenever user asks for a situation report or theater brief (e.g. 'situation brief of Persian Gulf', 'tactical report on Taiwan').")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the place, region, or theater to brief on (e.g. 'Persian Gulf', 'Black Sea', 'Tokyo').")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to simultaneously display the theater on the God's Eye View 3D globe console.")
                                    })
                                })
                                put("required", JSONArray().put("area"))
                            })
                        })

                        // 25. gods_eye_get_earthquakes
                        put(JSONObject().apply {
                            put("name", "gods_eye_get_earthquakes")
                            put("description", "Queries live real-time global seismic activity and earthquakes from USGS. Use when user asks about recent earthquakes, tremors, or seismic activity.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional name of city, country, or region (e.g. 'Japan', 'California', 'Turkey').")
                                    })
                                    put("min_magnitude", JSONObject().apply {
                                        put("type", "NUMBER")
                                        put("description", "Optional minimum Richter magnitude filter (e.g. 4.5, 5.0).")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to display earthquakes on God's Eye View console.")
                                    })
                                })
                            })
                        })

                        // 26. gods_eye_get_active_fires
                        put(JSONObject().apply {
                            put("name", "gods_eye_get_active_fires")
                            put("description", "Queries live global thermal anomalies, wildfires, and active fire perimeters detected by NASA FIRMS satellite instruments. Use when user asks about wildfires or active fires.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional region or country name (e.g. 'Canada', 'Greece', 'California').")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to display fires on God's Eye View console.")
                                    })
                                })
                            })
                        })

                        // 27. gods_eye_aircraft_in_area
                        put(JSONObject().apply {
                            put("name", "gods_eye_aircraft_in_area")
                            put("description", "Tracks live civilian and military aircraft in flight over a specified area using ADS-B telemetry feeds. Returns callsigns, altitude, and heading.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Region, city, or coordinates (e.g. 'London', 'New York', 'Delhi').")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to display air traffic on God's Eye View console.")
                                    })
                                })
                                put("required", JSONArray().put("area"))
                            })
                        })

                        // 28. gods_eye_satellites_overhead
                        put(JSONObject().apply {
                            put("name", "gods_eye_satellites_overhead")
                            put("description", "Calculates active satellites currently orbiting directly overhead or passing over a specified location using NORAD/CelesTrak orbital elements.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "City, region, or coordinates (e.g. 'Austin', 'Tokyo', 'Mumbai').")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to show satellite orbits on God's Eye View console.")
                                    })
                                })
                                put("required", JSONArray().put("area"))
                            })
                        })

                        // 29. gods_eye_get_recent_launches
                        put(JSONObject().apply {
                            put("name", "gods_eye_get_recent_launches")
                            put("description", "Queries upcoming and recently conducted orbital rocket launches, space missions, launch sites, and countdowns from Launch Library 2.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 30. gods_eye_military_awareness
                        put(JSONObject().apply {
                            put("name", "gods_eye_military_awareness")
                            put("description", "Queries active military air assets, reconnaissance flights, tankers, and tactical aircraft detected via adsb.lol telemetry.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Airspace, theater, or country to scan (e.g. 'Middle East', 'Eastern Europe').")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to display on God's Eye View console.")
                                    })
                                })
                                put("required", JSONArray().put("area"))
                            })
                        })

                        // 31. gods_eye_find_cctv_cameras
                        put(JSONObject().apply {
                            put("name", "gods_eye_find_cctv_cameras")
                            put("description", "Locates live public traffic and city CCTV surveillance cameras near a given location (e.g. Austin, London, Sydney, Helsinki, Caltrans California) and retrieves camera status.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("area", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "City or region name.")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to focus camera locations on God's Eye View console.")
                                    })
                                })
                                put("required", JSONArray().put("area"))
                            })
                        })

                        // 32. gods_eye_inspect_globe
                        put(JSONObject().apply {
                            put("name", "gods_eye_inspect_globe")
                            put("description", "Inspects the current state of the God's Eye 3D globe console: camera coordinates (lat, lon, altitude), heading, pitch, active visualization layers, selected CCTV camera, visual HUD style, and rendering status. Call before and after performing globe operations to inspect or verify the screen state.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 33. gods_eye_control_layers
                        put(JSONObject().apply {
                            put("name", "gods_eye_control_layers")
                            put("description", "Enables, disables, or toggles visualization layers on God's Eye View (e.g. 'earthquakes', 'cctv', 'flights', 'military', 'firms', 'satellites', 'launches'). Accepts layer arrays to enable/disable or a layer name to toggle. Dispatches immediately to the globe and returns verified layer state.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("enable", JSONObject().apply {
                                        put("type", "ARRAY")
                                        put("description", "Array of layer IDs to turn ON (e.g. [\"earthquakes\", \"military\"]).")
                                        put("items", JSONObject().apply { put("type", "STRING") })
                                    })
                                    put("disable", JSONObject().apply {
                                        put("type", "ARRAY")
                                        put("description", "Array of layer IDs to turn OFF (e.g. [\"flights\"]).")
                                        put("items", JSONObject().apply { put("type", "STRING") })
                                    })
                                    put("toggle", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Single layer ID to toggle on or off.")
                                    })
                                    put("style", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional visual style HUD mode: 'normal', 'thermal', 'nvg', 'crt'.")
                                    })
                                })
                            })
                        })

                        // 34. gods_eye_select_camera
                        put(JSONObject().apply {
                            put("name", "gods_eye_select_camera")
                            put("description", "Selects, flies to, and opens a specific CCTV camera feed on the God's Eye 3D globe. Highlight the camera entity and open the live video stream or snapshot player modal. Never guess camera IDs; use IDs returned by gods_eye_find_cctv_cameras.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("camera_id", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The unique CCTV camera ID (e.g. 'tfl-00002.00865', 'nsw-201', '354', 'caltrans-d7-1').")
                                    })
                                })
                                put("required", JSONArray().put("camera_id"))
                            })
                        })

                        // 35. gods_eye_get_space_weather
                        put(JSONObject().apply {
                            put("name", "gods_eye_get_space_weather")
                            put("description", "Queries NOAA SWPC real-time solar space weather and geomagnetic activity (planetary Kp index). Use when user asks about solar storms, space weather, geomagnetic storms, or aurora visibility.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to fly Gods Eye to the polar auroral oval.")
                                    })
                                })
                            })
                        })

                        // 36. gods_eye_get_subsea_cables
                        put(JSONObject().apply {
                            put("name", "gods_eye_get_subsea_cables")
                            put("description", "Queries global submarine fiber-optic cables and landing stations. Use when user asks about undersea internet cables, telecom backbone, or subsea infrastructure.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("region", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional ocean or region (e.g. Atlantic, Pacific, India).")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to display cables layer on Gods Eye globe.")
                                    })
                                })
                            })
                        })

                        // 37. gods_eye_get_public_transit
                        put(JSONObject().apply {
                            put("name", "gods_eye_get_public_transit")
                            put("description", "Queries real-time public transit vehicle positions (e.g. Boston MBTA GTFS-RT). Use when user asks about public transit, buses, or trains.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("city", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "City or agency name (e.g. Boston).")
                                    })
                                    put("show_on_globe", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to display live transit vehicles on Gods Eye globe.")
                                    })
                                })
                            })
                        })

                        // 38. gods_eye_start_auto_tour
                        put(JSONObject().apply {
                            put("name", "gods_eye_start_auto_tour")
                            put("description", "Activates the Autonomous Sentinel Tour on the Gods Eye 3D globe console, automatically cycling every 14s through 13 planetary sensor vantage points (flights, CCTV, earthquakes, space launches, cables, weather radar, lightning density).")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 35. add_memory (Mem0 Persistent Memory)
                        put(JSONObject().apply {
                            put("name", "add_memory")
                            put("description", "Stores a persistent user preference, fact, routine, contact, or life detail into Mem0 long-term memory across sessions.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("fact", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The factual statement or preference to permanently remember.")
                                    })
                                    put("category", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Category: 'preferences', 'routines', 'contacts', 'work', or 'notes'.")
                                    })
                                })
                                put("required", JSONArray().put("fact"))
                            })
                        })

                        // 36. search_memories (Mem0 Persistent Memory)
                        put(JSONObject().apply {
                            put("name", "search_memories")
                            put("description", "Searches Mem0 persistent semantic long-term memory for relevant facts, preferences, routines, or past user details.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Semantic search query or topic to recall.")
                                    })
                                })
                                put("required", JSONArray().put("query"))
                            })
                        })

                        // 37. delete_memory (Mem0 Persistent Memory)
                        put(JSONObject().apply {
                            put("name", "delete_memory")
                            put("description", "Deletes an outdated, corrected, or requested fact from Mem0 long-term memory.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("keyword", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Keyword or phrase matching the fact to remove.")
                                    })
                                })
                                put("required", JSONArray().put("keyword"))
                            })
                        })

                        // 38. reply_to_message
                        put(JSONObject().apply {
                            put("name", "reply_to_message")
                            put("description", "Sends a direct reply to an incoming WhatsApp, Telegram, or SMS message without opening the app, using the notification reply action.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("sender", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The name of the sender or contact who messaged.")
                                    })
                                    put("text", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The reply message to send.")
                                    })
                                })
                                put("required", JSONArray().put("text"))
                            })
                        })

                        // 39. handle_phone_call
                        put(JSONObject().apply {
                            put("name", "handle_phone_call")
                            put("description", "Answers or declines an incoming ringing telephone call.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "'answer' to pick up the call, or 'decline'/'reject' to hang up.")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 40. manage_leads (Customer CRM)
                        put(JSONObject().apply {
                            put("name", "manage_leads")
                            put("description", "Manages customer sales leads in the local CRM. Actions: 'add', 'list', 'update_status', 'search', 'delete', 'export'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "'add', 'list', 'update_status', 'search', 'delete', or 'export'")
                                    })
                                    put("name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Lead contact name")
                                    })
                                    put("phone", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Customer phone number")
                                    })
                                    put("company", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Company or business name")
                                    })
                                    put("status", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Lead stage: NEW, CONTACTED, FOLLOW_UP_SCHEDULED, CONVERTED, LOST")
                                    })
                                    put("notes", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Interaction notes or deal requirements")
                                    })
                                    put("deal_value", JSONObject().apply {
                                        put("type", "NUMBER")
                                        put("description", "Estimated deal value in INR/USD")
                                    })
                                })
                                put("required", JSONArray().put("action"))
                            })
                        })

                        // 41. lead_followup (Drip Reminder Engine)
                        put(JSONObject().apply {
                            put("name", "lead_followup")
                            put("description", "Schedules an automated or timed follow-up reminder for a lead or client.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("phone", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Customer phone number")
                                    })
                                    put("lead_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Lead name")
                                    })
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Follow-up message or discussion agenda")
                                    })
                                    put("delay_minutes", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Delay in minutes before reminder triggers (e.g. 15, 60, 1440)")
                                    })
                                })
                                put("required", JSONArray().put("phone").put("message"))
                            })
                        })

                        // 42. draft_review_reply (Customer Review Drafter)
                        put(JSONObject().apply {
                            put("name", "draft_review_reply")
                            put("description", "Generates professional, high-conversion responses to customer reviews (1-5 stars) on Google Maps, Play Store, or marketplace.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("review_text", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The customer's review text")
                                    })
                                    put("rating", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Star rating from 1 to 5")
                                    })
                                    put("business_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Your business name")
                                    })
                                    put("customer_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Customer name if known")
                                    })
                                    put("language", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "'english', 'hindi', or 'hinglish'")
                                    })
                                })
                                put("required", JSONArray().put("review_text").put("rating"))
                            })
                        })

                        // 43. generate_n8n_workflow (Automation Workflow Graph)
                        put(JSONObject().apply {
                            put("name", "generate_n8n_workflow")
                            put("description", "Generates production-ready, importable n8n workflow JSON graphs for automated multi-step pipelines.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("workflow_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Descriptive name for the n8n workflow")
                                    })
                                    put("trigger_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "'webhook' or 'schedule'")
                                    })
                                    put("action_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "'whatsapp', 'slack', 'telegram', 'sheets', or 'http'")
                                    })
                                    put("destination_endpoint", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Webhook URL, channel, or target ID")
                                    })
                                })
                                put("required", JSONArray().put("workflow_name"))
                            })
                        })

                        // 44. send_webhook (Generic Webhook Dispatcher)
                        put(JSONObject().apply {
                            put("name", "send_webhook")
                            put("description", "Dispatches an HTTP POST/GET webhook to an external automation endpoint (n8n, Zapier, Make, server).")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("url", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target webhook URL")
                                    })
                                    put("method", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "'POST', 'GET', or 'PUT'")
                                    })
                                    put("payload", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "JSON payload string")
                                    })
                                })
                                put("required", JSONArray().put("url"))
                            })
                        })

                        // 45. github_clone_repo (GitHub Repository Cloner & Inspector)
                        put(JSONObject().apply {
                            put("name", "github_clone_repo")
                            put("description", "Inspects or clones a GitHub repository locally via Termux Linux environment.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("repo_url", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "GitHub repository URL or owner/repo format")
                                    })
                                    put("destination_folder", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional local folder name in Termux")
                                    })
                                })
                                put("required", JSONArray().put("repo_url"))
                            })
                        })

                        // 46. github_agent_edit (GitHub Commit & Push Agent)
                        put(JSONObject().apply {
                            put("name", "github_agent_edit")
                            put("description", "Stages, commits, and pushes modified code in a cloned git repository via Termux.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("repo_folder", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Path to local cloned repo in Termux")
                                    })
                                    put("commit_message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Git commit message")
                                    })
                                })
                                put("required", JSONArray().put("repo_folder").put("commit_message"))
                            })
                        })

                        // 47. create_website (Website Sandbox Creator)
                        put(JSONObject().apply {
                            put("name", "create_website")
                            put("description", "Builds a complete, modern responsive website (HTML5, Tailwind, JS) and opens instant live preview in Chrome.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("project_name", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Name of the website project")
                                    })
                                    put("title", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Page title")
                                    })
                                    put("html_body", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "HTML content for the page body")
                                    })
                                    put("custom_css", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional CSS styles")
                                    })
                                    put("custom_js", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional JavaScript logic")
                                    })
                                })
                                put("required", JSONArray().put("project_name").put("html_body"))
                            })
                        })

                        // 48. get_live_traffic
                        put(JSONObject().apply {
                            put("name", "get_live_traffic")
                            put("description", "Retrieves live real-time traffic flow, congestion levels, delay percentages, and travel speeds for a city or area using TomTom traffic telemetry.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("location", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "City or area name (e.g. 'Mumbai', 'Delhi', 'Bengaluru', 'London', 'my location')")
                                    })
                                    put("lat", JSONObject().apply {
                                        put("type", "NUMBER")
                                        put("description", "Optional latitude coordinate")
                                    })
                                    put("lon", JSONObject().apply {
                                        put("type", "NUMBER")
                                        put("description", "Optional longitude coordinate")
                                    })
                                })
                            })
                        })

                        // 49. get_crypto_price
                        put(JSONObject().apply {
                            put("name", "get_crypto_price")
                            put("description", "Fetches live cryptocurrency prices in USD and INR, along with 24-hour market percentage changes for Bitcoin, Ethereum, Solana, and other tokens via CoinGecko and Binance.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Cryptocurrency name or symbol (e.g. 'bitcoin', 'btc', 'eth', 'solana', 'doge', 'crypto market')")
                                    })
                                })
                            })
                        })

                        // 50. get_iss_telemetry
                        put(JSONObject().apply {
                            put("name", "get_iss_telemetry")
                            put("description", "Tracks the real-time orbital location, speed (27,600 km/h), altitude (420 km), overhead region, and current astronauts aboard the International Space Station (ISS) in space.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })

                        // 50B. get_wikipedia_summary
                        put(JSONObject().apply {
                            put("name", "get_wikipedia_summary")
                            put("description", "Retrieves verified encyclopedia knowledge, historical facts, biographies, scientific concepts, or definitions for any topic directly from Wikipedia public API.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The person, concept, place, technology, or topic to look up (e.g. 'Quantum Computing', 'Albert Einstein', 'James Webb Space Telescope').")
                                    })
                                })
                                put("required", JSONArray().apply { put("query") })
                            })
                        })

                        // 51. get_live_weather_and_aqi
                        put(JSONObject().apply {
                            put("name", "get_live_weather_and_aqi")
                            put("description", "Retrieves live temperature, weather conditions, humidity, wind speed, PM2.5, and Air Quality Index (AQI/pollution level) for any city or current location.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("location", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "City name or 'here' for current location (e.g. 'Delhi', 'Mumbai', 'Pune', 'New York')")
                                    })
                                    put("lat", JSONObject().apply {
                                        put("type", "NUMBER")
                                        put("description", "Optional latitude")
                                    })
                                    put("lon", JSONObject().apply {
                                        put("type", "NUMBER")
                                        put("description", "Optional longitude")
                                    })
                                })
                            })
                        })

                        // 52. open_gods_eye_radar
                        put(JSONObject().apply {
                            put("name", "open_gods_eye_radar")
                            put("description", "Opens or focuses the full-screen 3D God's Eye geospatial Earth radar console. Supports specialized layers: 'flights' (air traffic), 'satellites' (orbital assets / ISS), 'traffic' (TomTom vector flow & CCTV), 'fires' (NASA FIRMS hotspots), 'earthquakes' (seismic), 'weather' (radar & clouds), or 'all'.")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("radar_type", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Type of radar: 'flights', 'satellites', 'traffic', 'fires', 'earthquakes', 'weather', or 'all'")
                                    })
                                    put("location", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Target city or country to center and fly to on the 3D globe")
                                    })
                                })
                            })
                        })
                    })
                }))
            })
        }
        val json = setup.toString()
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live with tools")
        ws.send(json)
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // Check if server returned an error payload
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "") ?: json.optString("error", "Unknown error")
                val errCode = errObj?.optInt("code", 0) ?: 0
                Log.e(TAG, "Server returned error: code=$errCode, message=$errMsg")
                val isApiKey = errMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                               errMsg.contains("API key not valid", ignoreCase = true) ||
                               errCode == 400 || errCode == 403
                val isQuota = errMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                              errMsg.contains("quota", ignoreCase = true) ||
                              errCode == 429
                if (isApiKey) {
                    listener.onError("Invalid API Key: $errMsg", isFatal = true, errorCode = "API_KEY_INVALID")
                    listener.onConnectionStateChanged("ERROR • INVALID KEY")
                } else if (isQuota) {
                    listener.onError("API Quota Exceeded: $errMsg", isFatal = true, errorCode = "QUOTA_EXCEEDED")
                    listener.onConnectionStateChanged("ERROR • QUOTA EXCEEDED")
                } else {
                    listener.onError("Gemini Error ($errCode): $errMsg", isFatal = false)
                }
                return
            }

            // 0. Server setupComplete handshake acknowledgment
            if (json.has("setupComplete")) {
                Log.i(TAG, "Server acknowledged setup: setupComplete received. Session is now LIVE!")
                if (!isSetupComplete.getAndSet(true)) {
                    listener.onConnectionStateChanged("LIVE")
                    startSessionRenewal()
                }
                return
            }

            // Ensure session is marked ready if server sends content before explicit setupComplete
            if (!isSetupComplete.get() && (json.has("serverContent") || json.has("toolCall"))) {
                isSetupComplete.set(true)
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewal()
            }

            // 1. Top-level toolCall
            val topToolCall = json.optJSONObject("toolCall")
            if (topToolCall != null) {
                val functionCalls = topToolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    for (i in 0 until functionCalls.length()) {
                        val call = functionCalls.getJSONObject(i)
                        val name = call.optString("name", "")
                        val id = call.optString("id", "")
                        val args = call.optJSONObject("args") ?: JSONObject()
                        if (name.isNotEmpty()) {
                            Log.i(TAG, "Gemini invoked toolCall: $name (id=$id)")
                            listener.onToolCall(id, name, args)
                        }
                    }
                }
                return
            }

            // 2. serverContent
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                val scToolCall = serverContent.optJSONObject("toolCall")
                if (scToolCall != null) {
                    val functionCalls = scToolCall.optJSONArray("functionCalls")
                    if (functionCalls != null) {
                        for (i in 0 until functionCalls.length()) {
                            val call = functionCalls.getJSONObject(i)
                            val name = call.optString("name", "")
                            val id = call.optString("id", "")
                            val args = call.optJSONObject("args") ?: JSONObject()
                            if (name.isNotEmpty()) {
                                Log.i(TAG, "Gemini invoked toolCall (serverContent): $name (id=$id)")
                                listener.onToolCall(id, name, args)
                            }
                        }
                    }
                }

                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    var userText = inputTrans.optString("text", "").trim()
                    if (userText.isEmpty()) {
                        val parts = inputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            userText = sb.toString().trim()
                        }
                    }
                    if (userText.isNotBlank()) {
                        listener.onUserTextReceived(userText)
                    }
                }

                var hasOutputTranscriptionText = false
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    var outText = outputTrans.optString("text", "")
                    if (outText.isEmpty()) {
                        val parts = outputTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (p in 0 until parts.length()) {
                                val pObj = parts.optJSONObject(p)
                                sb.append(pObj?.optString("text", "") ?: "")
                            }
                            outText = sb.toString()
                        }
                    }
                    if (outText.isNotEmpty()) {
                        hasOutputTranscriptionText = true
                        listener.onAssistantTextReceived(outText)
                    }
                }

                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Filter out internal thinking/reasoning parts (Gemini 2.5 thinking tokens)
                            val isThought = part.optBoolean("thought", false)
                            if (isThought) {
                                Log.d(TAG, "Suppressed model thought part: ${part.optString("text").take(40)}")
                                continue
                            }
                            val text = part.optString("text", "")
                            val isThinkingLeak = text.startsWith("**") && (
                                    text.contains("thinking", ignoreCase = true) ||
                                    text.contains("crafting", ignoreCase = true) ||
                                    text.contains("formulating", ignoreCase = true) ||
                                    text.contains("acknowledge", ignoreCase = true) ||
                                    text.contains("inquire", ignoreCase = true) ||
                                    text.contains("greeting", ignoreCase = true)
                            )
                            if (text.isNotEmpty() && !isThinkingLeak && !hasOutputTranscriptionText) {
                                listener.onAssistantTextReceived(text)
                            }

                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.length > 8) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    if (pcmBytes.size >= 16) {
                                        val validLen = pcmBytes.size and 1.inv()
                                        val aligned = if (validLen == pcmBytes.size) pcmBytes else pcmBytes.copyOf(validLen)
                                        listener.onAudioDataReceived(aligned)
                                    }
                                }
                            }
                        }
                    }
                }

                val isTurnDone = serverContent.optBoolean("turnComplete", false) ||
                                 serverContent.optBoolean("generationComplete", false) ||
                                 serverContent.optBoolean("endOfTurn", false)
                if (isTurnDone) {
                    Log.d(TAG, "Server signaled turnComplete/generationComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }

    fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        try {
            val responseEntry = JSONObject().apply {
                put("id", callId)
                put("name", functionName)
                put("response", result)
            }
            val responsesArray = JSONArray().put(responseEntry)
            val msg = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", responsesArray)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent toolResponse for $functionName (callId=$callId): $result")
        } catch (e: Exception) {
            Log.e(TAG, "sendToolResponse failed: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Audio\"}}}"
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    private var mediaChunksSent = 0
    private val lastVideoFrameSentAt = AtomicLong(0L)

    /**
     * Streams real-time screen frames to Gemini Live (BidiGenerateContent).
     */
    fun sendMediaChunk(jpegBytes: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || jpegBytes.isEmpty()) return
        // Gemini Live accepts video as a continuous realtimeInput stream at up to 1 FPS.
        // Enforce the cap here so periodic and post-action frames share one rate limiter.
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastVideoFrameSentAt.get()
            if (now - previous < 1000L) return
            if (lastVideoFrameSentAt.compareAndSet(previous, now)) break
        }
        try {
            val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$base64Image\"}}}"
            val sent = webSocket?.send(json) ?: false
            mediaChunksSent++
            if (mediaChunksSent % 10 == 1) {
                Log.i(TAG, "Sent video frame #$mediaChunksSent to Gemini Live (sent=$sent, size=${jpegBytes.size / 1024} KB)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media chunk: ${e.message}")
        }
    }

    /**
     * In Gemini Live BidiGenerateContent, streaming audio via realtimeInput handles turn-taking
     * natively via server-side neural VAD. Sending empty client_content cancels active generation.
     */
    fun sendAudioStreamEnd() {
        // No-op: server-side VAD handles turn completion smoothly
    }

    /**
     * Sends a text prompt to Gemini Live using the client_content turn protocol.
     */
    fun sendClientContent(text: String) {
        if (!isConnected.get() || !isSetupComplete.get() || text.isBlank()) return
        try {
            val msg = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("turn_complete", true)
                })
            }
            webSocket?.send(msg.toString())
            Log.d(TAG, "Sent client_content: $text")
        } catch (e: Exception) {
            Log.e(TAG, "sendClientContent failed: ${e.message}", e)
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                webSocket?.close(1000, "Session renewal")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(500)
            if (!isManuallyStopped.get()) {
                Log.d(TAG, "Attempting auto-reconnect...")
                initiateConnection()
            }
        }
    }

    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
