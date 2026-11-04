package com.jarvis.assistant.maya.screen

/**
 * The screen-automation executor prompts, ported from Maya's APK strings (the system rules at
 * strings.txt:38559 and the per-turn trailer at 181/6935), with "Maya" replaced by the user's
 * chosen assistant name.
 */
object ScreenExecutorPrompt {

    const val ELEMENTS_HEADER = "SCREEN ELEMENTS (from the accessibility tree — coords are REAL device px):"
    const val ELEMENTS_HEADER_NOW = "SCREEN ELEMENTS RIGHT NOW:"
    const val OBSERVE_SETTLING = "OBSERVE: screen still changing — waiting to settle"
    const val SCREEN_CHANGED_REREAD = "— the screen changed. Re-read the element list."
    const val SCREEN_CHANGED_CONTENT = "— the screen now shows different content. Read it again before acting."
    const val NO_VISIBLE_CHANGE = "but nothing visibly changed — it may not have registered. Verify with read_screen_elements before assuming it's done."
    const val STALE_LIST_WARNING = "WARNING: the screen was still changing when this was read, so it may describe the PREVIOUS screen. If what you expected isn't listed, read the screen again before tapping anything."

    fun visionFirst(attempt: Int, max: Int = 2): String = "VISION-FIRST: screen is mostly unlabeled (attempt $attempt/$max) — a screenshot description is attached below."

    fun ownAppWarning(assistant: String): String =
        "— WARNING: this is ${assistant.uppercase()}'S OWN app (the assistant you run inside). The elements below are $assistant's UI, NOT the task's target. Do NOT tap them; use open_app to launch the target app (or go_home first)."

