package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat

class JarvisService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var bridge: AndroidBridge
    private val CHANNEL_ID = "JarvisVIPPersistent"
    private val handler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var wakeWord = "jarvis"
    private var whatsappAgent: JarvisWhatsAppAgent? = null
    private var automation: JarvisAutomation? = null
    private var commandEngine: JarvisCommandEngine? = null
    private var contextEngine: JarvisContextEngine? = null
    private var contactManager: JarvisContactManager? = null
    private var screenAnalyzer: JarvisScreenAnalyzer? = null
    private var aiProcessor: JarvisAIProcessor? = null
    private var memory: JarvisKnowledgeBase? = null
    private var lastScreenSummary: String = ""
    private var micRestartCount = 0

    override fun onCreate() {
        super.onCreate()
        bridge = AndroidBridge(this)
        whatsappAgent = JarvisWhatsAppAgent(this, bridge)
        automation = JarvisAutomation(bridge)
        commandEngine = JarvisCommandEngine(bridge)
        contextEngine = JarvisContextEngine()
        memory = JarvisKnowledgeBase(this)
        contactManager = JarvisContactManager(this, memory!!)
        screenAnalyzer = JarvisScreenAnalyzer()
        aiProcessor = JarvisAIProcessor(bridge)
        // Load saved AI config
        val savedKey = memory!!.recall("ai_api_key")
        if (!savedKey.isNullOrBlank()) {
            val savedUrl = memory!!.recall("ai_api_url") ?: ""
            val savedModel = memory!!.recall("ai_model") ?: ""
            aiProcessor!!.setConfig(savedKey, savedUrl, savedModel)
        }
        createNotificationChannel()
        startForeground(1, createNotification())
        initSpeechRecognizer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Jarvis Background Core",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS VIP ACTIVE")
            .setContentText("Ready! Boliyega 'Jarvis'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }


    private fun initSpeechRecognizer() {
        Log.d("JarvisService", "initSpeechRecognizer")
        handler.post {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer?.stopListening()
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        micRestartCount = 0
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                        startListening()
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        micRestartCount++
                        Log.d("JarvisService", "Mic error: $error count=$micRestartCount")
                        val delay = if (micRestartCount > 20) 2000L else 200L
                        handler.postDelayed({ if (micRestartCount > 20) initSpeechRecognizer() else startListening() }, delay)
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        micRestartCount = 0
                        val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = data?.get(0)?.lowercase() ?: ""
                        Log.d("JarvisService", "Heard: $text")
                        if (text.contains("jarvis") || text.contains("jervis") || text.contains("jarves") || text.contains("ervis")) {
                            handleCommand(text)
                        } else {
                            startListening()
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening()
            } catch (e: Exception) {
                Log.e("JarvisService", "Init error: ${e.message}")
                handler.postDelayed({ initSpeechRecognizer() }, 3000)
            }
        }
    }

    private fun startListening() {
        if (isListening) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            handler.postDelayed({ initSpeechRecognizer() }, 1000)
        }
    }

    private fun handleCommand(text: String) {
        val cmd = text.substringAfter(wakeWord).substringAfter("jervis").substringAfter("jarves").trim()
        if (cmd.isEmpty()) {
            bridge.speak("Ji Sir?") { startListening() }
            return
        }

        Log.d("JarvisService", "Processing command: $cmd")

        // Check for pending action response first (multi-step flow)
        if (pendingAction != null) {
            if (handlePendingResponse(cmd)) return
        }

        // Update screen context
        updateScreenContext()

        // Step 0: Try smart sentence parser for multi-part commands
        val parsed = JarvisSentenceParser.parse(cmd)
        if (parsed.confidence >= 0.3f && handleParsedCommand(parsed)) return

        var handled = false

        // Step 1: Process social media commands (TikTok, Instagram, YouTube)
        if (!handled) handled = processSocialMediaCommands(cmd)

        // Step 2: Process emoji commands
        if (!handled) handled = processEmojiCommands(cmd)

        // Step 3: Try to match using command engine
        if (!handled) {
            val match = commandEngine?.matchCommand(cmd)
            if (match != null) {
                Log.d("JarvisService", "Command matched: ${match.action} with params ${match.params}")
                handled = executeCommand(match.action, match.params, cmd)
            }
        }

        // Step 4: Try WhatsApp direct patterns (more flexible)
        if (!handled) handled = processWhatsAppCommands(cmd)

        // Step 5: Try contact management commands
        if (!handled) handled = processContactCommands(cmd)

        // Step 6: Try screen interaction commands
        if (!handled) handled = processScreenCommands(cmd)

        // Step 7: Try smart context-aware commands
        if (!handled) handled = processContextCommands(cmd)

        // Step 8: Use AI to understand the command (if API key available)
        if (!handled) {
            if (aiProcessor?.hasApiKey() == true) {
                processWithAI(cmd)
            } else {
                bridge.speak("Ji Sir! Main samajh gaya.") {
                    bridge.askAI(cmd)
                    handler.postDelayed({ startListening() }, 5000)
                }
            }
        }

        // Always restart listening after command processing (fallback if no handler started it)
        handler.postDelayed({ if (!isListening) startListening() }, 8000)
    }

    // Pending action for multi-step flows
    private var pendingAction: PendingAction? = null

    data class PendingAction(
        val type: String,
        val contact: String = "",
        val app: String = "",
        val originalCommand: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun setPendingAction(type: String, contact: String = "", app: String = "", originalCmd: String = "") {
        pendingAction = PendingAction(type, contact, app, originalCmd, System.currentTimeMillis())
        // Auto-clear after 60 seconds
        handler.postDelayed({
            if (pendingAction != null && System.currentTimeMillis() - (pendingAction?.timestamp ?: 0) > 60000) {
                pendingAction = null
            }
        }, 60000)
    }

    private fun handlePendingResponse(cmd: String): Boolean {
        val action = pendingAction ?: return false
        val lower = cmd.lowercase().trim()

        when (action.type) {
            "WAIT_MESSAGE" -> {
                // User is providing the message text now
                // Check if user is just saying "type karo" or "send karo" without actual text
                val msgText = cmd.replace(Regex("(type|type karo|send|send karo|bhej|bhejo|likh|likho|bol|bolo|kar do|kar|do)", RegexOption.IGNORE_CASE), "").trim()
                if (msgText.isNotEmpty() && msgText.length > 2) {
                    sendWhatsAppMessage(action.contact, msgText)
                } else {
                    bridge.speak("Sir, kya message bhejna hai? Batao.") { startListening() }
                }
                pendingAction = null
                return true
            }
            "WAIT_NAME" -> {
                // User is providing the contact name now
                val name = cmd.replace(Regex("(name|contact|naam|person|banda|shakhs)", RegexOption.IGNORE_CASE), "").trim()
                if (name.isNotEmpty() && name.length > 1) {
                    bridge.speak("Sir, $name ko kya message bhejna hai?") {
                        setPendingAction("WAIT_MESSAGE", name)
                        startListening()
                    }
                } else {
                    bridge.speak("Sir, naam batao kis ko message bhejna hai?") { startListening() }
                }
                pendingAction = null
                return true
            }
        }
        return false
    }

    fun promptForMessage(contact: String) {
        bridge.speak("Sir, $contact ko kya message bhejna hai? Batao.") {
            setPendingAction("WAIT_MESSAGE", contact)
            startListening()
        }
    }

    fun getBridge(): AndroidBridge = bridge
    fun restartListening() { startListening() }

    fun handleParsedCommand(p: ParsedCommand): Boolean {
        if (p.confidence < 0.3f) return false
        val forceAutonomous = p.rawText.lowercase().contains("ja kar") || p.rawText.lowercase().contains("jao") || p.rawText.lowercase().contains("go to")
        
        return when {
            p.targetApp == "whatsapp" && p.primaryAction == "MESSAGE" && p.targetContact.isNotEmpty() -> {
                if (p.messageText.isNotEmpty()) sendWhatsAppMessage(p.targetContact, p.messageText, forceAutonomous)
                else promptForMessage(p.targetContact)
                true
            }
            p.targetApp == "whatsapp" && p.primaryAction == "SEARCH" && p.targetContact.isNotEmpty() -> {
                searchWhatsAppContact(p.targetContact); true
            }
            p.targetApp == "whatsapp" && p.primaryAction == "CALL" && p.targetContact.isNotEmpty() -> {
                makeWhatsAppCall(p.targetContact); true
            }
            p.targetApp == "whatsapp" && p.primaryAction == "VIDEO_CALL" && p.targetContact.isNotEmpty() -> {
                makeWhatsAppVideoCall(p.targetContact); true
            }
            p.targetApp == "whatsapp" && p.primaryAction == "OPEN" -> {
                bridge.speak("Ji Sir! WhatsApp khol raha hoon.") {
                    bridge.openApp("whatsapp")
                    handler.postDelayed({ startListening() }, 3000)
                }; true
            }
            p.primaryAction == "MESSAGE" && p.targetContact.isNotEmpty() -> {
                if (p.messageText.isNotEmpty()) sendWhatsAppMessage(p.targetContact, p.messageText)
                else promptForMessage(p.targetContact)
                true
            }
            p.primaryAction == "SEARCH" && p.searchQuery.isNotEmpty() -> {
                bridge.speak("Ji Sir! ${p.searchQuery} search kar raha hoon.") {
                    bridge.webSearch(p.searchQuery)
                    handler.postDelayed({ startListening() }, 5000)
                }; true
            }
            else -> false
        }
    }

    private fun updateScreenContext() {
        try {
            val service = JarvisAccessibilityService.instance
            if (service != null) {
                val root = service.rootInActiveWindow
                if (root != null) {
                    val analysis = screenAnalyzer?.analyzeScreen(root)
                    lastScreenSummary = screenAnalyzer?.getScreenSummary() ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e("JarvisService", "Screen analysis error: ${e.message}")
        }
    }

    private fun processWithAI(cmd: String) {
        bridge.speak("Ji Sir! Main samajh raha hoon...") {
            aiProcessor?.processCommand(cmd, lastScreenSummary) { response ->
                val (action, params, explanation) = aiProcessor?.parseAIResponse(response)
                    ?: Triple("AI_CHAT", mapOf("query" to cmd), "Processing...")

                Log.d("AIProcessor", "Action: $action, Params: $params")

                // Try to execute the AI-recommended action
                val executed = executeAICommand(action, params, cmd)

                if (!executed) {
                    // Fallback to AI chat
                    bridge.speak(explanation) {
                        bridge.askAI(cmd)
                        handler.postDelayed({ startListening() }, 5000)
                    }
                }
            }
        }
    }

    private fun executeAICommand(action: String, params: Map<String, String>, originalCmd: String): Boolean {
        return when (action) {
            "OPEN_APP" -> {
                val app = params["app"] ?: ""
                if (app.isNotEmpty()) {
                    bridge.speak("Ji Sir! $app khol raha hoon.") {
                        bridge.openApp(app)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                    true
                } else false
            }
            "WHATSAPP_MSG" -> {
                val contact = params["contact"] ?: ""
                val message = params["message"] ?: ""
                val force = originalCmd.lowercase().contains("ja kar") || originalCmd.lowercase().contains("go to")
                sendWhatsAppMessage(contact, message, force)
                true
            }
            "WHATSAPP_CALL" -> {
                makeWhatsAppCall(params["contact"] ?: "")
                true
            }
            "WHATSAPP_VIDEO_CALL" -> {
                makeWhatsAppVideoCall(params["contact"] ?: "")
                true
            }
            "WHATSAPP_SEARCH" -> {
                searchWhatsAppContact(params["contact"] ?: "")
                true
            }
            "CALL_CONTACT" -> {
                makePhoneCall(params["contact"] ?: "")
                true
            }
            "SMS_CONTACT" -> {
                sendSMS(params["contact"] ?: "", params["message"] ?: "")
                true
            }
            "SAVE_CONTACT" -> {
                val name = params["name"] ?: ""
                val number = params["number"] ?: ""
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    bridge.saveContact(name, number)
                    bridge.speak("Sir, $name ka number $number save kar diya.") { startListening() }
                }
                true
            }
            "FIND_CONTACT" -> {
                findContactNumber(params["name"] ?: "")
                true
            }
            "CLICK_TEXT" -> {
                val text = params["text"] ?: ""
                if (text.isNotEmpty()) {
                    JarvisAccessibilityService.instance?.findAndClickText(text)
                    bridge.speak("Ji Sir! $text dabaya.") { startListening() }
                }
                true
            }
            "TYPE_TEXT" -> {
                val text = params["text"] ?: ""
                if (text.isNotEmpty()) {
                    JarvisAccessibilityService.instance?.findAndTypeTextFuzzy(text)
                    bridge.speak("Ji Sir! Type kar diya.") { startListening() }
                }
                true
            }
            "SCROLL_DOWN" -> { JarvisAccessibilityService.instance?.performScrollDown(); bridge.speak("Ji Sir!") { startListening() }; true }
            "SCROLL_UP" -> { JarvisAccessibilityService.instance?.performScrollUp(); bridge.speak("Ji Sir!") { startListening() }; true }
            "SWIPE_LEFT" -> { JarvisAccessibilityService.instance?.performSwipeLeft(); bridge.speak("Ji Sir!") { startListening() }; true }
            "SWIPE_RIGHT" -> { JarvisAccessibilityService.instance?.performSwipeRight(); bridge.speak("Ji Sir!") { startListening() }; true }
            "GO_BACK" -> { JarvisAccessibilityService.instance?.performBack(); bridge.speak("Ji Sir!") { startListening() }; true }
            "GO_HOME" -> { JarvisAccessibilityService.instance?.performHome(); bridge.speak("Ji Sir!") { startListening() }; true }
            "RECENTS" -> { JarvisAccessibilityService.instance?.performRecents(); bridge.speak("Ji Sir!") { startListening() }; true }
            "LIKE" -> { performSocialAction("like"); true }
            "COMMENT" -> {
                val text = params["text"] ?: "Nice!"
                performSocialComment(text)
                true
            }
            "SAVE_CONTENT" -> { performSocialAction("save"); true }
            "SHARE" -> { performSocialAction("share"); true }
            "EMOJI" -> {
                val emoji = params["emoji"] ?: ""
                sendEmoji(emoji)
                true
            }
            "SEARCH" -> {
                val query = params["query"] ?: ""
                performAppSearch(query)
                true
            }
            "CAPTURE_SCREEN" -> {
                JarvisAccessibilityService.instance?.performTakeScreenshot()
                bridge.speak("Ji Sir! Screenshot le liya.") { startListening() }
                true
            }
            "READ_SCREEN" -> {
                val content = bridge.getScreenContent()
                bridge.speak("Sir, screen par yeh hai: ${content.take(500)}") { startListening() }
                true
            }
            "SET_VOLUME" -> {
                val level = params["level"]?.toIntOrNull() ?: 50
                bridge.setVolume("media", level.coerceIn(0, 100))
                bridge.speak("Ji Sir! Volume $level set kar diya.") { startListening() }
                true
            }
            "TOGGLE_WIFI" -> {
                val state = params["state"] ?: ""
                when (state) {
                    "on" -> bridge.setWifiEnabled(true)
                    "off" -> bridge.setWifiEnabled(false)
                    else -> bridge.toggleWifi()
                }
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "TOGGLE_BLUETOOTH" -> {
                val state = params["state"] ?: ""
                when (state) {
                    "on" -> bridge.setBluetoothEnabled(true)
                    "off" -> bridge.setBluetoothEnabled(false)
                    else -> bridge.setBluetoothEnabled(!bridge.isBluetoothEnabled())
                }
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "TOGGLE_FLASHLIGHT" -> {
                val state = params["state"] ?: ""
                bridge.toggleFlashlight(state != "off")
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "SET_BRIGHTNESS" -> {
                val level = params["level"]?.toIntOrNull() ?: 128
                bridge.setBrightness(level.coerceIn(0, 255))
                bridge.speak("Ji Sir! Brightness set kar diya.") { startListening() }
                true
            }
            "SET_ALARM" -> {
                val hour = params["hour"]?.toIntOrNull() ?: 7
                val minute = params["minute"]?.toIntOrNull() ?: 0
                bridge.setAlarm(hour, minute, "Jarvis Alarm")
                bridge.speak("Ji Sir! $hour:$minute ka alarm set kar diya.") { startListening() }
                true
            }
            "SET_TIMER" -> {
                val seconds = params["seconds"]?.toIntOrNull() ?: 60
                bridge.setTimer(seconds, "Timer")
                bridge.speak("Ji Sir! $seconds second ka timer set kar diya.") { startListening() }
                true
            }
            "PLAY_MUSIC" -> {
                val action = params["action"] ?: "play"
                bridge.playMedia(action)
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "OPEN_YOUTUBE_SEARCH" -> {
                val query = params["query"] ?: ""
                if (query.isNotEmpty()) {
                    bridge.speak("Ji Sir! YouTube par $query search kar raha hoon.") {
                        bridge.youtubeSearch(query)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                }
                true
            }
            "WEB_SEARCH" -> {
                val query = params["query"] ?: ""
                if (query.isNotEmpty()) {
                    bridge.speak("Ji Sir! $query search kar raha hoon.") {
                        bridge.webSearch(query)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                }
                true
            }
            "CHECK_WEATHER" -> {
                bridge.speak("Sir, weather check kar raha hoon.") {
                    bridge.searchAndRead("aaj ka mausam temperature aur barish")
                    handler.postDelayed({ startListening() }, 8000)
                }
                true
            }
            "CHECK_NEWS" -> {
                bridge.speak("Sir, news check kar raha hoon.") {
                    bridge.searchAndRead("aaj ki top news")
                    handler.postDelayed({ startListening() }, 8000)
                }
                true
            }
            "CHECK_TIME" -> { bridge.speak("Sir, ${bridge.getCurrentTime()} hai.") { startListening() }; true }
            "CHECK_DATE" -> { bridge.speak("Sir, aaj ${bridge.getCurrentDate()} hai.") { startListening() }; true }
            "CHECK_BATTERY" -> { bridge.speak("Sir, battery ${bridge.getBatteryLevel()} hai.") { startListening() }; true }
            "TELL_JOKE" -> { bridge.speak(bridge.generateJoke()) { startListening() }; true }
            "MOTIVATE" -> { bridge.speak(bridge.generateMotivation()) { startListening() }; true }
            "TELL_QUOTE" -> { bridge.speak(bridge.generateQuote()) { startListening() }; true }
            "SOS_EMERGENCY" -> {
                bridge.speak("Sir! Emergency SOS bhej raha hoon.") {
                    val emergencyNum = memory?.getContact("emergency") ?: "911"
                    bridge.sendSMS(emergencyNum, "EMERGENCY! Sir ko help chahiye! - JARVIS")
                    handler.postDelayed({ startListening() }, 5000)
                }
                true
            }
            "AI_CHAT" -> {
                val query = params["query"] ?: originalCmd
                bridge.speak("Ji Sir!") {
                    bridge.askAI(query)
                    handler.postDelayed({ startListening() }, 5000)
                }
                true
            }
            else -> false
        }
    }

    private fun executeCommand(action: String, params: MutableMap<String, String>, originalCmd: String): Boolean {
        Log.d("JarvisService", "Executing: $action with $params")

        return when (action) {
            // ===== APP CONTROL =====
            "OPEN_APP" -> {
                val app = params["app"] ?: ""
                if (app.isNotEmpty()) {
                    bridge.speak("Ji Sir! $app khol raha hoon.") {
                        bridge.openApp(app)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                } else {
                    // Try to extract from original cmd
                    val extractedApp = originalCmd.replace(Regex("^(open|kholo|khol|chalao|launch|start|\\s)"), "").trim()
                    if (extractedApp.isNotEmpty()) {
                        bridge.speak("Ji Sir! $extractedApp khol raha hoon.") {
                            bridge.openApp(extractedApp)
                            handler.postDelayed({ startListening() }, 3000)
                        }
                    } else {
                        bridge.speak("Sir, kaun si app kholni hai?") { startListening() }
                    }
                }
                true
            }
            "GO_HOME" -> {
                JarvisAccessibilityService.instance?.performHome()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }

            // ===== WIFI =====
            "WIFI_ON" -> { bridge.setWifiEnabled(true); bridge.speak("Ji Sir! WiFi on kar diya.") { startListening() }; true }
            "WIFI_OFF" -> { bridge.setWifiEnabled(false); bridge.speak("Ji Sir! WiFi off kar diya.") { startListening() }; true }
            "WIFI_TOGGLE" -> { bridge.toggleWifi(); bridge.speak("Ji Sir! WiFi toggle kar diya.") { startListening() }; true }

            // ===== BLUETOOTH =====
            "BLUETOOTH_ON" -> { bridge.setBluetoothEnabled(true); bridge.speak("Ji Sir! Bluetooth on kar diya.") { startListening() }; true }
            "BLUETOOTH_OFF" -> { bridge.setBluetoothEnabled(false); bridge.speak("Ji Sir! Bluetooth off kar diya.") { startListening() }; true }

            // ===== FLASHLIGHT =====
            "FLASHLIGHT_ON" -> { bridge.toggleFlashlight(true); bridge.speak("Ji Sir! Flashlight on.") { startListening() }; true }
            "FLASHLIGHT_OFF" -> { bridge.toggleFlashlight(false); bridge.speak("Ji Sir! Flashlight off.") { startListening() }; true }
            "FLASHLIGHT_TOGGLE" -> {
                val current = false // simplified
                bridge.toggleFlashlight(!current)
                bridge.speak("Ji Sir!") { startListening() }
                true
            }

            // ===== VOLUME =====
            "VOLUME_UP" -> { bridge.volumeUp(); bridge.speak("Ji Sir!") { startListening() }; true }
            "VOLUME_DOWN" -> { bridge.volumeDown(); bridge.speak("Ji Sir!") { startListening() }; true }
            "VOLUME_SET" -> {
                val level = params["level"]?.toIntOrNull() ?: 50
                bridge.setVolume("media", level)
                bridge.speak("Ji Sir! Volume $level set kar diya.") { startListening() }
                true
            }
            "MUTE" -> { bridge.mute(); bridge.speak("Ji Sir! Mute kar diya.") { startListening() }; true }
            "UNMUTE" -> { bridge.unmute(); bridge.speak("Ji Sir! Unmute kar diya.") { startListening() }; true }

            // ===== RINGER =====
            "RINGER_SILENT" -> { bridge.setRingerMode("silent"); bridge.speak("Ji Sir! Silent mode.") { startListening() }; true }
            "RINGER_VIBRATE" -> { bridge.setRingerMode("vibrate"); bridge.speak("Ji Sir! Vibrate mode.") { startListening() }; true }
            "RINGER_NORMAL" -> { bridge.setRingerMode("normal"); bridge.speak("Ji Sir! Normal mode.") { startListening() }; true }

            // ===== BRIGHTNESS =====
            "BRIGHTNESS_UP" -> {
                val current = bridge.getBrightness()
                bridge.setBrightness((current + 30).coerceAtMost(255))
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "BRIGHTNESS_DOWN" -> {
                val current = bridge.getBrightness()
                bridge.setBrightness((current - 30).coerceAtLeast(0))
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "BRIGHTNESS_SET" -> {
                val level = params["level"]?.toIntOrNull() ?: 128
                bridge.setBrightness(level.coerceIn(0, 255))
                bridge.speak("Ji Sir! Brightness set kar diya.") { startListening() }
                true
            }

            // ===== AIRPLANE =====
            "AIRPLANE_ON" -> { bridge.setAirplaneMode(true); bridge.speak("Ji Sir! Airplane mode on.") { startListening() }; true }
            "AIRPLANE_OFF" -> { bridge.setAirplaneMode(false); bridge.speak("Ji Sir! Airplane mode off.") { startListening() }; true }

            // ===== DND =====
            "DND_ON" -> { bridge.startDndMode(); handler.postDelayed({ startListening() }, 2000); true }
            "DND_OFF" -> { bridge.stopDndMode(); handler.postDelayed({ startListening() }, 2000); true }

            // ===== GESTURES =====
            "SCROLL_DOWN" -> {
                JarvisAccessibilityService.instance?.performScrollDown()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "SCROLL_UP" -> {
                JarvisAccessibilityService.instance?.performScrollUp()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "SWIPE_LEFT" -> {
                JarvisAccessibilityService.instance?.performSwipeLeft()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "SWIPE_RIGHT" -> {
                JarvisAccessibilityService.instance?.performSwipeRight()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "BACK" -> {
                JarvisAccessibilityService.instance?.performBack()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "HOME" -> {
                JarvisAccessibilityService.instance?.performHome()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "RECENTS" -> {
                JarvisAccessibilityService.instance?.performRecents()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "NOTIFICATIONS" -> {
                JarvisAccessibilityService.instance?.performNotifications()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "QUICK_SETTINGS" -> {
                JarvisAccessibilityService.instance?.performQuickSettings()
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "LOCK_SCREEN" -> {
                bridge.lockScreen()
                handler.postDelayed({ startListening() }, 2000)
                true
            }

            // ===== SCREEN =====
            "SCREENSHOT" -> {
                bridge.speak("Ji Sir! Screenshot le raha hoon.") {
                    JarvisAccessibilityService.instance?.performTakeScreenshot()
                    handler.postDelayed({ startListening() }, 2000)
                }
                true
            }
            "SCREEN_READ" -> {
                val content = bridge.getScreenContent()
                if (content.isNotEmpty()) {
                    bridge.speak("Sir, screen par yeh likha hai: $content") { startListening() }
                } else {
                    bridge.speak("Sir, screen par kuch nahi mila.") { startListening() }
                }
                true
            }

            // ===== WHATSAPP =====
            "WHATSAPP_OPEN" -> {
                bridge.speak("Ji Sir! WhatsApp khol raha hoon.") {
                    bridge.openApp("WhatsApp")
                    handler.postDelayed({ startListening() }, 3000)
                }
                true
            }
            "WHATSAPP_SEARCH" -> {
                val contact = params["contact"] ?: ""
                searchWhatsAppContact(contact)
                true
            }
            "WHATSAPP_MSG" -> {
                val contactName = params["contact"] ?: ""
                val message = params["message"] ?: ""
                sendWhatsAppMessage(contactName, message)
                true
            }
            "WHATSAPP_CALL" -> {
                val contact = params["contact"] ?: ""
                makeWhatsAppCall(contact)
                true
            }
            "WHATSAPP_VIDEO_CALL" -> {
                val contact = params["contact"] ?: ""
                makeWhatsAppVideoCall(contact)
                true
            }

            // ===== CONTACTS =====
            "CONTACTS_SHOW" -> {
                bridge.speak("Sir, contacts dikha raha hoon. WebView mein dekhiye.") { startListening() }
                true
            }
            "CONTACT_SAVE" -> {
                val name = params["name"] ?: ""
                val number = params["number"] ?: ""
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    bridge.saveContact(name, number)
                    bridge.speak("Sir, $name ka number $number save kar diya.") { startListening() }
                } else {
                    bridge.speak("Sir, kaun sa number save karna hai?") { startListening() }
                }
                true
            }
            "CONTACT_DELETE" -> {
                val name = params["name"] ?: ""
                val result = contactManager?.deleteContactViaVoice(originalCmd) ?: "Sir, contact delete nahi ho paya."
                bridge.speak(result) { startListening() }
                true
            }
            "CONTACT_FIND" -> {
                val name = params["name"] ?: ""
                findContactNumber(name)
                true
            }

            // ===== CALL =====
            "CALL" -> {
                val contact = params["contact"] ?: ""
                makePhoneCall(contact)
                true
            }

            // ===== SMS =====
            "SMS" -> {
                val contact = params["contact"] ?: ""
                val message = params["message"] ?: ""
                sendSMS(contact, message)
                true
            }

            // ===== ALARM =====
            "ALARM_SET" -> {
                val time = params["time"] ?: params["hour"] ?: "7"
                val minute = params["minute"]?.toIntOrNull() ?: 0
                val hour = params["hour"]?.toIntOrNull() ?: time.toIntOrNull() ?: 7
                bridge.setAlarm(hour, minute, "Jarvis Alarm")
                bridge.speak("Ji Sir! $hour:$minute ka alarm set kar diya.") { startListening() }
                true
            }

            // ===== TIMER =====
            "TIMER_SET" -> {
                val seconds = params["seconds"]?.toIntOrNull() ?: params["minutes"]?.toIntOrNull()?.times(60) ?: 60
                bridge.setTimer(seconds, "Timer")
                bridge.speak("Ji Sir! $seconds second ka timer set kar diya.") { startListening() }
                true
            }

            // ===== CAMERA =====
            "CAMERA_OPEN" -> { bridge.openCamera(); bridge.speak("Ji Sir! Camera khol raha hoon.") { startListening() }; true }
            "CAMERA_VIDEO" -> { bridge.openVideoCamera(); bridge.speak("Ji Sir! Video camera khol raha hoon.") { startListening() }; true }
            "PHOTO_CLICK" -> { bridge.openCamera(); bridge.speak("Ji Sir! Camera khol raha hoon. Photo le lijiye.") { startListening() }; true }

            // ===== MEDIA =====
            "MUSIC_PLAY" -> { bridge.playMedia("play"); bridge.speak("Ji Sir! Music play kar raha hoon.") { startListening() }; true }
            "MUSIC_PAUSE" -> { bridge.playMedia("pause"); bridge.speak("Ji Sir!") { startListening() }; true }
            "MUSIC_NEXT" -> { bridge.playMedia("next"); bridge.speak("Ji Sir!") { startListening() }; true }
            "MUSIC_PREVIOUS" -> { bridge.playMedia("previous"); bridge.speak("Ji Sir!") { startListening() }; true }

            // ===== YOUTUBE =====
            "YOUTUBE_OPEN" -> {
                bridge.speak("Ji Sir! YouTube khol raha hoon.") {
                    bridge.openApp("YouTube")
                    handler.postDelayed({ startListening() }, 3000)
                }
                true
            }
            "YOUTUBE_SEARCH" -> {
                val query = params["query"] ?: ""
                if (query.isNotEmpty()) {
                    bridge.speak("Ji Sir! YouTube par $query search kar raha hoon.") {
                        bridge.youtubeSearch(query)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                } else {
                    bridge.speak("Sir, kya search karna hai?") { startListening() }
                }
                true
            }

            // ===== WEB SEARCH =====
            "WEB_SEARCH" -> {
                val query = params["query"] ?: ""
                if (query.isNotEmpty()) {
                    bridge.speak("Ji Sir! $query search kar raha hoon.") {
                        bridge.webSearch(query)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                } else {
                    bridge.speak("Sir, kya search karna hai?") { startListening() }
                }
                true
            }

            // ===== WEATHER =====
            "WEATHER" -> {
                bridge.speak("Sir, weather check kar raha hoon.") {
                    bridge.searchAndRead("aaj ka mausam temperature aur barish")
                    handler.postDelayed({ startListening() }, 8000)
                }
                true
            }

            // ===== NEWS =====
            "NEWS" -> {
                bridge.speak("Sir, news check kar raha hoon.") {
                    bridge.searchAndRead("aaj ki top news")
                    handler.postDelayed({ startListening() }, 8000)
                }
                true
            }

            // ===== TIME/DATE =====
            "TIME" -> { bridge.speak("Sir, ${bridge.getCurrentTime()} hai.") { startListening() }; true }
            "DATE" -> { bridge.speak("Sir, aaj ${bridge.getCurrentDate()} hai.") { startListening() }; true }

            // ===== BATTERY =====
            "BATTERY" -> { bridge.speak("Sir, battery ${bridge.getBatteryLevel()} hai.") { startListening() }; true }

            // ===== NAVIGATION =====
            "MAPS_OPEN" -> { bridge.openMaps(""); bridge.speak("Ji Sir! Maps khol raha hoon.") { startListening() }; true }
            "NAVIGATE" -> {
                val location = params["location"] ?: ""
                if (location.isNotEmpty()) {
                    bridge.openMaps(location)
                    bridge.speak("Ji Sir! $location ka map khol raha hoon.") { startListening() }
                }
                true
            }

            // ===== SETTINGS =====
            "SETTINGS_OPEN" -> { bridge.openSettings(); bridge.speak("Ji Sir! Settings khol raha hoon.") { startListening() }; true }
            "WIFI_SETTINGS" -> { bridge.openWifiSettings(); bridge.speak("Ji Sir!") { startListening() }; true }
            "BLUETOOTH_SETTINGS" -> { bridge.openBluetoothSettings(); bridge.speak("Ji Sir!") { startListening() }; true }
            "SOUND_SETTINGS" -> { bridge.openSoundSettings(); bridge.speak("Ji Sir!") { startListening() }; true }
            "DISPLAY_SETTINGS" -> { bridge.openDisplaySettings(); bridge.speak("Ji Sir!") { startListening() }; true }
            "BATTERY_SETTINGS" -> { bridge.openBatterySettings(); bridge.speak("Ji Sir!") { startListening() }; true }
            "ACCESSIBILITY_SETTINGS" -> { bridge.openAccessibilitySettings(); bridge.speak("Ji Sir!") { startListening() }; true }

            // ===== ROUTINES =====
            "ROUTINE_GOOD_NIGHT" -> { automation?.goodNightRoutine(); true }
            "ROUTINE_GOOD_MORNING" -> { automation?.goodMorningRoutine(); true }
            "ROUTINE_WORK" -> { automation?.workModeRoutine(); true }
            "ROUTINE_DRIVING" -> { automation?.drivingModeRoutine(); true }
            "ROUTINE_MEETING" -> { automation?.meetingModeRoutine(); true }

            // ===== FUN =====
            "JOKE" -> { bridge.speak(bridge.generateJoke()) { startListening() }; true }
            "MOTIVATE" -> { bridge.speak(bridge.generateMotivation()) { startListening() }; true }
            "QUOTE" -> { bridge.speak(bridge.generateQuote()) { startListening() }; true }
            "HELLO" -> { bridge.speak("Hello Sir! Kaise hain aap?") { startListening() }; true }
            "BYE" -> { bridge.speak("Goodbye Sir! Jab bolo tab hazir hoon.") { startListening() }; true }
            "THANKS" -> { bridge.speak("Welcome Sir!") { startListening() }; true }
            "WHO_ARE_YOU" -> { bridge.speak("Main JARVIS hoon Sir, aapka personal AI assistant!") { startListening() }; true }
            "WHAT_YOU_DO" -> {
                bridge.speak("Sir, main sab kuch kar sakta hoon! Apps kholna, WhatsApp message, call, WiFi, Bluetooth, torch, volume, brightness, alarm, timer, search, aur bhi bahut kuch.") { startListening() }
                true
            }
            "HOW_ARE_YOU" -> { bridge.speak("Main bilkul theek hoon Sir! Aap batao?") { startListening() }; true }

            // ===== CLIPBOARD =====
            "CLIPBOARD_COPY" -> {
                val text = bridge.getScreenText()
                if (text.isNotEmpty()) {
                    bridge.copyToClipboard(text)
                    bridge.speak("Sir, screen ka text copy kar diya.") { startListening() }
                } else {
                    bridge.speak("Sir, screen par koi text nahi mila.") { startListening() }
                }
                true
            }
            "CLIPBOARD_PASTE" -> {
                val text = bridge.getClipboardText()
                if (text.isNotEmpty()) {
                    JarvisAccessibilityService.instance?.findAndTypeTextFuzzy(text)
                    bridge.speak("Ji Sir! Paste kar diya.") { startListening() }
                } else {
                    bridge.speak("Sir, clipboard mein kuch nahi hai.") { startListening() }
                }
                true
            }
            "CLIPBOARD_READ" -> {
                val text = bridge.getClipboardText()
                if (text.isNotEmpty()) {
                    bridge.speak("Sir, clipboard mein yeh hai: ${text.take(100)}") { startListening() }
                } else {
                    bridge.speak("Sir, clipboard mein kuch nahi hai.") { startListening() }
                }
                true
            }

            // ===== BUTTON CLICK =====
            "BUTTON_CLICK" -> {
                val service = JarvisAccessibilityService.instance
                if (service != null) {
                    contextEngine?.updateScreenElements(service)
                    val buttons = contextEngine?.getDetectedButtons()
                    if (buttons.isNullOrEmpty()) {
                        bridge.speak("Sir, screen par koi button nahi mila.") { startListening() }
                    } else if (buttons.size == 1) {
                        service.findAndClickText(buttons[0])
                        bridge.speak("Ji Sir! $buttons[0] dabaya.") { startListening() }
                    } else {
                        val msg = "Sir, ${buttons.size} buttons hain: ${buttons.joinToString(", ")}. Kaunsa dabau?"
                        bridge.speak(msg) { startListening() }
                    }
                } else {
                    bridge.speak("Sir, Accessibility Service enable nahi hai.") { startListening() }
                }
                true
            }

            // ===== CLICK TEXT =====
            "CLICK_TEXT" -> {
                val text = params["text"] ?: ""
                if (text.isNotEmpty()) {
                    val clicked = JarvisAccessibilityService.instance?.findAndClickText(text)
                    if (clicked == true) {
                        bridge.speak("Ji Sir! $text dabaya.") { startListening() }
                    } else {
                        // Try fuzzy
                        contextEngine?.let { engine ->
                            val service = JarvisAccessibilityService.instance
                            if (service != null) {
                                engine.updateScreenElements(service)
                                val elements = engine.getAllElements()
                                val match = elements.find { it.lowercase().contains(text.lowercase()) }
                                if (match != null) {
                                    service.findAndClickText(match)
                                    bridge.speak("Ji Sir! $match mila aur dabaya.") { startListening() }
                                } else {
                                    bridge.speak("Sir, '$text' screen par nahi mila.") { startListening() }
                                }
                            }
                        }
                    }
                }
                true
            }

            // ===== TYPE TEXT =====
            "TYPE_TEXT" -> {
                val text = params["text"] ?: ""
                if (text.isNotEmpty()) {
                    val typed = JarvisAccessibilityService.instance?.findAndTypeTextFuzzy(text)
                    if (typed == true) {
                        bridge.speak("Ji Sir! Type kar diya.") { startListening() }
                    } else {
                        bridge.speak("Sir, type karne ke liye jagah nahi mili.") { startListening() }
                    }
                }
                true
            }

            // ===== UNINSTALL =====
            "UNINSTALL_APP" -> {
                val app = params["app"] ?: ""
                if (app.isNotEmpty()) {
                    bridge.uninstallApp(app)
                    bridge.speak("Sir, $app uninstall ka prompt bhej diya.") { startListening() }
                }
                true
            }

            // ===== OPEN URL =====
            "OPEN_URL" -> {
                val url = params["url"] ?: ""
                if (url.isNotEmpty()) {
                    val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                    bridge.openUrl(finalUrl)
                    bridge.speak("Ji Sir! $url khol raha hoon.") { startListening() }
                }
                true
            }

            // ===== CALCULATOR =====
            "CALCULATOR" -> {
                val expr = params["expression"] ?: ""
                if (expr.isNotEmpty()) {
                    val result = bridge.calculate(expr)
                    bridge.speak("Sir, $expr ka jawab $result hai.") { startListening() }
                }
                true
            }

            // ===== VIDEO =====
            "VIDEO_NEXT" -> {
                bridge.playMedia("next")
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "VIDEO_PREVIOUS" -> {
                bridge.playMedia("previous")
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "VIDEO_FULLSCREEN" -> {
                JarvisAccessibilityService.instance?.findAndClickText("Full screen")
                bridge.speak("Ji Sir!") { startListening() }
                true
            }
            "VIDEO_360" -> {
                bridge.speak("Sir, video 360 mode mein ghumane ke liye screen par swipe karein.") { startListening() }
                true
            }

            // ===== EMERGENCY =====
            "EMERGENCY_SOS" -> {
                bridge.speak("Sir! Emergency mode activate! SOS message bhej raha hoon.") {
                    bridge.sendSMS(bridge.getSavedContact("emergency") ?: "911", "EMERGENCY! Sir ko help chahiye! - JARVIS")
                    handler.postDelayed({ startListening() }, 5000)
                }
                true
            }

            else -> false
        }
    }

    // ===== WHATSAPP COMMAND PROCESSING =====
    private fun processWhatsAppCommands(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()

        // Check if user specifically wants to "go to" WhatsApp
        val forceAutonomous = lower.contains("ja kar") || lower.contains("jao") || lower.contains("go to") || lower.contains("open")

        // WhatsApp message detection
        val waKeywords = listOf("msg", "message", "whatsapp", "send", "bhej", "likh", "bol")
        val hasWaKeyword = waKeywords.any { lower.contains(it) }
        val hasNameMarker = lower.contains(" ko") || lower.startsWith("ko ") || lower.contains("to ")

        if (hasWaKeyword || hasNameMarker) {
            val name = extractWhatsAppName(cmd)
            val message = extractWhatsAppMessage(cmd)
            if (name.isNotEmpty()) {
                sendWhatsAppMessage(name, message, forceAutonomous)
                return true
            }
        }

        // WhatsApp search: "whatsapp mein Ali dhoondo"
        if (lower.contains("whatsapp") && (lower.contains("search") || lower.contains("dhoond") || lower.contains("find") || lower.contains("khoj"))) {
            val name = cmd.replace(Regex("whatsapp|search|dhoond|find|khoj|mein|me"), "").trim()
            if (name.isNotEmpty()) {
                searchWhatsAppContact(name)
                return true
            }
        }

        // WhatsApp video call
        if ((lower.contains("whatsapp") || lower.contains("wa")) && lower.contains("video")) {
            val name = cmd.replace(Regex("whatsapp|wa|video|call|karo|ko|par|pe"), "").trim()
            if (name.isNotEmpty()) {
                makeWhatsAppVideoCall(name)
                return true
            }
        }

        // WhatsApp call
        if ((lower.contains("whatsapp") || lower.contains("wa")) && lower.contains("call")) {
            val name = cmd.replace(Regex("whatsapp|wa|call|karo|ko|par|pe"), "").trim()
            if (name.isNotEmpty()) {
                makeWhatsAppCall(name)
                return true
            }
        }

        return false
    }

    fun sendWhatsAppMessage(name: String, message: String, forceAutonomous: Boolean = false) {
        if (name.isEmpty()) {
            bridge.speak("Sir, kis ko message bhejna hai? Naam batao.") { startListening() }
            return
        }

        // Find contact
        val contact = contactManager?.findContact(name)
        val displayName = contact?.name ?: name
        val rawNumber = contact?.number ?: bridge.getSavedContact(name.lowercase())
        val number = rawNumber?.let { formatWhatsAppNumber(it) }

        if (number != null && !forceAutonomous) {
            if (message.isNotEmpty()) {
                bridge.speak("Ji Sir! $displayName ko WhatsApp kar raha hoon.") {
                    bridge.sendWhatsAppDirect(number, message)
                    handler.postDelayed({ startListening() }, 3000)
                }
            } else {
                bridge.speak("Sir, $displayName ko kya message bhejna hai?") { startListening() }
            }
        } else {
            if (message.isEmpty() && !forceAutonomous) {
                bridge.speak("Sir, $name ko kya message bhejna hai?") { startListening() }
                return
            }
            val prompt = if (forceAutonomous) "Ji Sir! WhatsApp par ja kar $displayName ko message bhej raha hoon." 
                         else "Ji Sir! $displayName ko WhatsApp mein dhoondh kar message bhej raha hoon."
            
            bridge.speak(prompt) {
                whatsappAgent?.executeAutonomousWhatsApp("to $name message $message",
                    object : JarvisWhatsAppAgent.AgentCallback {
                        override fun onProgress(msg: String) { Log.d("WhatsApp", msg) }
                        override fun onComplete(success: Boolean, msg: String) {
                            if (!success) {
                                if (msg.contains("not registered", true)) {
                                    bridge.speak("Sir, ye number WhatsApp par nahi hai. Kya SMS bhej doon?") { startListening() }
                                } else {
                                    bridge.speak("Sir, $msg") { startListening() }
                                }
                            }
                            else handler.postDelayed({ startListening() }, 5000)
                        }
                        override fun onRequestInput(prompt: String, type: String) {
                            bridge.speak(prompt) { startListening() }
                        }
                    })
            }
        }
    }

    private fun formatWhatsAppNumber(number: String): String {
        // Remove all non-digits
        var clean = number.replace(Regex("[^\\d]"), "")
        
        // If it starts with 0 and is 11 digits (typical Pakistan local format 03xx-xxxxxxx)
        if (clean.startsWith("0") && clean.length == 11) {
            clean = "92" + clean.substring(1)
        }
        
        // Return only the digits (WhatsApp smsto: format usually likes this)
        return clean
    }

    fun searchWhatsAppContact(name: String) {
        if (name.isEmpty()) {
            bridge.speak("Sir, kaun sa contact dhoondhna hai?") { startListening() }
            return
        }
        bridge.speak("Ji Sir! WhatsApp mein $name dhoondh raha hoon.") {
            whatsappAgent?.searchContactInWhatsAppUI(name, object : JarvisWhatsAppAgent.AgentCallback {
                override fun onProgress(msg: String) { Log.d("WhatsApp", msg) }
                override fun onComplete(success: Boolean, msg: String) {
                    if (!success) bridge.speak("Sir, $msg") { startListening() }
                    else handler.postDelayed({ startListening() }, 5000)
                }
                override fun onRequestInput(prompt: String, type: String) {
                    bridge.speak(prompt) { startListening() }
                }
            })
        }
    }

    fun makeWhatsAppCall(name: String) {
        val contact = contactManager?.findContact(name)
        val number = contact?.number?.replace(Regex("[^\\d+]"), "") ?: ""
        if (number.isNotEmpty()) {
            bridge.speak("Ji Sir! $name ko WhatsApp call kar raha hoon.") {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://wa.me/$number")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    handler.postDelayed({ startListening() }, 5000)
                } catch (e: Exception) {
                    bridge.speak("Sir, WhatsApp call feature issue hai.") { startListening() }
                }
            }
        } else {
            bridge.speak("Sir, $name ka number nahi mila.") { startListening() }
        }
    }

    fun makeWhatsAppVideoCall(name: String) {
        val contact = contactManager?.findContact(name)
        val number = contact?.number?.replace(Regex("[^\\d+]"), "") ?: ""
        if (number.isNotEmpty()) {
            bridge.speak("Ji Sir! $name ko WhatsApp video call kar raha hoon.") {
                try {
                    // WhatsApp video call intent
                    val intent = Intent("android.intent.action.VIEW").apply {
                        data = android.net.Uri.parse("https://wa.me/$number")
                        putExtra("whatsapp_video_call", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    handler.postDelayed({ startListening() }, 5000)
                } catch (e: Exception) {
                    // Fallback: open WhatsApp chat
                    bridge.speak("Sir, WhatsApp khol diya. Video call manually karein.") {
                        bridge.openWhatsAppChat(number)
                        handler.postDelayed({ startListening() }, 3000)
                    }
                }
            }
        } else {
            bridge.speak("Sir, $name ka number nahi mila.") { startListening() }
        }
    }

    fun makePhoneCall(name: String) {
        val contact = contactManager?.findContact(name)
        val number = contact?.number ?: name
        val displayName = contact?.name ?: name
        bridge.speak("Ji Sir! $displayName ko call kar raha hoon.") {
            bridge.makeCall(number)
            handler.postDelayed({ startListening() }, 3000)
        }
    }

    fun sendSMS(name: String, message: String) {
        val contact = contactManager?.findContact(name)
        val number = contact?.number ?: name
        val displayName = contact?.name ?: name
        if (message.isNotEmpty()) {
            bridge.speak("Ji Sir! $displayName ko SMS bhej raha hoon.") {
                bridge.sendSMS(number, message)
                handler.postDelayed({ startListening() }, 3000)
            }
        } else {
            bridge.speak("Sir, kya message bhejna hai?") { startListening() }
        }
    }

    fun findContactNumber(name: String) {
        if (name.isEmpty()) {
            bridge.speak("Sir, kis contact ka number dhoondhna hai?") { startListening() }
            return
        }
        val contact = contactManager?.findContact(name)
        if (contact != null) {
            bridge.speak("Sir, ${contact.name} ka number ${contact.number} hai.") { startListening() }
        } else {
            bridge.speak("Sir, $name contacts mein nahi mila.") { startListening() }
            handler.postDelayed({
                bridge.speak("Sir, kya aap $name ka number save karna chahenge?") { startListening() }
            }, 3000)
        }
    }

    // ===== CONTACT COMMANDS =====
    private fun processContactCommands(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()

        // Save contact: "Sara ka number 1234567890 save karo"
        if ((lower.contains("save") || lower.contains("rakh lo") || lower.contains("yad rakho")) &&
            (lower.contains("number") || lower.contains("numbar"))) {
            val result = contactManager?.addContactViaVoice(cmd) ?: "Sir, main samajh nahi paya."
            bridge.speak(result) { startListening() }
            return true
        }

        // Delete contact: "Sara ko delete karo"
        if (lower.contains("delete") || lower.contains("hatao") || lower.contains("remove") || lower.contains("mitao")) {
            val result = contactManager?.deleteContactViaVoice(cmd) ?: "Sir, main samajh nahi paya."
            bridge.speak(result) { startListening() }
            return true
        }

        // Find contact: "Sara ka number batao"
        if ((lower.contains("number") || lower.contains("numbar") || lower.contains("contact")) &&
            (lower.contains("batao") || lower.contains("dhoond") || lower.contains("find") || lower.contains("khoj") || lower.contains("search"))) {
            val name = cmd.replace(Regex("ka|number|numbar|contact|batao|dhoond|find|khoj|search|dhoondo"), "").trim()
            findContactNumber(name)
            return true
        }

        return false
    }

    // ===== CONTEXT COMMANDS =====
    private fun processContextCommands(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()

        // Open app (generic)
        if (lower.startsWith("open ") || lower.startsWith("kholo ") || lower.startsWith("khol ")) {
            val app = cmd.replace(Regex("^(open|kholo|khol)"), "").trim()
            if (app.isNotEmpty()) {
                bridge.speak("Ji Sir! $app khol raha hoon.") {
                    bridge.openApp(app)
                    handler.postDelayed({ startListening() }, 3000)
                }
                return true
            }
        }

        return false
    }

    // ===== SOCIAL MEDIA COMMANDS =====
    private fun processSocialMediaCommands(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()
        val currentPkg = bridge.getCurrentApp()

        // TikTok commands
        if (currentPkg?.contains("tiktok") == true) {
            // Like: "like karo", "like kar", "pasand aaya"
            if (lower.contains("like") || lower.contains("pasand") || lower.contains("love it") || lower.contains("acha laga")) {
                performSocialAction("like")
                return true
            }
            // Comment: "comment karo yeh video achi hai"
            if (lower.contains("comment") || lower.contains("reply")) {
                val text = cmd.replace(Regex("(comment|reply|karo|kar do|kar|karein|de|do)", RegexOption.IGNORE_CASE), "").trim()
                performSocialComment(text.ifEmpty { "Nice video!" })
                return true
            }
            // Save: "save karo", "download karo"
            if (lower.contains("save") || lower.contains("download") || lower.contains("rakh lo")) {
                performSocialAction("save")
                return true
            }
            // Share: "share karo", "bhej do"
            if (lower.contains("share") || lower.contains("forward") || lower.contains("bhej do") || lower.contains("baant")) {
                performSocialAction("share")
                return true
            }
            // Follow: "follow karo", "follow kar"
            if (lower.contains("follow") || lower.contains("subscribe")) {
                performSocialAction("follow")
                return true
            }
            // Search: "TikTok par xyz search karo"
            if ((lower.contains("search") || lower.contains("dhoond") || lower.contains("khoj")) && !lower.contains("contact")) {
                val query = cmd.replace(Regex("(tiktok|search|dhoond|khoj|karo|kar do|par|mein|me)", RegexOption.IGNORE_CASE), "").trim()
                if (query.isNotEmpty()) {
                    bridge.speak("Ji Sir! TikTok par $query search kar raha hoon.") {
                        bridge.tiktokSearch(query)
                        handler.postDelayed({ startListening() }, 4000)
                    }
                }
                return true
            }
            // Profile: "profile kholo", "my profile"
            if (lower.contains("profile") || lower.contains("my account") || lower.contains("mera profile")) {
                bridge.speak("Ji Sir!") { bridge.tiktokOpenProfile(); handler.postDelayed({ startListening() }, 3000) }
                return true
            }
            // Open TikTok
            if (lower.contains("tiktok khol") || lower.contains("open tiktok") || lower.contains("tiktok chalao")) {
                bridge.speak("Ji Sir! TikTok khol raha hoon.") {
                    bridge.openApp("tiktok")
                    handler.postDelayed({ startListening() }, 3000)
                }
                return true
            }
        }

        // Instagram commands
        if (currentPkg?.contains("instagram") == true) {
            if (lower.contains("like") || lower.contains("pasand") || lower.contains("love it") || lower.contains("acha laga")) {
                performSocialAction("like")
                return true
            }
            if (lower.contains("comment") || lower.contains("reply")) {
                val text = cmd.replace(Regex("(comment|reply|karo|kar do|kar|karein|de|do)", RegexOption.IGNORE_CASE), "").trim()
                performSocialComment(text.ifEmpty { "Nice post!" })
                return true
            }
            if (lower.contains("save") || lower.contains("bookmark") || lower.contains("rakh lo")) {
                performSocialAction("save")
                return true
            }
            if (lower.contains("share") || lower.contains("forward") || lower.contains("bhej do") || lower.contains("baant")) {
                performSocialAction("share")
                return true
            }
            if (lower.contains("profile") || lower.contains("my account")) {
                bridge.speak("Ji Sir!") { bridge.instagramOpenProfile(); handler.postDelayed({ startListening() }, 3000) }
                return true
            }
            if ((lower.contains("search") || lower.contains("dhoond") || lower.contains("khoj")) && !lower.contains("contact")) {
                val query = cmd.replace(Regex("(instagram|search|dhoond|khoj|karo|kar do|par|mein|me)", RegexOption.IGNORE_CASE), "").trim()
                if (query.isNotEmpty()) {
                    bridge.speak("Ji Sir! Instagram par $query search kar raha hoon.") {
                        bridge.instagramSearch(query)
                        handler.postDelayed({ startListening() }, 4000)
                    }
                }
                return true
            }
        }

        // YouTube commands
        if (currentPkg?.contains("youtube") == true) {
            if (lower.contains("subscribe") || lower.contains("follow") || lower.contains("subscribe karo")) {
                performSocialAction("subscribe")
                return true
            }
            if (lower.contains("comment") || lower.contains("reply")) {
                val text = cmd.replace(Regex("(comment|reply|karo|kar do|kar|karein|de|do)", RegexOption.IGNORE_CASE), "").trim()
                performSocialComment(text.ifEmpty { "Great video!" })
                return true
            }
            if (lower.contains("like") || lower.contains("pasand") || lower.contains("acha laga")) {
                performSocialAction("like")
                return true
            }
            if (lower.contains("save") || lower.contains("download") || lower.contains("watch later") || lower.contains("baad mein dekhen")) {
                performSocialAction("save")
                return true
            }
            if (lower.contains("share") || lower.contains("forward") || lower.contains("bhej do")) {
                performSocialAction("share")
                return true
            }
        }

        return false
    }

    private fun performSocialAction(action: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            bridge.speak("Sir, Accessibility Service enable nahi hai.") { startListening() }
            return
        }
        val buttonIds = when (action) {
            "like" -> listOf("like_button", "composer_like", "like", "LIKE", "action_like")
            "save" -> listOf("save_button", "bookmark", "download", "SAVE", "save_video")
            "share" -> listOf("share_button", "share", "SHARE", "action_share")
            "follow" -> listOf("follow_button", "follow", "FOLLOW", "action_follow")
            "subscribe" -> listOf("subscribe_button", "subscribe", "SUBSCRIBE", "action_subscribe")
            else -> emptyList()
        }

        // Try clicking by content description or view ID
        val root = service.rootInActiveWindow
        if (root != null) {
            for (id in buttonIds) {
                val clicked = service.findAndClickText(id)
                if (clicked) {
                    bridge.speak("Ji Sir! $action kar diya.") { startListening() }
                    return
                }
            }
        }

        bridge.speak("Sir, $action button nahi mila.") { startListening() }
    }

    private fun performSocialComment(text: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            bridge.speak("Sir, Accessibility Service enable nahi hai.") { startListening() }
            return
        }

        // Try to find and click comment box to focus it
        val commentBoxIds = listOf("comment", "comment_field", "add_comment", "reply", "comment_box", "input_comment", "composer_comment")
        var focused = false
        for (id in commentBoxIds) {
            if (service.findAndClickText(id)) {
                focused = true
                handler.postDelayed({
                    // Type the text in the focused field
                    service.findAndTypeTextFuzzy(text)
                }, 500)
                break
            }
        }

        if (!focused) {
            // Direct fallback: type in any available text field
            focused = service.findAndTypeTextFuzzy(text)
        }

        if (focused) {
            handler.postDelayed({
                // Try to find and click post/send button
                val postIds = listOf("post", "send", "done", "POST", "arrow_forward", "send_button")
                for (p in postIds) {
                    if (service.findAndClickText(p)) break
                }
                bridge.speak("Ji Sir! Comment kar diya.") { startListening() }
            }, 1000)
        } else {
            bridge.speak("Sir, comment box nahi mila.") { startListening() }
        }
    }

    private fun performAppSearch(query: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            bridge.webSearch(query)
            return
        }
        
        // Try to find search bar in current app
        val searchBoxIds = listOf("search_src_text", "search_bar", "search_edit_text", "search", "search_input")
        var focused = false
        for (id in searchBoxIds) {
            if (service.findAndClickId(id) || service.findAndClickText("Search")) {
                focused = true
                handler.postDelayed({
                    service.findAndTypeTextFuzzy(query)
                    // Try to press enter or click search button
                    handler.postDelayed({
                        service.findAndClickId("search_go_btn") || service.findAndClickDescription("Search")
                    }, 500)
                }, 500)
                break
            }
        }
        
        if (!focused) {
            bridge.speak("Sir, app mein search nahi mila. Web search kar raha hoon.") {
                bridge.webSearch(query)
                handler.postDelayed({ startListening() }, 3000)
            }
        } else {
            bridge.speak("Ji Sir! $query dhoondh raha hoon.") {
                handler.postDelayed({ startListening() }, 3000)
            }
        }
    }

    // ===== EMOJI COMMANDS =====
    private fun processEmojiCommands(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()

        // Check for emoji keywords
        val emojiKeywords = listOf("emoji", "emojee", "smiley", "emoticon", "mohra", "sticker")
        val hasEmojiKeyword = emojiKeywords.any { lower.contains(it) }

        if (!hasEmojiKeyword) return false

        // Extract emoji name: "emoji bhejo heart" or "heart emoji send karo" or "smile emoji"
        val emojiName = cmd.replace(Regex("(emoji|emojee|smiley|emoticon|mohra|sticker|bhej|bhejo|send|karo|kar do|kar|de|do|dal|dalo)", RegexOption.IGNORE_CASE), "").trim()

        if (emojiName.isNotEmpty()) {
            val emojiChar = resolveEmoji(emojiName)
            if (emojiChar.isNotEmpty()) {
                sendEmoji(emojiChar)
                return true
            }
        }

        // Open emoji picker
        bridge.speak("Sir, kaunsa emoji bhejna hai? Batao.") { startListening() }
        return true
    }

    private fun resolveEmoji(name: String): String {
        val map = mapOf(
            "smile" to "\uD83D\uDE0A", "smiley" to "\uD83D\uDE03", "happy" to "\uD83D\uDE04",
            "laugh" to "\uD83D\uDE06", "lol" to "\uD83D\uDE02", "rofl" to "\uD83E\uDD23",
            "love" to "\uD83D\uDE0D", "heart" to "\u2764\uFE0F", "red heart" to "\u2764\uFE0F",
            "broken heart" to "\uD83D\uDC94", "heart eyes" to "\uD83D\uDE0D",
            "wink" to "\uD83D\uDE09", "kiss" to "\uD83D\uDE17", "kissing" to "\uD83D\uDE18",
            "blush" to "\uD83D\uDE0A", "cool" to "\uD83D\uDE0E", "sunglasses" to "\uD83D\uDE0E",
            "cry" to "\uD83D\uDE22", "crying" to "\uD83D\uDE2D", "sad" to "\uD83D\uDE14",
            "angry" to "\uD83D\uDE20", "mad" to "\uD83D\uDE21", "frustrated" to "\uD83D\uDE24",
            "surprised" to "\uD83D\uDE2E", "shock" to "\uD83D\uDE32", "wow" to "\uD83D\uDE2E",
            "fear" to "\uD83D\uDE28", "scared" to "\uD83D\uDE31", "think" to "\uD83E\uDD14",
            "thinking" to "\uD83E\uDD14", "confused" to "\uD83D\uDE15", "shrug" to "\uD83E\uDD37",
            "sleep" to "\uD83D\uDE34", "sleepy" to "\uD83D\uDE2A", "tired" to "\uD83D\uDE2B",
            "sick" to "\uD83E\uDD12", "vomit" to "\uD83E\uDD2E", "party" to "\uD83E\uDD73",
            "celebrate" to "\uD83C\uDF89", "fire" to "\uD83D\uDD25", "lit" to "\uD83D\uDD25",
            "clap" to "\uD83D\uDC4F", "applause" to "\uD83D\uDC4F", "ok" to "\uD83D\uDC4C",
            "thumb" to "\uD83D\uDC4D", "thumbs up" to "\uD83D\uDC4D", "thumbs down" to "\uD83D\uDC4E",
            "wave" to "\uD83D\uDC4B", "hello" to "\uD83D\uDC4B", "bye" to "\uD83D\uDC4B",
            "namaste" to "\uD83D\uDE4F", "pray" to "\uD83D\uDE4F", "folded hands" to "\uD83D\uDE4F",
            "muscle" to "\uD83D\uDCAA", "strong" to "\uD83D\uDCAA", "flex" to "\uD83D\uDCAA",
            "100" to "\uD83D\uDCAF", "perfect" to "\uD83D\uDCAF",
            "clown" to "\uD83E\uDD21", "nerd" to "\uD83E\uDD13", "alien" to "\uD83D\uDC7D",
            "ghost" to "\uD83D\uDC7B", "skull" to "\uD83D\uDC80", "robot" to "\uD83E\uDD16",
            "dog" to "\uD83D\uDC36", "cat" to "\uD83D\uDC31", "lion" to "\uD83E\uDD81",
            "tiger" to "\uD83D\uDC2F", "monkey" to "\uD83D\uDC35", "panda" to "\uD83D\uDC3C",
            "fox" to "\uD83E\uDD8A", "rabbit" to "\uD83D\uDC30", "frog" to "\uD83D\uDC38",
            "chicken" to "\uD83D\uDC14", "bird" to "\uD83D\uDC26", "peacock" to "\uD83E\uDD9A",
            "snake" to "\uD83D\uDC0D", "dragon" to "\uD83D\uDC32", "unicorn" to "\uD83E\uDD84",
            "star" to "\u2B50", "sparkle" to "\u2728", "glow" to "\uD83D\uDCA1",
            "moon" to "\uD83C\uDF19", "sun" to "\u2600\uFE0F", "rainbow" to "\uD83C\uDF08",
            "flower" to "\uD83C\uDF3C", "rose" to "\uD83C\uDF39", "sunflower" to "\uD83C\uDF3B",
            "tree" to "\uD83C\uDF33", "pizza" to "\uD83C\uDF55", "burger" to "\uD83C\uDF54",
            "fries" to "\uD83C\uDF5F", "cake" to "\uD83C\uDF70", "ice cream" to "\uD83C\uDF66",
            "coffee" to "\u2615", "tea" to "\u2615", "chai" to "\u2615",
            "beer" to "\uD83C\uDF7A", "wine" to "\uD83C\uDF77", "cocktail" to "\uD83C\uDF78",
            "music" to "\uD83C\uDFB5", "guitar" to "\uD83C\uDFB8", "drum" to "\uD83E\uDD41",
            "car" to "\uD83D\uDE97", "plane" to "\u2708\uFE0F", "rocket" to "\uD83D\uDE80",
            "bike" to "\uD83D\uDEB2", "train" to "\uD83D\uDE82", "bus" to "\uD83D\uDE8C",
            "phone" to "\uD83D\uDCF1", "mobile" to "\uD83D\uDCF1", "call" to "\uD83D\uDCDE",
            "email" to "\u2709\uFE0F", "mail" to "\u2709\uFE0F", "letter" to "\uD83D\uDCEB",
            "camera" to "\uD83D\uDCF7", "photo" to "\uD83D\uDCF8", "video" to "\uD83D\uDCF9",
            "tv" to "\uD83D\uDCFA", "game" to "\uD83C\uDFAE", "gift" to "\uD83C\uDF81",
            "ball" to "\u26BD", "football" to "\u26BD", "cricket" to "\uD83C\uDFCF",
            "flag" to "\uD83C\uDFF4", "india" to "\uD83C\uDDEE\uD83C\uDDF3", "pakistan" to "\uD83C\uDDF5\uD83C\uDDF0",
            "fire" to "\uD83D\uDD25", "poop" to "\uD83D\uDCA9", "shit" to "\uD83D\uDCA9",
            "middle finger" to "\uD83D\uDD95", "fuck" to "\uD83D\uDD95",
            "peace" to "\u270C\uFE0F", "victory" to "\u270C\uFE0F", "v" to "\u270C\uFE0F"
        )

        // Try exact match first
        val clean = name.lowercase().trim()
        map[clean]?.let { return it }

        // Try fuzzy match
        for ((key, value) in map) {
            if (clean.contains(key) || key.contains(clean)) return value
        }

        // Default heart
        return "\u2764\uFE0F"
    }

    private fun sendEmoji(emoji: String) {
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            // Try to paste emoji in active text field
            val typed = service.findAndTypeTextFuzzy(emoji)
            if (typed) {
                bridge.speak("Ji Sir! $emoji bhej diya.") { startListening() }
                return
            }
        }

        // Fallback: copy to clipboard
        bridge.copyToClipboard(emoji)
        bridge.speak("Sir, $emoji clipboard mein copy kar diya. Ab paste karein.") {
            handler.postDelayed({ startListening() }, 3000)
        }
    }

    // ===== SCREEN COMMANDS =====
    private fun processScreenCommands(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()

        // Screen analysis: "screen par kya hai", "screen batao", "what's on screen"
        if (lower.contains("screen par kya") || lower.contains("screen batao") || lower.contains("what's on screen") ||
            lower.contains("screen dikhao") || lower.contains("screen read") || lower.contains("screen read karo")) {
            val content = bridge.getScreenContent()
            if (content.isNotEmpty()) {
                bridge.speak("Sir, screen par yeh hai: ${content.take(500)}") { startListening() }
            } else {
                bridge.speak("Sir, screen par kuch nahi dikh raha.") { startListening() }
            }
            return true
        }

        // Chats/conversations: "kaun si chats hain", "chats batao"
        if (lower.contains("chat") || lower.contains("baat") || lower.contains("conversation")) {
            if (lower.contains("batao") || lower.contains("kaun") || lower.contains("dikhao") || lower.contains("list") || lower.contains("kaun si")) {
                val service = JarvisAccessibilityService.instance
                if (service != null && service.rootInActiveWindow != null) {
                    screenAnalyzer?.analyzeScreen(service.rootInActiveWindow)
                    val chatNames = screenAnalyzer?.extractChatNames() ?: emptyList()
                    if (chatNames.isNotEmpty()) {
                        val chatList = chatNames.take(15).joinToString(", ")
                        bridge.speak("Sir, screen par yeh chats hain: $chatList") { startListening() }
                    } else {
                        bridge.speak("Sir, screen par koi chat nahi mili.") { startListening() }
                    }
                } else {
                    bridge.speak("Sir, Accessibility Service enable nahi hai.") { startListening() }
                }
                return true
            }
        }

        // Element detection: "button batao", "kaun se buttons hain"
        if (lower.contains("button") && (lower.contains("batao") || lower.contains("kaun se") || lower.contains("kitne") || lower.contains("find"))) {
            val service = JarvisAccessibilityService.instance
            if (service != null) {
                contextEngine?.updateScreenElements(service)
                val buttons = contextEngine?.getDetectedButtons()
                if (buttons.isNullOrEmpty()) {
                    bridge.speak("Sir, screen par koi button nahi mila.") { startListening() }
                } else {
                    bridge.speak("Sir, ${buttons.size} buttons hain: ${buttons.joinToString(", ")}") { startListening() }
                }
            } else {
                bridge.speak("Sir, Accessibility Service enable nahi hai.") { startListening() }
            }
            return true
        }

        // App info: "kaunsa app hai", "current app batao"
        if (lower.contains("kaunsa app") || lower.contains("current app") || lower.contains("which app") ||
            lower.contains("konsa app")) {
            val pkg = bridge.getCurrentApp()
            if (pkg != null) {
                val appName = pkg.split(".").lastOrNull() ?: pkg
                bridge.speak("Sir, aap $appName mein hain.") { startListening() }
            } else {
                bridge.speak("Sir, pata nahi kaunsa app hai.") { startListening() }
            }
            return true
        }

        // Refresh screen: "screen refresh karo", "dobara check karo"
        if (lower.contains("screen refresh") || lower.contains("screen dobara") || lower.contains("screen re-analyze")) {
            updateScreenContext()
            bridge.speak("Ji Sir! Screen dobara check kar liya.") { startListening() }
            return true
        }

        // Context-aware element action: "send button dabao", "search bar mein type karo"
        if (lower.contains("send") && (lower.contains("dabao") || lower.contains("click") || lower.contains("press") || lower.contains("touch"))) {
            val service = JarvisAccessibilityService.instance
            if (service != null && service.rootInActiveWindow != null) {
                screenAnalyzer?.analyzeScreen(service.rootInActiveWindow)
                val sendBtn = screenAnalyzer?.findRelevantElement("send")
                if (sendBtn != null) {
                    val clicked = service.clickElement(sendBtn.centerX, sendBtn.centerY)
                    bridge.speak("Ji Sir! Send button dab gaya.") { startListening() }
                } else {
                    // Fallback to text search
                    service.findAndClickText("Send")
                    bridge.speak("Ji Sir! Send dhundh kar dabaya.") { startListening() }
                }
            }
            return true
        }

        return false
    }

    // ===== EXTRACTION HELPERS =====
    private fun extractWhatsAppName(cmd: String): String {
        val lower = cmd.lowercase().trim()
        val koIndex = lower.indexOf(" ko ")
        if (koIndex >= 0) return cmd.substring(0, koIndex).trim()
        val toIndex = lower.indexOf(" to ")
        if (toIndex >= 0) return cmd.substring(0, toIndex).trim()
        for (sep in listOf("msg", "message", "send", "bhej", "whatsapp", "likh", "bol")) {
            val idx = lower.indexOf(sep)
            if (idx >= 0) {
                val before = cmd.substring(0, idx).trim()
                if (before.isNotEmpty()) return before.split("\\s+".toRegex()).lastOrNull() ?: return before
            }
        }
        return ""
    }

    private fun extractWhatsAppMessage(cmd: String): String {
        val lower = cmd.lowercase().trim()
        val separators = listOf("message ", "msg ", "send ", "bhej ", "likh ", "bol ")
        for (sep in separators) {
            val idx = lower.indexOf(sep)
            if (idx >= 0) {
                var msg = cmd.substring(idx + sep.length).trim()
                msg = msg.replace(Regex("\\b(karo|do|de|kar do|kar|send)\\b", RegexOption.IGNORE_CASE), "").trim()
                return msg.trimStart(' ', ',', '.', '!')
            }
        }
        // Also check without trailing space
        for (sep in listOf("message", "msg", "send", "bhej", "likh", "bol")) {
            val idx = lower.indexOf(sep)
            if (idx >= 0) {
                var after = cmd.substring(idx + sep.length).trim()
                after = after.replace(Regex("\\b(karo|do|de|kar do|kar|send)\\b", RegexOption.IGNORE_CASE), "").trim()
                if (after.isNotEmpty()) return after
            }
        }
        return ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent): IBinder? = null
    override fun onDestroy() {
        isListening = false
        speechRecognizer?.destroy()
        bridge.shutdown()
        super.onDestroy()
    }
}
