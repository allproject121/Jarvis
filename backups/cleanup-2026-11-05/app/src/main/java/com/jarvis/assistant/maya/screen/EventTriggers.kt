package com.jarvis.assistant.maya.screen

/**
 * Catalog of automatic phone-event announcements (Maya `set_event_trigger` / ev_* lines).
 * Pure Kotlin; [ScreenPrefs] stores the on/off state and [WatcherEngine] fires them.
 */
object EventTriggers {

    data class EventSpec(
        val id: String,
        val label: String,
        val lines: List<String>,
        val defaultOn: Boolean,
        val aliases: List<String>
    )

    val ALL: List<EventSpec> = listOf(
        EventSpec("battery_low", "Battery low", listOf("Battery kam hai, charge laga lo.", "Battery kam ho gayi hai, charger laga lo."), true,
            listOf("battery", "battery low", "low battery", "battery kam", "low")),
        EventSpec("battery_critical", "Battery critical", listOf("Battery bahut kam hai — abhi charger lagao, phone band ho sakta hai."), true,
            listOf("critical", "battery critical")),
        EventSpec("battery_full", "Battery full", listOf("Battery full ho gayi, charger nikaal do.", "Sau percent ho gaya, ab charger hata do."), true,
            listOf("full", "battery full", "100")),
        EventSpec("charger_in", "Charger plugged in", listOf("Charger lag gaya."), true,
            listOf("charger", "charging", "charger in", "plug", "plugged")),
        EventSpec("charger_out", "Charger unplugged", listOf("Charger hat gaya.", "Charger nikal gaya."), true,
            listOf("charger out", "unplug", "unplugged")),
        EventSpec("bluetooth_in", "Bluetooth device connected", listOf("Bluetooth device connect ho gaya."), true,
            listOf("bluetooth", "bt", "bluetooth in")),
        EventSpec("bluetooth_out", "Bluetooth device disconnected", listOf("Bluetooth device disconnect ho gaya."), true,
            listOf("bluetooth out")),
        EventSpec("wifi_in", "Wi-Fi connected", listOf("Wi-Fi connect ho gaya."), false,
            listOf("wifi", "wi-fi", "wifi in", "internet")),
        EventSpec("wifi_out", "Wi-Fi lost", listOf("Wi-Fi chala gaya, ab mobile data pe ho."), false,
            listOf("wifi out", "wifi lost")),
        EventSpec("headset_in", "Headphones plugged in", listOf("Headphone lag gaya.", "Headphone connect ho gaya."), true,
            listOf("headphone", "headphones", "headset", "earphone", "earphones", "headset in")),
        EventSpec("headset_out", "Headphones unplugged", listOf("Headphone nikal gaya.", "Headphone hat gaya."), true,
            listOf("headset out", "headphone out")),
        EventSpec("silent_on", "Phone put on silent", listOf("Phone silent pe chala gaya."), false,
            listOf("silent", "ringer", "silent on")),
        EventSpec("silent_off", "Ringer back on", listOf("Ringer wapas on ho gaya."), false,
            listOf("silent off", "ringer on")),
        EventSpec("airplane_on", "Airplane mode on", listOf("Airplane mode on ho gaya, ab network nahi milega."), true,
            listOf("airplane", "flight", "airplane on", "flight mode")),
        EventSpec("airplane_off", "Airplane mode off", listOf("Airplane mode band ho gaya."), true,
            listOf("airplane off", "flight off"))
    )

    private val ALL_WORDS = setOf("all", "sab", "sabhi", "saare", "everything", "every", "*")

    fun byId(id: String): EventSpec? = ALL.firstOrNull { it.id == id }

    fun defaultEnabled(id: String): Boolean = byId(id)?.defaultOn ?: false

    /**
     * Maps the user's words ("charging", "battery", "headphone wala", "sab") to event ids.
     * Exact ids win; a bare family word ("charger") selects both directions.
     */
    private val FAMILY_WORDS: Map<String, String> = mapOf(
        "battery" to "battery", "charger" to "charger", "charging" to "charger", "charge" to "charger",
        "bluetooth" to "bluetooth", "bt" to "bluetooth", "wifi" to "wifi", "wi fi" to "wifi", "internet" to "wifi",
        "headphone" to "headset", "headphones" to "headset", "headset" to "headset", "earphone" to "headset",
        "earphones" to "headset", "silent" to "silent", "ringer" to "silent", "airplane" to "airplane",
        "flight" to "airplane", "flight mode" to "airplane", "airplane mode" to "airplane"
    )

    fun resolve(words: String?): List<String> {
        val w = words?.trim()?.lowercase()?.replace('-', ' ')?.replace('_', ' ')?.replace(Regex("\\s+"), " ") ?: return emptyList()
        if (w.isEmpty()) return emptyList()
        if (ALL_WORDS.contains(w)) return ALL.map { it.id }
        val asId = w.replace(' ', '_')
        byId(asId)?.let { return listOf(it.id) }
        // bare family word → both directions of that family
        FAMILY_WORDS[w]?.let { fam -> return ALL.filter { it.id.startsWith(fam + "_") }.map { it.id } }
        // exact alias
        val direct = ALL.filter { spec -> spec.aliases.any { it == w } }
        if (direct.isNotEmpty()) return direct.map { it.id }.distinct()
        // fuzzy: family word somewhere in the phrase, direction from on/off words
        val fam = FAMILY_WORDS.entries.firstOrNull { (k, _) -> w.contains(k) }?.value
            ?: ALL.firstOrNull { spec -> spec.aliases.any { a -> w.contains(a) } }?.id?.substringBefore('_')
            ?: return emptyList()
        val family = ALL.filter { it.id.startsWith(fam + "_") }
        val wantsOff = listOf("off", "out", "nikal", "hat ", "hata", "disconnect", "unplug", "lost").any { w.contains(it) }
        val wantsOn = !wantsOff && listOf(" on", " in", "lag", "connect", "plug").any { w.contains(it) }
        val wantsLow = w.contains("low") || w.contains("kam")
        val wantsFull = w.contains("full") || w.contains("100")
        val wantsCritical = w.contains("critical")
        val picked = when {
            fam == "battery" && (wantsLow || wantsFull || wantsCritical) -> family.filter {
                (wantsLow && it.id == "battery_low") || (wantsFull && it.id == "battery_full") || (wantsCritical && it.id == "battery_critical")
            }
            wantsOff -> family.filter { it.id.endsWith("_off") || it.id.endsWith("_out") }
            wantsOn -> family.filter { it.id.endsWith("_on") || it.id.endsWith("_in") }
            else -> family
        }
        return picked.ifEmpty { family }.map { it.id }.distinct()
    }

    fun line(id: String, seed: Long = System.currentTimeMillis()): String {
        val spec = byId(id) ?: return id
        if (spec.lines.isEmpty()) return spec.label
        return spec.lines[((seed % spec.lines.size).toInt().let { if (it < 0) -it else it })]
    }
}