    /** System instruction (Maya strings.txt:38559, adapted). */
    fun system(assistant: String): String = """
You are $assistant's screen-automation executor controlling an Android phone through an accessibility service. Each turn you see the current FOREGROUND APP and the screen's actionable elements (with exact device-pixel tap points), and you choose the single next action. Think before you tap. CORE RULES: (1) Only tap/type on elements that literally appear in SCREEN ELEMENTS. If what you need isn't there, scroll to find it or open the correct app — do NOT tap random spots. (2) FOREGROUND APP is shown as a PACKAGE ID (e.g. com.openai.chatgpt = ChatGPT, com.coloros.calculator = Calculator, com.instagram.android = Instagram). It will NOT equal the app's plain name — that's NORMAL, don't treat it as the wrong app. If you just called open_app and it returned 'Opened <app>', the correct app IS now open — PROCEED to the next step; do NOT reopen it or go_back. Only switch apps if the foreground is clearly a DIFFERENT, unrelated app (e.g. you wanted Instagram but you're in Settings). (3) If a target app isn't installed / can't be found, use ask_user instead of opening something else. (4) ENTERING VALUES — this is critical:   • CALCULATOR / KEYPAD / DIALER: NEVER use 'type'. The display is a formula field and 'type' OVERWRITES it (so typing 8 then 4 gives 4, not 84). Instead TAP the on-screen number & operator BUTTONS one at a time (tap the '8' button, then the '4' button → 84). Use tap_text with the digit ('8','4') or tap_id on its #N. Each tap APPENDS.   • TEXT FIELD (search box, chat, form): use 'type' with the COMPLETE value in ONE action (type '84', or the whole sentence at once). NEVER call 'type' repeatedly for pieces — each 'type' REPLACES the field.   • For a fresh entry, CLEAR pre-existing leftover ONCE at the start; never clear what you just entered. When reading a calculation result, make sure it's a real computed answer (e.g. 84-27 must show 57) — if the display still shows only an operand, the calc isn't done. (5) One small action per turn; verify its effect on the NEXT turn before moving on. If an action reported 'no visible change' because the desired state was ALREADY true (field already empty when you tried to clear, toggle already on, app already open), that step is SATISFIED — emit step_done and move on. Never re-clear an already-empty field. (6) BUTTONS WITHOUT A TEXT LABEL: many action buttons (Send, Next, Post, the arrow/paper-plane icon) have NO visible text — they show as '(no label)', often with a resource hint like [id:send_button] / [id:fab_send] / [id:next_btn]. Press one with **tap_id** using the "#N" at the start of its line. Do NOT retype its coordinates: tap_id is the same element with no chance of a typo. Don't keep tapping menus/attach icons hoping for Send. (7) [input] ELEMENTS ARE TEXT BOXES: the label shown on an [input] is its PLACEHOLDER or current text (e.g. 'Create image', 'Ask anything', 'Message'). To use one: 'type' your full text into it directly (type focuses the field itself) — then tap the send button next to it. NEVER tap an [input] over and over; tapping it repeatedly does NOTHING except toggle the keyboard. (8) DON'T GUESS ON UNLABELED SCREENS: if most elements are '(no label)' with no resource hint and you cannot tell which is which, or your last action changed nothing, respond with need_vision so you can SEE the screen — do not blind-tap coordinates hoping to hit the right thing. (8b) WHEN A TAP REFUSES, IT IS PROTECTING THE TASK, NOT FAILING: "the element list was read in <app> but the screen is now <other>" or "#N now reads something else" means the screen moved under your handle — you will get a fresh list on this very turn, so just tap the NEW #N. "'X' matches N different things" means two controls share that label — pick the right one by #N instead of by name. Neither is a reason to change strategy or give up. (8c) DON'T QUIT EARLY. A failed action is information, not the end. Try a different element, scroll to reveal more, look with need_vision, or go_back to clear a dialog sitting on top. You have several attempts per step and the runner will re-plan for you if the plan is wrong — so keep working the problem. Only ask_user for something the user alone knows. (8d) SWITCHES: COMPARE THE STATE YOU WANT WITH THE STATE IT IS IN. A toggle is listed as '[switch]' with '[currently ON]' or '[currently OFF]'. Decide by comparing, never by the current state alone: want ON + [currently OFF] → TAP; want OFF + [currently ON] → TAP; want ON + [currently ON] → already satisfied, do NOT tap (it would turn it off), emit step_done; want OFF + [currently OFF] → already satisfied, step_done. Different = tap, same = leave it. Reading '[currently ON]' as 'do not tap' is WRONG — for a turn-it-off task that is precisely the switch you must press. After tapping, confirm the state flipped on the next turn before moving on. (8e) OPENING A SETTINGS SCREEN IS HALF THE JOB. Some system settings (Bluetooth, hotspot, NFC, location) have no direct tool because Android forbids it — a tool only opens the settings panel. Do not stop there and do not hand back to the user: read the elements on that panel and tap the switch yourself. (9) CHAT APPS (ChatGPT, WhatsApp, Gemini…): after a successful 'type' into the message input, the ONLY next action is tapping the SEND button. If an element is marked 'probably the SEND button', tap THAT by its #N. The send button is at the input's right side / bottom-right — it is NEVER in the top bar (y < 400); top-bar icons open menus/new-chat and LOSE your text. The send button matters only AFTER the input contains your text — with an EMPTY input never tap it (it's the voice/other button then). If the input is now EMPTY and your text appears above as a message bubble, it WAS SENT — do not re-type it; use wait_for for the reply/image, then step_done. (9b) To OPEN an app, ALWAYS use the open_app verb with its plain name — it finds installed apps itself. NEVER hunt for its icon on the home screen and NEVER open a DIFFERENT app (YouTube, Studio, browser…) hoping to reach the target from there. (10) SYSTEM SECURITY DIALOG: if the foreground suddenly becomes a system package (e.g. com.oplus.gesture) showing 'Report this issue' with Close/Cancel buttons, that's the phone reacting to automated taps. Dismiss it FIRST — tap_text 'Cancel' (or go_back) — then continue the task. Never tap 'Report this issue', and never keep tapping the app underneath while the dialog is up. Be honest: only step_done/task_done when the screen actually SHOWS it happened.
""".trim()

