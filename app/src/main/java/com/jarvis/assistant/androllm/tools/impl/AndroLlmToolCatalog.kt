package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.registry.ToolRegistry

/**
 * Every built-in AndroLLM tool (core/tools/tool/impl), adapted to Jarvis —
 * the replacement for AndroLLM's Hilt `ToolsModule` multibinding.
 *
 * Tools whose behaviour Jarvis already implements delegate to it (see each
 * class' KDoc): convert_units, convert_currency, open_navigation,
 * search_places, control_music, send_email, send_sms, vibrate and
 * read_notifications run Jarvis' androllm.tools.AndroLlmTools;
 * set_flashlight / set_volume(media) / set_brightness run Jarvis'
 * PhoneControlManager; launch_app / list_apps use Jarvis' AppIndex;
 * find_contacts uses ContactCaller; note_* use Jarvis' memory notes;
 * variable_* use Jarvis' AgentVariableStore; take_screenshot uses Jarvis'
 * accessibility service.
 */
object AndroLlmToolCatalog {

    /** Fresh instances of every built-in tool (registration order = ToolsModule order). */
    fun builtInTools(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(
            WeatherTool(),
            WebSearchTool(),
            SmsTool(app),
            PhoneTool(app),
            EmailTool(app),
            MapsTool(app),
            MapsSearchTool(app),
            CalendarTool(app),
            ReminderTool(app),
            AlarmTool(app),
            ClipboardTool(app),
            NotificationTool(app),
            FlashlightTool(app),
            BluetoothTool(app),
            WifiTool(app),
            MusicTool(app),
            AppLauncherTool(app),
            ContactTool(app),
            CameraTool(app),
            ScreenshotTool(app),
            ShareTool(app),

            // Workflow engine: model-visible variable read/write (loops, chaining)
            VariableSetTool(),
            VariableGetTool(),

            // Device & system
            BatteryTool(app),
            VolumeTool(app),
            DeviceInfoTool(app),
            PackageManagerTool(app),
            RunningAppsTool(app),
            GalleryTool(app),
            VibrateTool(app),
            BrightnessTool(app),
            ClockTool(app),
            LocationTool(app),

            // Compute & information
            CalculatorTool(),
            UnitConverterTool(app),
            CurrencyTool(app),
            TranslationTool(app),
            GitHubTool(),

            // Productivity
            NoteSaveTool(app),
            NoteListTool(app),
            NoteGetTool(app),
            NoteDeleteTool(app),
            ListDownloadsTool(app),
            ListAppFilesTool(app),
            PdfExportTool(app),
            MarkdownExportTool(app),
            VoiceRecorderTool(app)
        )
    }

    /**
     * Registers every built-in tool into [registry]. Returns the names the
     * registry rejected (already registered — e.g. a second call — or invalid).
     */
    fun register(registry: ToolRegistry, context: Context): List<String> =
        registry.registerAll(builtInTools(context))
}
