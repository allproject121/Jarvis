package com.example.myapplication

import android.util.Log
import java.util.regex.Pattern

class JarvisCommandEngine(private val bridge: AndroidBridge) {

    data class CommandMatch(
        val action: String,
        val params: MutableMap<String, String> = mutableMapOf(),
        val confidence: Float = 1.0f
    )

    data class CommandEntry(
        val category: String,
        val variations: List<String>,
        val action: String,
        val paramExtractors: List<(String) -> Pair<String, String>?> = emptyList(),
        val needsContext: Boolean = false,
        val description: String = ""
    )

    private val commands = mutableListOf<CommandEntry>()

    init {
        buildCommandDatabase()
    }

    private fun addCmd(category: String, variations: List<String>, action: String,
                       paramExtractors: List<(String) -> Pair<String, String>?> = emptyList(),
                       needsContext: Boolean = false, description: String = "") {
        commands.add(CommandEntry(category, variations, action, paramExtractors, needsContext, description))
    }

    private fun buildCommandDatabase() {
        // ===== APP CONTROL =====
        addCmd("APP_OPEN", listOf(
            "open {app}", "kholo {app}", "khol {app}", "chalao {app}", "launch {app}",
            "{app} kholo", "{app} open karo", "{app} chalao", "{app} launch karo",
            "{app} start karo"
        ), "OPEN_APP", description = "Kisi bhi app ko open/khol deta hai")

        addCmd("APP_CLOSE", listOf(
            "close all", "band karo", "stop all", "app band karo", "sab band karo",
            "close app", "exit karo", "bahar jao", "home jao", "home karo"
        ), "GO_HOME", description = "Current app se bahar nikalta hai / home jata hai")

        // ===== DEVICE SETTINGS =====
        addCmd("WIFI_ON", listOf(
            "wifi on karo", "wifi on", "wifi chalu karo", "wifi enable karo",
            "wifi start karo", "wifi ko on karo", "wifi khol do", "wifi chal do",
            "wifi active karo", "wifi connect karo"
        ), "WIFI_ON")

        addCmd("WIFI_OFF", listOf(
            "wifi off karo", "wifi off", "wifi band karo", "wifi disable karo",
            "wifi ko off karo", "wifi stop karo", "wifi hata do", "wifi shut down karo",
            "wifi inactive karo", "wifi disconnect karo"
        ), "WIFI_OFF")

        addCmd("WIFI_TOGGLE", listOf(
            "wifi toggle", "wifi badlo", "wifi change karo", "wifi flip karo",
            "wifi ulat do", "wifi switch karo"
        ), "WIFI_TOGGLE")

        addCmd("BLUETOOTH_ON", listOf(
            "bluetooth on karo", "bluetooth on", "bluetooth chalu karo", "bluetooth enable karo",
            "bluetooth start karo", "bluetooth ko on karo", "bluetooth active karo",
            "blue on karo", "bluetooth khol do", "bluetooth chal do"
        ), "BLUETOOTH_ON")

        addCmd("BLUETOOTH_OFF", listOf(
            "bluetooth off karo", "bluetooth off", "bluetooth band karo", "bluetooth disable karo",
            "bluetooth stop karo", "bluetooth ko off karo", "bluetooth hata do",
            "blue off karo", "bluetooth inactive karo", "bluetooth shut down karo"
        ), "BLUETOOTH_OFF")

        addCmd("FLASHLIGHT_ON", listOf(
            "flashlight on karo", "flash on", "torch on", "light on karo",
            "flashlight chalu karo", "torch chalu karo", "light khol do",
            "flashlight enable karo", "torch enable karo", "roshni khol do"
        ), "FLASHLIGHT_ON")

        addCmd("FLASHLIGHT_OFF", listOf(
            "flashlight off karo", "flash off", "torch off", "light off karo",
            "flashlight band karo", "torch band karo", "light band karo",
            "flashlight disable karo", "torch disable karo", "roshni band karo"
        ), "FLASHLIGHT_OFF")

        addCmd("FLASHLIGHT_TOGGLE", listOf(
            "flashlight toggle", "torch toggle", "flash change karo", "light badlo",
            "flashlight flip karo", "torch flip karo", "torch badlo"
        ), "FLASHLIGHT_TOGGLE")

        // ===== VOLUME CONTROL =====
        addCmd("VOLUME_UP", listOf(
            "volume up karo", "volume up", "awaz badhao", "awaz tej karo",
            "volume barhao", "sound up karo", "volume tej karo", "awaz up karo",
            "volume increase karo", "sound tej karo"
        ), "VOLUME_UP")

        addCmd("VOLUME_DOWN", listOf(
            "volume down karo", "volume down", "awaz kam karo", "awaz ghatao",
            "volume ghatao", "sound down karo", "volume kam karo", "awaz down karo",
            "volume decrease karo", "sound kam karo"
        ), "VOLUME_DOWN")

        addCmd("VOLUME_SET", listOf(
            "volume {level} set karo", "volume {level} karo", "awaz {level} karo",
            "volume {level} par set karo", "sound {level} karo"
        ), "VOLUME_SET", listOf(
            { extractParam(it, "volume ", " set| karo| par") },
            { extractParam(it, "awaz ", " karo") },
            { extractParam(it, "sound ", " karo") }
        ))

        addCmd("MUTE", listOf(
            "mute karo", "mute", "chup karo", "silent karo",
            "awaz band karo", "sound band karo", "mute kar do",
            "chup ho jao", "silent mode", "awaz hatao"
        ), "MUTE")

        addCmd("UNMUTE", listOf(
            "unmute karo", "unmute", "awaz wapas lao", "sound on karo",
            "mute hatao", "unmute kar do", "awaz khol do"
        ), "UNMUTE")

        // ===== RINGER MODE =====
        addCmd("RINGER_SILENT", listOf(
            "silent mode karo", "silent karo", "phone silent karo", "silent mode",
            "phone chup karo", "ringer silent karo", "silent kar do",
            "phone chup rahe", "no sound", "khamosh karo"
        ), "RINGER_SILENT")

        addCmd("RINGER_VIBRATE", listOf(
            "vibrate mode karo", "vibrate karo", "phone vibrate karo", "vibrate mode",
            "vibration on karo", "ringer vibrate karo", "vibrate kar do",
            "phone vibrate rahe", "vibration mode", "vibrate pe rakh do"
        ), "RINGER_VIBRATE")

        addCmd("RINGER_NORMAL", listOf(
            "normal mode karo", "normal karo", "phone normal karo", "normal mode",
            "ringer normal karo", "normal kar do", "sound wapas lao", "general mode",
            "phone normal rahe", "sound on karo"
        ), "RINGER_NORMAL")

        // ===== BRIGHTNESS =====
        addCmd("BRIGHTNESS_UP", listOf(
            "brightness up karo", "brightness barhao", "roshni badhao", "screen bright karo",
            "brightness tej karo", "roshni tej karo", "brightness up", "screen roshni badhao",
            "display bright karo", "brightness increase karo"
        ), "BRIGHTNESS_UP")

        addCmd("BRIGHTNESS_DOWN", listOf(
            "brightness down karo", "brightness ghatao", "roshni kam karo", "screen dim karo",
            "brightness kam karo", "roshni kam karo", "brightness down", "screen roshni kam karo",
            "display dim karo", "brightness decrease karo"
        ), "BRIGHTNESS_DOWN")

        addCmd("BRIGHTNESS_SET", listOf(
            "brightness {level} karo", "roshni {level} karo", "brightness {level} set karo",
            "screen {level} karo", "display {level} karo"
        ), "BRIGHTNESS_SET", listOf(
            { extractParam(it, "brightness ", " karo| set| par") },
            { extractParam(it, "roshni ", " karo") },
            { extractParam(it, "screen ", " karo") }
        ))

        // ===== AIRPLANE MODE =====
        addCmd("AIRPLANE_ON", listOf(
            "airplane mode on karo", "flight mode on karo", "airplane on karo",
            "flight mode chalu karo", "airplane mode active karo", "flight mode enable karo",
            "airplane lagao", "flight mode lagao", "airplane chal do", "flight mode chal do"
        ), "AIRPLANE_ON")

        addCmd("AIRPLANE_OFF", listOf(
            "airplane mode off karo", "flight mode off karo", "airplane off karo",
            "flight mode band karo", "airplane mode disable karo", "flight mode hatao",
            "airplane hatao", "flight mode hata do", "airplane mode stop karo", "flight mode band do"
        ), "AIRPLANE_OFF")

        // ===== DO NOT DISTURB =====
        addCmd("DND_ON", listOf(
            "dnd on karo", "do not disturb on karo", "dnd chalu karo",
            "disturb mat karo", "dnd enable karo", "do not disturb enable karo",
            "dnd active karo", "dnd lagao", "dnd chal do", "dnd on"
        ), "DND_ON")

        addCmd("DND_OFF", listOf(
            "dnd off karo", "do not disturb off karo", "dnd band karo",
            "disturb kar sakte ho", "dnd disable karo", "do not disturb hatao",
            "dnd hatao", "dnd stop karo", "dnd off", "dnd band do"
        ), "DND_OFF")

        // ===== SCREEN =====
        addCmd("SCREENSHOT", listOf(
            "screenshot lo", "screenshot le lo", "screenshot karo", "screen capture karo",
            "capture karo", "screenshot le", "screen shot lo", "photo le screen ka",
            "screen ka photo lo", "capture screen"
        ), "SCREENSHOT")

        addCmd("SCREEN_READ", listOf(
            "screen read karo", "screen par kya hai", "yeh kya hai", "screen padho",
            "screen read", "screen content batao", "screen mein kya likha hai",
            "screen dikhao", "page read karo", "yeh page kya hai"
        ), "SCREEN_READ")

        // ===== GESTURES =====
        addCmd("SCROLL_DOWN", listOf(
            "scroll down karo", "scroll down", "neeche scroll karo", "down scroll karo",
            "page neeche karo", "scroll neeche", "upar se neeche karo", "age badhao",
            "scroll aage", "page down karo"
        ), "SCROLL_DOWN")

        addCmd("SCROLL_UP", listOf(
            "scroll up karo", "scroll up", "upar scroll karo", "up scroll karo",
            "page upar karo", "scroll upar", "piche scroll karo", "peeche jao",
            "scroll piche", "page up karo"
        ), "SCROLL_UP")

        addCmd("SWIPE_LEFT", listOf(
            "swipe left karo", "left swipe karo", "left jao", "left scroll karo",
            "swipe karo left", "left me jao", "bayein jao", "left side jao",
            "previous page", "pichla page"
        ), "SWIPE_LEFT")

        addCmd("SWIPE_RIGHT", listOf(
            "swipe right karo", "right swipe karo", "right jao", "right scroll karo",
            "swipe karo right", "right me jao", "dahine jao", "right side jao",
            "next page", "agla page"
        ), "SWIPE_RIGHT")

        addCmd("BACK", listOf(
            "back jao", "back karo", "piche jao", "back", "piche",
            "wapis jao", "pichle page par jao", "back button dabao", "piche hato",
            "ek step piche"
        ), "BACK")

        addCmd("HOME", listOf(
            "home jao", "home karo", "home", "bahar jao", "home screen jao",
            "main screen par jao", "desktop jao", "home button dabao", "ghar jao",
            "homescreen jao"
        ), "HOME")

        addCmd("RECENTS", listOf(
            "recents kholo", "recent apps kholo", "recent jao", "recents dikhao",
            "recent apps dikhao", "app switcher kholo", "recent mein jao",
            "recent screen dikhao", "recents", "recent apps"
        ), "RECENTS")

        addCmd("NOTIFICATIONS", listOf(
            "notifications kholo", "notification kholo", "notifications dikhao",
            "notification bar kholo", "notification panel kholo", "notifications check karo",
            "notification dekh", "notification dikhao", "notifications", "notification khol do"
        ), "NOTIFICATIONS")

        addCmd("QUICK_SETTINGS", listOf(
            "quick settings kholo", "quick settings dikhao", "quick panel dikhao",
            "settings panel kholo", "quick settings", "quick panel kholo",
            "settings shortcut dikhao", "quick toggles dikhao"
        ), "QUICK_SETTINGS")

        addCmd("LOCK_SCREEN", listOf(
            "screen lock karo", "phone lock karo", "lock screen karo", "phone band karo",
            "screen band karo", "lock kar do", "phone lock kar do", "lock karo",
            "mobile lock karo", "screen ko lock karo"
        ), "LOCK_SCREEN")

        // ===== WHATSAPP =====
        addCmd("WHATSAPP_OPEN", listOf(
            "whatsapp open karo", "whatsapp kholo", "whatsapp khol", "whatsapp chalao",
            "whatsapp launch karo", "whatsapp start karo", "whatsapp khol do",
            "whatsapp khol ke dikhao", "whatsapp ko kholo", "whatsapp open"
        ), "WHATSAPP_OPEN")

        addCmd("WHATSAPP_SEARCH", listOf(
            "whatsapp mein {contact} dhoondo", "whatsapp mein {contact} search karo",
            "whatsapp mein {contact} find karo", "{contact} ko whatsapp mein dhoondo",
            "whatsapp khol ke {contact} dhoondo", "whatsapp mein {contact} khojo",
            "{contact} whatsapp mein search karo", "whatsapp mein {contact} dhundo",
            "{contact} ko whatsapp mein find karo", "whatsapp mein {contact} talash karo"
        ), "WHATSAPP_SEARCH", listOf(
            { extractParam(it, "whatsapp mein ", " dhoondo| search| find| khojo| dhundo| talash") },
            { extractParamAfter(it, listOf(" whatsapp mein", "ko whatsapp mein")) }
        ))

        addCmd("WHATSAPP_MSG", listOf(
            "{contact} ko msg bhejdo {message}", "{contact} ko message karo {message}",
            "{contact} ko bhejdo {message}", "{contact} ko likho {message}",
            "{contact} ko send karo {message}", "{contact} ko bol {message}",
            "send {contact} {message}", "msg {contact} ko {message}",
            "message {contact} ko {message}", "{contact} ko whatsapp kar do {message}"
        ), "WHATSAPP_MSG", description = "WhatsApp par message bhejta hai")

        addCmd("WHATSAPP_CALL", listOf(
            "whatsapp call karo {contact}", "{contact} ko whatsapp call karo",
            "{contact} ko whatsapp pe call karo", "whatsapp pe {contact} ko call karo",
            "whatsapp call {contact}", "{contact} ka whatsapp call lagao",
            "{contact} ko wa call karo", "whatsapp pe call karo {contact}",
            "video call karo {contact}", "{contact} ko whatsapp video call karo"
        ), "WHATSAPP_CALL", listOf(
            { extractParamAfter(it, listOf("whatsapp call karo", "whatsapp pe call karo", "whatsapp call")) },
            { extractParamBefore(it, listOf(" ko whatsapp", " ko wa ")) }
        ))

        addCmd("WHATSAPP_VIDEO_CALL", listOf(
            "whatsapp video call {contact}", "{contact} ko whatsapp video call karo",
            "video call karo {contact}", "{contact} ko video call karo",
            "whatsapp video {contact}", "{contact} ka video call karo",
            "whatsapp pe video call karo {contact}", "video call {contact}",
            "{contact} ko video karo", "whatsapp video call"
        ), "WHATSAPP_VIDEO_CALL")

        // ===== CONTACTS =====
        addCmd("CONTACTS_SHOW", listOf(
            "contacts dikhao", "contacts kholo", "contacts list dikhao",
            "saved contacts dikhao", "phone book dikhao", "contacts show karo",
            "mere contacts dikhao", "contact list dikhao", "contacts khol ke dikhao",
            "all contacts dikhao"
        ), "CONTACTS_SHOW")

        addCmd("CONTACT_SAVE", listOf(
            "{name} ka number {number} save karo", "{name} ko save karo {number}",
            "{name} ka contact save karo {number}", "{name} {number} save karo",
            "save contact {name} {number}", "naya contact save karo {name} {number}",
            "{name} ko contacts mein add karo {number}", "contact add karo {name} {number}",
            "{name} ka number save kar lo {number}", "{name} {number} yad rakho"
        ), "CONTACT_SAVE")

        addCmd("CONTACT_DELETE", listOf(
            "{name} ko delete karo", "{name} ka contact delete karo",
            "{name} ko hatao", "{name} ko remove karo",
            "{name} ka number delete karo", "{name} ko contacts se hatao",
            "{name} ka contact mitao", "{name} ko erase karo",
            "delete contact {name}", "remove contact {name}"
        ), "CONTACT_DELETE")

        addCmd("CONTACT_FIND", listOf(
            "{name} ka number batao", "{name} ka number dhoondo",
            "{name} ka contact dhoondo", "{name} ka number find karo",
            "{name} ka phone number batao", "{name} ka number kya hai",
            "{name} search karo", "{name} ka contact dhundo",
            "{name} ka number nikal do", "{name} ka number khojo"
        ), "CONTACT_FIND")

        // ===== CALL =====
        addCmd("CALL", listOf(
            "{contact} ko call karo", "{contact} ko phone karo", "{contact} ko dial karo",
            "{contact} ka call lagao", "{contact} ko call kar do", "{contact} ko phone lagao",
            "call karo {contact}", "phone karo {contact}", "dial karo {contact}",
            "{contact} ko call laga do"
        ), "CALL", description = "Kisi bhi contact ko call karta hai")

        // ===== SMS =====
        addCmd("SMS", listOf(
            "{contact} ko sms karo {message}", "{contact} ko text karo {message}",
            "{contact} ko sms bhejo {message}", "{contact} ko message bhejo {message}",
            "sms bhejo {contact} ko {message}", "text bhejo {contact} ko {message}",
            "send sms to {contact} {message}", "send text to {contact} {message}",
            "{contact} ko sms kar do {message}", "{contact} ko text kar do {message}"
        ), "SMS")

        // ===== ALARM =====
        addCmd("ALARM_SET", listOf(
            "{time} ka alarm set karo", "alarm set karo {time}", "alarm laga do {time}",
            "{time} par alarm laga do", "{time} ke liye alarm set karo",
            "alarm {time} baje ka laga do", "{hour} baje alarm laga do",
            "{hour} {minute} par alarm set karo"
        ), "ALARM_SET")

        // ===== TIMER =====
        addCmd("TIMER_SET", listOf(
            "{seconds} second ka timer set karo", "{seconds} second ka timer laga do",
            "timer set karo {seconds}", "{minutes} minute ka timer set karo",
            "{minutes} minute ka timer laga do", "timer {seconds} seconds ka laga do",
            "{seconds} sec ka timer", "{minutes} min ka timer set karo"
        ), "TIMER_SET")

        // ===== CAMERA =====
        addCmd("CAMERA_OPEN", listOf(
            "camera open karo", "camera kholo", "camera chalao", "camera khol",
            "camera khol do", "camera launch karo", "camera on karo",
            "camera start karo", "photo khinchne ka camera kholo", "camera khol ke dikhao"
        ), "CAMERA_OPEN")

        addCmd("CAMERA_VIDEO", listOf(
            "video camera kholo", "video record karo", "camera video mode kholo",
            "video camera chalao", "video mode kholo", "recording start karo",
            "video record karne ka camera kholo", "video cam kholo"
        ), "CAMERA_VIDEO")

        addCmd("PHOTO_CLICK", listOf(
            "photo click karo", "photo lo", "picture lo", "photo le lo",
            "picture click karo", "photo khincho", "picture khincho",
            "foto le lo", "tasveer lo", "selfie lo"
        ), "PHOTO_CLICK")

        // ===== MEDIA =====
        addCmd("MUSIC_PLAY", listOf(
            "music play karo", "music chalao", "gaana chalao", "song play karo",
            "music chal do", "gaana chal do", "music start karo", "song chalao",
            "music bajao", "gaana bajao"
        ), "MUSIC_PLAY")

        addCmd("MUSIC_PAUSE", listOf(
            "music pause karo", "music stop karo", "gaana band karo", "song pause karo",
            "music ruk do", "gaana ruk do", "music stop", "gaana band kar do",
            "song stop karo", "music rok do"
        ), "MUSIC_PAUSE")

        addCmd("MUSIC_NEXT", listOf(
            "next song", "agla gaana", "age badhao", "next track",
            "agla song chalao", "skip karo", "age karo", "next karo",
            "agla gaana chalao", "next gaana chal do"
        ), "MUSIC_NEXT")

        addCmd("MUSIC_PREVIOUS", listOf(
            "previous song", "pichla gaana", "piche jao", "previous track",
            "pichla song chalao", "back karo song", "pichla gaana chalao",
            "previous karo", "pichle gaane par jao", "pichli track"
        ), "MUSIC_PREVIOUS")

        // ===== YOUTUBE =====
        addCmd("YOUTUBE_OPEN", listOf(
            "youtube open karo", "youtube kholo", "youtube chalao",
            "youtube khol", "youtube launch karo", "youtube start karo",
            "youtube khol do", "youtube khol ke dikhao", "youtube on karo"
        ), "YOUTUBE_OPEN")

        addCmd("YOUTUBE_SEARCH", listOf(
            "youtube mein {query} search karo", "youtube par {query} dhoondo",
            "youtube pe {query} search karo", "youtube mein {query} dhundo",
            "youtube {query} search karo", "youtube pe {query} dhoondo",
            "youtube par {query} search karo", "youtube mein {query} play karo",
            "youtube par {query} chalao", "youtube {query} dikhao"
        ), "YOUTUBE_SEARCH")

        // ===== WEB SEARCH =====
        addCmd("WEB_SEARCH", listOf(
            "google par {query} search karo", "google karo {query}",
            "search karo {query}", "dhoondo {query}", "khojo {query}",
            "web mein {query} search karo", "internet par {query} dhoondo",
            "browser mein {query} search karo", "{query} search karo",
            "online {query} dhoondo"
        ), "WEB_SEARCH")

        // ===== WEATHER =====
        addCmd("WEATHER", listOf(
            "weather batao", "mausam batao", "aaj ka mausam kya hai",
            "kya barish hogi", "mausam kaisa hai", "weather check karo",
            "aaj ka weather batao", "temperature batao", "mausam ki jankari do",
            "barish hogi kya"
        ), "WEATHER")

        // ===== NEWS =====
        addCmd("NEWS", listOf(
            "news batao", "khabar sunao", "aaj ki news batao",
            "kya news hai", "samachar sunao", "todays news batao",
            "aaj ki khabar batao", "news kya hai", "latest news batao",
            "kya khabar hai"
        ), "NEWS")

        // ===== TIME/DATE =====
        addCmd("TIME", listOf(
            "time batao", "time kya hai", "current time batao",
            "kya time hai", "time check karo", "samay batao",
            "abhi kitna baj raha hai", "time kya hua", "ghanti batao",
            "clock kya bata raha hai"
        ), "TIME")

        addCmd("DATE", listOf(
            "date batao", "aaj kya hai", "tarikh batao", "aaj ki date kya hai",
            "current date batao", "aaj ka din kya hai", "tithi batao",
            "date kya hai", "kya din hai", "aaj konsa din hai"
        ), "DATE")

        // ===== BATTERY =====
        addCmd("BATTERY", listOf(
            "battery batao", "battery level kya hai", "battery check karo",
            "phone mein kitna charge hai", "battery status batao", "charge kitna hai",
            "battery percent batao", "battery kaisi hai", "power batao",
            "battery kitni bachi hai"
        ), "BATTERY")

        // ===== CLIPBOARD =====
        addCmd("CLIPBOARD_COPY", listOf(
            "copy karo", "clipboard mein copy karo", "ye text copy karo",
            "copy kar lo", "copy text", "text copy karo", "copy karke rakh lo",
            "save to clipboard", "clipboard copy", "nকল करो"
        ), "CLIPBOARD_COPY")

        addCmd("CLIPBOARD_PASTE", listOf(
            "paste karo", "clipboard se paste karo", "paste kar do",
            "text paste karo", "paste yahan", "yahan paste karo",
            "clipboard paste karo", "paste kar", "jo copy kiya tha paste karo"
        ), "CLIPBOARD_PASTE")

        addCmd("CLIPBOARD_READ", listOf(
            "clipboard mein kya hai", "clipboard batao", "clipboard read karo",
            "clipboard content kya hai", "clipboard check karo"
        ), "CLIPBOARD_READ")

        // ===== CLICK BY TEXT =====
        addCmd("CLICK_TEXT", listOf(
            "{text} par click karo", "{text} ko click karo", "{text} dabao",
            "{text} button dabao", "{text} pe click karo", "{text} ko press karo",
            "click on {text}", "press {text}", "tap on {text}", "{text} click karo"
        ), "CLICK_TEXT")

        // ===== TYPE TEXT =====
        addCmd("TYPE_TEXT", listOf(
            "{text} type karo", "{text} likho", "{text} write karo",
            "{text} enter karo", "{text} type kar do", "{text} input karo",
            "type {text}", "write {text}", "enter {text}", "likh do {text}"
        ), "TYPE_TEXT")

        // ===== NAVIGATION =====
        addCmd("MAPS_OPEN", listOf(
            "maps open karo", "maps kholo", "google maps kholo", "map kholo",
            "maps chalao", "navigation kholo", "maps launch karo", "maps khol"
        ), "MAPS_OPEN")

        addCmd("NAVIGATE", listOf(
            "{location} ka raasta batao", "{location} ka navigation karo",
            "{location} ka map dikhao", "{location} ka direction batao",
            "navigate to {location}", "{location} ka rasta batao"
        ), "NAVIGATE")

        // ===== SETTINGS =====
        addCmd("SETTINGS_OPEN", listOf(
            "settings open karo", "settings kholo", "settings mein jao",
            "settings khol", "settings dikhao", "settings app kholo",
            "phone settings kholo", "system settings kholo"
        ), "SETTINGS_OPEN")

        addCmd("WIFI_SETTINGS", listOf(
            "wifi settings kholo", "wifi settings mein jao", "wifi settings dikhao",
            "wifi ki settings kholo", "wifi settings open karo"
        ), "WIFI_SETTINGS")

        addCmd("BLUETOOTH_SETTINGS", listOf(
            "bluetooth settings kholo", "bluetooth settings mein jao",
            "bluetooth settings dikhao", "bluetooth ki settings kholo"
        ), "BLUETOOTH_SETTINGS")

        addCmd("SOUND_SETTINGS", listOf(
            "sound settings kholo", "sound settings mein jao", "awaz ki settings kholo",
            "audio settings kholo", "sound settings dikhao"
        ), "SOUND_SETTINGS")

        addCmd("DISPLAY_SETTINGS", listOf(
            "display settings kholo", "screen settings kholo", "display settings mein jao",
            "display ki settings kholo", "screen ki settings kholo"
        ), "DISPLAY_SETTINGS")

        addCmd("BATTERY_SETTINGS", listOf(
            "battery settings kholo", "battery settings mein jao", "battery ki settings kholo",
            "power settings kholo", "battery settings dikhao"
        ), "BATTERY_SETTINGS")

        // ===== ACCESSIBILITY =====
        addCmd("ACCESSIBILITY_SETTINGS", listOf(
            "accessibility settings kholo", "accessibility settings mein jao",
            "accessibility settings dikhao", "accessibility ki settings kholo"
        ), "ACCESSIBILITY_SETTINGS")

        // ===== DEVICE INFO =====
        addCmd("DEVICE_INFO", listOf(
            "device info batao", "phone info batao", "device details batao",
            "mobile info batao", "phone ki jankari do", "device specs batao",
            "phone specs batao", "system info batao"
        ), "DEVICE_INFO")

        addCmd("STORAGE_INFO", listOf(
            "storage info batao", "storage space batao", "kitna storage bacha hai",
            "memory card info batao", "phone storage batao", "storage details batao",
            "kitni jagah bachi hai", "storage usage batao"
        ), "STORAGE_INFO")

        addCmd("RAM_INFO", listOf(
            "ram info batao", "ram details batao", "kitni ram hai",
            "ram usage batao", "ram space batao", "ram kitni hai"
        ), "RAM_INFO")

        // ===== EMERGENCY =====
        addCmd("EMERGENCY_SOS", listOf(
            "emergency sos", "help me", "bachao", "emergency",
            "sos send karo", "emergency alert", "help bhejo", "sos"
        ), "EMERGENCY_SOS")

        // ===== REMINDER =====
        addCmd("REMINDER_SET", listOf(
            "{label} yaad dilana {time} minute baad", "{label} ka reminder set karo {time}",
            "{time} minute baad {label} yaad dilana", "{label} remnder laga do {time} baad",
            "remind me in {time} minutes to {label}"
        ), "REMINDER_SET")

        // ===== SMART ROUTINES =====
        addCmd("ROUTINE_GOOD_NIGHT", listOf(
            "good night", "night mode", "so ja", "good night mode",
            "raat ho gai", "sleep mode", "good night karo", "so jao",
            "night time", "raat ka mode"
        ), "ROUTINE_GOOD_NIGHT")

        addCmd("ROUTINE_GOOD_MORNING", listOf(
            "good morning", "morning mode", "subah ho gai", "good morning mode",
            "subah", "morning karo", "din shuru karo", "good morning karo",
            "morning time", "subah ka mode"
        ), "ROUTINE_GOOD_MORNING")

        addCmd("ROUTINE_WORK", listOf(
            "work mode", "kaam mode", "work karo", "working mode",
            "office mode", "kaam ka mode", "work time", "productive mode",
            "focus mode", "kaam shuru karo"
        ), "ROUTINE_WORK")

        addCmd("ROUTINE_DRIVING", listOf(
            "driving mode", "gaadi mode", "drive mode", "driving",
            "car mode", "gaadi chala raha hoon", "road mode", "driving mode laga do",
            "travel mode", "journey mode"
        ), "ROUTINE_DRIVING")

        addCmd("ROUTINE_MEETING", listOf(
            "meeting mode", "meeting", "meeting mein hoon", "meeting chalu hai",
            "meeting laga do", "meeting mode active karo", "meeting karo",
            "meeting time", "silent meeting", "meeting mein jaa raha hoon"
        ), "ROUTINE_MEETING")

        // ===== SYSTEM TOOLS =====
        addCmd("UNINSTALL_APP", listOf(
            "{app} ko uninstall karo", "{app} delete karo", "{app} hatao",
            "{app} uninstall karo", "{app} remove karo", "{app} ko delete kar do",
            "uninstall {app}", "delete app {app}", "remove {app}", "hata do {app}"
        ), "UNINSTALL_APP")

        addCmd("OPEN_URL", listOf(
            "{url} kholo", "website {url} kholo", "{url} par jao",
            "{url} open karo", "site {url} kholo", "webpage {url} kholo",
            "{url} khol", "{url} site kholo"
        ), "OPEN_URL")

        addCmd("CALCULATOR", listOf(
            "{expression} calculate karo", "{expression} kya hoga",
            "{expression} math karo", "calculate {expression}", "kitna hoga {expression}",
            "{expression} daal", "{expression} ka result batao"
        ), "CALCULATOR")

        // ===== BUTTON DETECTION & CLICKING =====
        addCmd("BUTTON_CLICK", listOf(
            "button click karo", "button dabao", "jo button dikh raha hai usko click karo",
            "button par click karo", "click button", "dikh rahe button ko click karo",
            "koi bhi button click karo", "button press karo", "button ko tap karo",
            "jo button hai usko click karo"
        ), "BUTTON_CLICK")

        // ===== VIDEO =====
        addCmd("VIDEO_NEXT", listOf(
            "next video", "agli video", "agle video par jao", "video skip karo",
            "agli video chalao", "next video chalao", "age karo video", "video aage karo"
        ), "VIDEO_NEXT")

        addCmd("VIDEO_PREVIOUS", listOf(
            "previous video", "pichli video", "pichli video par jao", "video piche karo",
            "pichli video chalao", "back video", "pichli video dikhao"
        ), "VIDEO_PREVIOUS")

        addCmd("VIDEO_FULLSCREEN", listOf(
            "fullscreen karo", "full screen karo", "puri screen mein dekho",
            "fullscreen video", "parde par dekho", "screen bada karo",
            "full screen video karo", "fullscreen mode"
        ), "VIDEO_FULLSCREEN")

        addCmd("VIDEO_360", listOf(
            "360 video karo", "360 degree video", "video ghumao", "video rotate karo",
            "360 mode", "video ko ghumake dekho"
        ), "VIDEO_360")

        // ===== SMART HOME (Simulated) =====
        addCmd("SMART_HOME_LIGHT_ON", listOf(
            "light on karo", "room ka light on karo", "light jalao", "light chalu karo",
            "bulb on karo", "light enable karo", "room light on karo", "light khol do"
        ), "SMART_HOME_LIGHT_ON")

        addCmd("SMART_HOME_LIGHT_OFF", listOf(
            "light off karo", "room ka light off karo", "light bujhao", "light band karo",
            "bulb off karo", "light disable karo", "room light off karo", "light band kar do"
        ), "SMART_HOME_LIGHT_OFF")

        // ===== TELL JOKE =====
        addCmd("JOKE", listOf(
            "joke sunao", "joke batao", "hasao", "ek joke sunao",
            "chutkula sunao", "hasane wali baat batao", "funny joke sunao",
            "joke karo", "kuch hasa do", "laugh karo"
        ), "JOKE")

        addCmd("MOTIVATE", listOf(
            "motivate karo", "inspire karo", "hosla do", "motivation batao",
            "kuch inspiring batao", "uplift karo", "positive karo",
            "energy do", "enthusiasm do", "himmat do"
        ), "MOTIVATE")

        addCmd("QUOTE", listOf(
            "quote batao", "quote sunao", "famous quote batao", "suvichar batao",
            "anmol vachan batao", "kisi ne kya kaha", "quote karo",
            "inspirational quote", "good quote", "best quote"
        ), "QUOTE")

        addCmd("HELLO", listOf(
            "hello", "hi", "hey", "hello jarvis", "hi jarvis", "hey jarvis",
            "namaste", "salam", "namaskar", "jai hind"
        ), "HELLO")

        addCmd("BYE", listOf(
            "bye", "goodbye", "alvida", "phir milte hain",
            "bye jarvis", "goodbye jarvis", "chalta hoon", "nikalta hoon",
            "tata", "allah hafiz"
        ), "BYE")

        addCmd("THANKS", listOf(
            "thank you", "thanks", "shukriya", "dhanyawad",
            "thank you jarvis", "thanks jarvis", "shukriya jarvis",
            "bahut shukriya", "thanku", "thx"
        ), "THANKS")

        addCmd("WHO_ARE_YOU", listOf(
            "who are you", "kaun ho tum", "apna parichay do", "tum kya ho",
            "kaun ho jarvis", "aap kaun hain", "who is jarvis",
            "tum kaun ho", "tumhara naam kya hai", "jarvis kaun hai"
        ), "WHO_ARE_YOU")

        addCmd("WHAT_YOU_DO", listOf(
            "what can you do", "kya kar sakte ho", "tum kya kar sakte ho",
            "tumhari capability kya hai", "what are your features",
            "aap kya kar sakte hain", "capabilities batao", "kya kya kar sakta hai",
            "tum mein kya kya hai", "features batao"
        ), "WHAT_YOU_DO")

        addCmd("HOW_ARE_YOU", listOf(
            "how are you", "kaise ho", "kya haal hai", "kaise hain aap",
            "kya haal chal", "how r u", "sab theek", "kya chal raha hai",
            "kaisa hai", "aur kya chal raha hai"
        ), "HOW_ARE_YOU")

        // ===== VIDEO / MEDIA CONTROLS =====
        addCmd("VIDEO_PLAY", listOf(
            "play karo", "play", "chalao", "start video", "video play karo",
            "video chalao", "media play karo", "play kar do", "chal do",
            "play chal do", "video chal do", "chala do", "start karo video",
            "play karo video", "video ko play karo", "media chal do"
        ), "VIDEO_PLAY")

        addCmd("VIDEO_PAUSE", listOf(
            "pause karo", "pause", "rok do", "video rok do", "thahro",
            "video pause karo", "media pause karo", "ruko", "ruk jao",
            "stop karo video", "pause kar do", "thahar do", "ko rok do",
            "pause karo video", "video ko pause karo", "media rok do"
        ), "VIDEO_PAUSE")

        addCmd("VIDEO_STOP", listOf(
            "stop karo", "stop", "video stop karo", "band karo video",
            "media stop karo", "stop kar do", "band kar do", "video band karo",
            "ko band karo", "stop karo video", "band kar do video",
            "ab band karo", "video ko stop karo", "media band karo"
        ), "VIDEO_STOP")

        addCmd("VIDEO_NEXT", listOf(
            "next karo", "next", "agle par jao", "agle video par jao",
            "aage badho", "skip karo", "video next karo", "next video",
            "agle par chalo", "next track", "aage jao", "skip kar do",
            "agle video pe jao", "next par jao", "agle par badho"
        ), "VIDEO_NEXT")

        addCmd("VIDEO_PREVIOUS", listOf(
            "previous karo", "previous", "pichle par jao", "pichle video par jao",
            "peeche jao", "back karo", "video previous karo", "previous video",
            "pichle par chalo", "previous track", "peeche hato",
            "pichle video pe jao", "previous par jao", "pichle par badho"
        ), "VIDEO_PREVIOUS")

        addCmd("VIDEO_FULLSCREEN", listOf(
            "fullscreen karo", "full screen karo", "full screen", "video full screen karo",
            "poori screen karo", "full screen mode", "video ko fullscreen karo",
            "display full karo", "fullscreen mode", "big screen karo",
            "full screen kar do", "poori screen dikhao", "full screen chalao"
        ), "VIDEO_FULLSCREEN")

        addCmd("VIDEO_REWIND", listOf(
            "rewind karo", "rewind", "peeche le jao", "rewind kar do",
            "10 second peeche jao", "thoda peeche karo", "rewind karo thoda",
            "video wapas karo", "peeche karo", "rewind video"
        ), "VIDEO_REWIND")

        addCmd("VIDEO_FORWARD", listOf(
            "forward karo", "forward", "aage le jao", "forward kar do",
            "10 second aage jao", "thoda aage karo", "forward karo thoda",
            "video aage karo", "aage karo", "forward video",
            "skip forward", "agay barhao"
        ), "VIDEO_FORWARD")

        // ===== SCREEN READING =====
        addCmd("SCREEN_READ", listOf(
            "screen par kya hai", "screen read karo", "screen kya dikha rahi hai",
            "screen content batao", "kya dikh raha hai", "screen batao",
            "screen read", "kya likha hai screen par", "screen par kya likha hai",
            "screen info", "what is on screen", "read screen",
            "screen analysis", "screen analyze karo", "screen kaunsa app hai"
        ), "SCREEN_READ")

        addCmd("SCREEN_CHATS", listOf(
            "kaun si chats hain", "chats batao", "conversations batao",
            "kaun si baatcheet hain", "sari chats dikhao", "chat list batao",
            "chats dikhao", "conversation list", "kis kis se baat hui hai",
            "kis kis ki chat hai", "sare contacts dikhao", "chat names",
            "chat list", "kaun kaun hai", "message list"
        ), "SCREEN_CHATS")

        addCmd("SCREEN_BUTTONS", listOf(
            "button batao", "buttons dikhao", "kaun se buttons hain",
            "kitne buttons hain", "sare buttons batao", "button list",
            "kaun kaun se button hain", "buttons batao", "clickable elements",
            "kya kya dab sakta hai", "button names", "show buttons"
        ), "SCREEN_BUTTONS")

        addCmd("SCREEN_SEARCH_BAR", listOf(
            "search bar hai", "search bar batao", "kahan hai search bar",
            "search bar dikhao", "search box hai", "search bar show karo",
            "search field hai", "search bar location", "search bar dhundo"
        ), "SCREEN_SEARCH_BAR")

        // ===== CONTACT / NUMBER COMMANDS =====
        addCmd("CONTACT_LIST", listOf(
            "sare contacts dikhao", "contacts batao", "contact list",
            "saved contacts", "kis kis ka number hai", "contacts dikhao",
            "contact directory", "phone book dikhao", "contact list batao",
            "contacts show karo", "mere contacts", "jitne bhi contacts hain"
        ), "CONTACT_LIST")

        // ===== EXTRA WHATSAPP VARIATIONS =====
        addCmd("WHATSAPP_MSG", listOf(
            "whatsapp {contact} msg {message}", "whatsapp {contact} message {message}",
            "whatsapp {contact} send {message}", "whatsapp {contact} bhej {message}",
            "whatsapp {contact} likh {message}", "whatsapp {contact} bol {message}",
            "{contact} ko whatsapp msg {message}", "{contact} ko whatsapp message {message}",
            "{contact} ko whatsapp bhej {message}", "{contact} ko whatsapp likh {message}",
            "wa {contact} msg {message}", "wa {contact} message {message}",
            "whatsapp pe {contact} ko msg {message}", "whatsapp mein {contact} ko msg {message}"
        ), "WHATSAPP_MSG")

        addCmd("WHATSAPP_CALL", listOf(
            "whatsapp {contact} call", "whatsapp {contact} ko call",
            "{contact} ko whatsapp call", "wa {contact} call",
            "whatsapp mein {contact} ko call", "whatsapp pe {contact} call",
            "{contact} ko wa call", "whatsapp call {contact}",
            "wa pe {contact} ko call karo", "whatsapp par {contact} ko phone karo"
        ), "WHATSAPP_CALL")

        addCmd("WHATSAPP_VIDEO", listOf(
            "whatsapp {contact} video call", "whatsapp {contact} ko video call",
            "{contact} ko whatsapp video call", "wa {contact} video call",
            "whatsapp mein {contact} ko video call", "whatsapp pe {contact} video call",
            "{contact} ko wa video call", "whatsapp video call {contact}",
            "wa pe {contact} ko video call karo", "whatsapp par {contact} ko video karo"
        ), "WHATSAPP_VIDEO_CALL")

        addCmd("WHATSAPP_SEARCH", listOf(
            "whatsapp mein {contact} search karo", "whatsapp mein {contact} dhoondo",
            "whatsapp mein {contact} dhundo", "whatsapp mein {contact} khojo",
            "wa mein {contact} search", "whatsapp pe {contact} search karo",
            "{contact} ko wa mein dhundo", "{contact} ko whatsapp mein talash karo",
            "whatsapp mein {contact} find karo", "whatsapp se {contact} dhundo"
        ), "WHATSAPP_SEARCH")

        // ===== MORE APP VARIATIONS =====
        addCmd("YOUTUBE_OPEN", listOf(
            "youtube", "youtube kholo", "youtube open karo", "youtube chalao",
            "youtube start karo", "yt kholo", "yt open karo", "youtube khol do",
            "youtube launch karo", "youtube chal do", "youtube on karo",
            "yt khol do", "youtube par jao", "youtube khol"
        ), "YOUTUBE_OPEN")

        addCmd("YOUTUBE_SEARCH", listOf(
            "youtube {query} search karo", "youtube mein {query} dhoondo",
            "youtube pe {query} dhoondo", "yt {query} search karo",
            "youtube par {query} search", "youtube mein {query} search",
            "youtube {query} dikhao", "youtube pe {query} chalao"
        ), "YOUTUBE_SEARCH")

        addCmd("GOOGLE_SEARCH", listOf(
            "google {query} search karo", "google karo {query}",
            "search karo {query}", "google pe {query} dhoondo",
            "google {query}", "google kar {query}", "search {query}",
            "google mein {query} search karo", "web mein {query} search",
            "net par {query} dhoondo", "browser mein {query} search karo"
        ), "GOOGLE_SEARCH")

        addCmd("CONTACT_ADD", listOf(
            "{name} ko save karo {number}", "{name} ka number {number} save karo",
            "{number} save karo {name} ke naam", "{name} ka number yad rakho {number}",
            "{name} ka contact banao {number}", "{name} ko contacts mein dalo {number}"
        ), "CONTACT_SAVE")

        addCmd("CLIPBOARD_COPY_SCREEN", listOf(
            "screen copy karo", "screen ka text copy karo", "screen text copy karo",
            "jo dikh raha hai copy karo", "screen se text copy karo",
            "copy screen text", "jo likha hai copy karo", "screen par jo hai copy karo",
            "screen ka content copy karo"
        ), "CLIPBOARD_COPY")

        addCmd("CLIPBOARD_PASTE_TEXT", listOf(
            "paste karo", "text paste karo", "yahan paste karo",
            "clipboard paste karo", "copy ki hui cheez paste karo",
            "paste kar do", "yahan copy ki hui cheez dalo", "paste yahan karo",
            "text yahan dalo", "clipboard se paste karo"
        ), "CLIPBOARD_PASTE")

        addCmd("TYPE_TEXT", listOf(
            "type karo {text}", "likho {text}", "likh do {text}",
            "daalo {text}", "type kar do {text}", "type {text}",
            "yeh type karo {text}", "yeh likho {text}",
            "yahan {text} likho", "yahan {text} type karo"
        ), "TYPE_TEXT")

        addCmd("CLICK_TEXT", listOf(
            "{text} dabao", "{text} click karo", "{text} ko dabao",
            "{text} par click karo", "{text} par dabao", "{text} touch karo",
            "{text} select karo", "{text} open karo", "{text} kholo"
        ), "CLICK_TEXT")

        // ===== TIKTOK/SOCIAL =====
        addCmd("TIKTOK_LIKE", listOf(
            "like karo", "like kar do", "pasand aaya", "acha laga", "love it",
            "like kar", "is ko like karo", "yeh pasand aaya", "is video ko like karo",
            "video achi lagi", "like maro", "like do", "like kardo"
        ), "TIKTOK_LIKE")

        addCmd("TIKTOK_COMMENT", listOf(
            "comment karo {comment}", "comment likho {comment}", "reply karo {comment}",
            "comment kar do {comment}", "comment {comment}", "jawab do {comment}",
            "reply likho {comment}", "niche likho {comment}"
        ), "TIKTOK_COMMENT")

        addCmd("TIKTOK_SAVE", listOf(
            "save karo", "download karo", "rakh lo", "save kar lo",
            "video save karo", "is ko save karo", "download kar lo",
            "apne paas rakh lo", "save video", "save kar do"
        ), "TIKTOK_SAVE")

        addCmd("TIKTOK_SHARE", listOf(
            "share karo", "forward karo", "baant do", "share kar do",
            "kisi ko bhejo", "share kar", "forward kar do", "aage bhejo",
            "share kardo", "kisi aur ko bhejo"
        ), "TIKTOK_SHARE")

        addCmd("TIKTOK_FOLLOW", listOf(
            "follow karo", "follow kar do", "subscribe karo", "follow kar",
            "is ko follow karo", "follow maro", "follow kardo", "unfollow karo"
        ), "TIKTOK_FOLLOW")

        addCmd("INSTAGRAM_LIKE", listOf(
            "instagram like karo", "ig like karo", "is post ko like karo",
            "yeh pasand aaya like karo", "post achi lagi", "like kar instagram"
        ), "INSTAGRAM_LIKE")

        addCmd("INSTAGRAM_COMMENT", listOf(
            "instagram comment karo", "ig comment karo", "post par comment karo {comment}",
            "instagram reply karo {comment}", "ig pe comment likho {comment}"
        ), "INSTAGRAM_COMMENT")

        addCmd("INSTAGRAM_SAVE", listOf(
            "instagram save karo", "ig save karo", "post save karo",
            "bookmark karo", "post bookmark karo", "ig pe save karo"
        ), "INSTAGRAM_SAVE")

        // ===== EXTRA DEVICE =====
        addCmd("LOCK_SCREEN", listOf(
            "screen lock karo", "lock screen", "phone lock karo", "lock kar do",
            "screen band karo", "phone band karo", "lock karo", "mobile lock karo",
            "phone ko lock karo", "screen lock", "tala lagao", "phone tala lagao"
        ), "LOCK_SCREEN")

        addCmd("RECENT_APPS", listOf(
            "recent apps", "recent karo", "recent apps dikhao", "recent par jao",
            "app switcher kholo", "recent kholo", "recent mein jao",
            "pichle apps dikhao", "app list dikhao", "recent apps kholo"
        ), "RECENT_APPS")

        addCmd("NOTIFICATION", listOf(
            "notification kholo", "notifications dikhao", "notification bar kholo",
            "notifications", "notification panel", "notification center kholo",
            "upar se neeche karo", "notification karo", "notification check karo"
        ), "NOTIFICATION_PANEL")

        addCmd("QUICK_SETTINGS", listOf(
            "quick settings", "quick settings kholo", "quick panel dikhao",
            "settings panel kholo", "quick settings mein jao", "control center",
            "settings bar kholo", "quick toggle kholo"
        ), "QUICK_SETTINGS")

        addCmd("SPLIT_SCREEN", listOf(
            "split screen karo", "do apps kholo", "split screen mode",
            "aadha aadha screen karo", "two apps kholo", "split mode",
            "screen split karo", "do apps ek saath kholo", "half half screen"
        ), "SPLIT_SCREEN")

        addCmd(" POWER_DIALOG", listOf(
            "power button", "power dialog kholo", "shut down options",
            "power options", "phone band karne ke options", "restart options",
            "power menu", "power menu kholo", "shutdown dialog"
        ), "POWER_DIALOG")

        addCmd("SCREENSHOT", listOf(
            "screenshot lo", "screenshot le lo", "screenshot karo", "screen capture karo",
            "capture karo", "screenshot le", "screen shot lo", "photo le screen ka",
            "screen ka photo lo", "capture screen", "screenshot le do",
            "screenshot kardo", "screen photo", "screenshot lo please",
            "screenshot bhejo", "screenshot"
        ), "SCREENSHOT")

        addCmd("SCROLL_UP", listOf(
            "upar scroll karo", "scroll up", "upar jao", "oopar jao",
            "upar karo", "scroll up karo", "upar scroll", "thoda upar jao",
            "page up", "upar le jao", "scroll up kar do", "upar shift karo"
        ), "SCROLL_UP")

        addCmd("SCROLL_DOWN", listOf(
            "neeche scroll karo", "scroll down", "neeche jao", "scroll down karo",
            "thoda neeche jao", "page down", "neeche karo", "neeche scroll",
            "aur neeche jao", "scroll down kar do", "neeche shift karo",
            "neche lao", "aur dikhao"
        ), "SCROLL_DOWN")

        addCmd("BACK_BUTTON", listOf(
            "back karo", "wapas jao", "peeche jao", "back", "go back",
            "back jao", "peche hato", "vaapis jao", "pichli screen par jao",
            "ek step peeche", "back press karo", "back button dabao",
            "loat jao", "waps chale jao"
        ), "GO_BACK")

        addCmd("HOME_BUTTON", listOf(
            "home karo", "home jao", "home screen", "home button",
            "home par jao", "desktop dikhao", "home press karo",
            "home dabao", "ghar jao", "main screen par jao",
            "home screen par jao", "desktop par jao"
        ), "GO_HOME")
    }