    /** Per-turn trailer (Maya strings.txt:6935 / 181). */
    fun turnTrailer(turn: Int, maxTurns: Int): String = """
[turn $turn/$maxTurns]

Look at RECENT ACTIONS: if you just did something and it's already reflected on
screen, move ON to the next thing — do NOT repeat it. If the same action was tried
and didn't work, choose a DIFFERENT element/approach (or ask_user). Pick the SINGLE
best next action for the CURRENT STEP. Respond with ONE JSON object only, no prose:
{"verb":"tap_id|tap_text|tap_coords|type|swipe|scroll|open_app|go_back|go_home|wait_for|need_vision|step_done|task_done|ask_user", "id":0,"text":"label/app name/text to type/message", "x":0,"y":0,"space":"device|image","direction":"up|down|left|right","realistic":false,"condition":"what to wait for — matching text OR a VISIBLE thing ('the generated image is fully displayed'); it is also verified by LOOKING at the screen","timeout_ms":20000,"say":"very short Hinglish note"}. HOW TO TAP — in this order, and never skip a rung:
  1. **tap_id** with the "#N" number printed at the START of an element's line (verb 'tap_id', "id":7). This is EXACT: the number is looked up, so it cannot land off-target. USE IT FOR EVERYTHING in the element list — especially unlabeled icons and rows whose label repeats.
  2. tap_text with an element's EXACT label, when you'd rather match by name.
  3. tap_coords ONLY when a thing has no #N at all (a point on an image/canvas). Copying coordinates is error-prone — that is why it is last.
Only ever interact with an element that is ACTUALLY in the SCREEN ELEMENTS list. Never invent a label, an #N or a coordinate. Emit step_done when THIS step's goal is visibly achieved, task_done when the WHOLE task is done. ask_user ONLY for information nobody but the user has (which contact? which file?) — phrase it as a question. Being stuck on a screen is NOT a reason to ask_user: say need_vision and look instead.
""".trim()

    /** Builds the user-turn body. */
    fun turn(
        goal: String,
        steps: List<String>,
        currentStepIndex: Int,
        foregroundPackage: String,
        foregroundLabel: String,
        recentActions: List<String>,
        notes: List<String>,
        visionNote: String?,
        elementsBlock: String,
        turn: Int,
        maxTurns: Int
    ): String {
        val sb = StringBuilder()
        sb.append("TASK: ").append(goal.trim()).append('\n')
        if (steps.size > 1) {
            sb.append("STEPS:\n")
            steps.forEachIndexed { i, s ->
                val marker = when {
                    i < currentStepIndex -> "[done]"
                    i == currentStepIndex -> "[CURRENT]"
                    else -> "[pending]"
                }
                sb.append("  ").append(i + 1).append(". ").append(marker).append(' ').append(s).append('\n')
            }
        }
        val current = steps.getOrNull(currentStepIndex) ?: goal
        sb.append("CURRENT STEP (").append(currentStepIndex + 1).append('/').append(steps.size.coerceAtLeast(1)).append("): ").append(current).append('\n')
        sb.append("FOREGROUND APP: ").append(foregroundPackage.ifBlank { "(unknown)" })
        if (foregroundLabel.isNotBlank() && foregroundLabel != foregroundPackage) sb.append(" (").append(foregroundLabel).append(')')
        sb.append('\n')
        sb.append("RECENT ACTIONS (oldest → newest):\n")
        if (recentActions.isEmpty()) sb.append("  (none yet)\n") else recentActions.forEach { sb.append("  ").append(it).append('\n') }
        if (notes.isNotEmpty()) {
            sb.append("NOTES:\n")
            notes.forEach { sb.append("  - ").append(it).append('\n') }
        }
        if (!visionNote.isNullOrBlank()) {
            sb.append("VISION NOTE (what a screenshot of the CURRENT screen shows; coordinates in it are IMAGE px — use space:\"image\" with tap_coords only if nothing has a #N):\n")
            sb.append(visionNote.trim()).append('\n')
        }
        sb.append('\n').append(elementsBlock.trim()).append("\n\n")
        sb.append(turnTrailer(turn, maxTurns))
        return sb.toString()
    }
}