    fun matchCommand(input: String): CommandMatch? {
        val lower = input.lowercase().trim()
        var bestMatch: CommandMatch? = null
        var bestScore = 0f

        for (entry in commands) {
            for (variation in entry.variations) {
                val match = matchVariation(lower, input, variation, entry)
                if (match != null && match.confidence > bestScore) {
                    bestScore = match.confidence
                    bestMatch = match
                }
            }
        }

        return bestMatch
    }

    private fun matchVariation(lower: String, original: String, variation: String, entry: CommandEntry): CommandMatch? {
        val varLower = variation.lowercase()
        val params = mutableMapOf<String, String>()

        // Check if variation has parameter placeholders
        if (varLower.contains("{") && varLower.contains("}")) {
            return matchParametric(lower, original, variation, entry, params)
        }

        // Direct literal match has highest confidence
        if (lower == varLower || lower.endsWith(varLower) || lower.startsWith(varLower)) {
            return CommandMatch(entry.action, params, 1.0f)
        }

        // Contains match
        if (lower.contains(varLower)) {
            return CommandMatch(entry.action, params, 0.9f)
        }

        // Fuzzy contains (word-by-word)
        val words = varLower.split(" ").filter { it.length > 2 }
        val matchedWords = words.count { lower.contains(it) }
        if (words.isNotEmpty() && matchedWords >= words.size * 0.7) {
            return CommandMatch(entry.action, params, 0.7f * matchedWords / words.size)
        }

        return null
    }

    private fun matchParametric(lower: String, original: String, variation: String, entry: CommandEntry, params: MutableMap<String, String>): CommandMatch? {
        val pattern = variation.lowercase().trim()
        val paramNames = mutableListOf<String>()

        // Replace {param} placeholder with regex capture group
        val regex = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '{') {
                val end = pattern.indexOf('}', i)
                if (end > i) {
                    val paramName = pattern.substring(i + 1, end)
                    paramNames.add(paramName)
                    regex.append("(.+)")
                    i = end + 1
                    continue
                }
            }
            // Escape special regex chars
            val c = pattern[i]
            if (c in ".+^$()[]{}|\\") {
                regex.append('\\')
            }
            regex.append(c)
            i++
        }

        val regexStr = regex.toString()
        val matchResult = Regex(regexStr, RegexOption.IGNORE_CASE).find(lower) ?: return null

        val groups = matchResult.groupValues.drop(1)
        for ((idx, name) in paramNames.withIndex()) {
            if (idx < groups.size) {
                params[name] = groups[idx].trim()
            }
        }

        if (paramNames.isEmpty()) return null
        if (params.values.any { it.isBlank() }) return null

        // Use custom param extractors if available
        if (entry.paramExtractors.isNotEmpty()) {
            for (extractor in entry.paramExtractors) {
                val result = extractor(original)
                if (result != null) {
                    params[result.first] = result.second
                }
            }
        }

        // Check for message param in WHATSAPP_MSG
        if (entry.action == "WHATSAPP_MSG") {
            val parts = params.toMap()
            val nameFromParams = parts["contact"] ?: ""
            if (nameFromParams.isNotEmpty()) {
                // Extract message after msg/bhej/likh/send keywords
                for (kw in listOf("msg", "message", "bhej", "likh", "bol", "send")) {
                    val idx = lower.indexOf(kw)
                    if (idx >= 0) {
                        val msgRaw = lower.substring(idx + kw.length).trim()
                        val msgClean = msgRaw.replace(Regex("\\b(karo|do|de|send|bhej|kar do|kar)\\b"), "").trim()
                        if (msgClean.isNotEmpty()) {
                            params["message"] = msgClean
                            break
                        }
                    }
                }
            }
        }

        return CommandMatch(entry.action, params, 0.95f)
    }

    fun getAllCommands(): List<CommandEntry> = commands

    companion object {
        fun extractParam(input: String, prefix: String, suffix: String): Pair<String, String>? {
            val lower = input.lowercase()
            val idx = lower.indexOf(prefix)
            if (idx < 0) return null
            var remaining = input.substring(idx + prefix.length).trim()
            suffix.split("|").forEach { s ->
                val sidx = remaining.lowercase().indexOf(s.trim())
                if (sidx >= 0) remaining = remaining.substring(0, sidx).trim()
            }
            return if (remaining.isNotBlank()) "value" to remaining else null
        }

        fun extractParamAfter(input: String, prefixes: List<String>): Pair<String, String>? {
            val lower = input.lowercase()
            for (prefix in prefixes) {
                val idx = lower.indexOf(prefix)
                if (idx >= 0) {
                    val extractedValue = input.substring(idx + prefix.length).trim()
                    if (extractedValue.isNotBlank()) return "contact" to extractedValue
                }
            }
            return null
        }

        fun extractParamBefore(input: String, suffixes: List<String>): Pair<String, String>? {
            val lower = input.lowercase()
            for (suffix in suffixes) {
                val idx = lower.indexOf(suffix)
                if (idx >= 0) {
                    val extractedValue = input.substring(0, idx).trim()
                    if (extractedValue.isNotBlank()) return "contact" to extractedValue
                }
            }
            return null
        }
    }
}
