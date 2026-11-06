package com.jarvis.assistant.maya.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media Studio: settings + playground for the Maya media pack (image generation, WhatsApp
 * auto-reply & support mode, music, YouTube API key, Maps key, commentary).
 */
class MediaStudioActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, MediaStudioActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { ThemeManager.applyTheme(this) }
        super.onCreate(savedInstanceState)
        setContent {
            AndroLLMTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MediaStudioScreen(onBack = { finish() })
                }
            }
        }
    }
}

/**
 * Dark Bento tokens (2026-11-05) read from the active Jarvis theme, so Media Studio wears the
 * same contrast-audited palette as the XML screens on every theme (Nova, Pearl White, Stealth, ...).
 */
private data class Bento(
    val cardTop: Color, val cardBase: Color, val border: Color,
    val plate: Color, val plateBorder: Color,
    val glow1: Color, val glow2: Color,
    val text: Color, val text2: Color, val muted: Color,
    val accent: Color, val accentFill: Color, val onAccent: Color, val accentSoft: Color,
    val success: Color
)

private val LocalBento = staticCompositionLocalOf<Bento?> { null }

@Composable
private fun bento(): Bento {
    val provided = LocalBento.current
    val ctx = LocalContext.current
    val computed = remember(ctx) {
        fun c(attr: Int, fb: Number) = Color(ThemeManager.attrColor(ctx, attr, fb.toInt()))
        Bento(
            cardTop = c(R.attr.themeCardGlassStart, 0xF21B1733), cardBase = c(R.attr.themeCardGlassEnd, 0xF2110E21),
            border = c(R.attr.themeCardBorder, 0x17FFFFFF),
            plate = c(R.attr.themeChipBg, 0xFF17152E), plateBorder = c(R.attr.themePlateBorder, 0x1FFFFFFF),
            glow1 = c(R.attr.themeGlow1, 0x737F7CF3), glow2 = c(R.attr.themeGlow2, 0x59BC694A),
            text = c(R.attr.themeTextPrimary, 0xFFF9F9FA), text2 = c(R.attr.themeTextSecondary, 0xFFBDBBCB),
            muted = c(R.attr.themeTextMuted, 0xFFA1A0AE),
            accent = c(R.attr.themeAccent, 0xFFA194FF), accentFill = c(R.attr.themeAccentFill, 0xFF6352F5),
            onAccent = c(R.attr.themeOnAccent, 0xFFFFFFFF), accentSoft = c(R.attr.themeAccentLight, 0x2E6D5DFC),
            success = c(R.attr.themeSuccess, 0xFF4ADE80)
        )
    }
    return provided ?: computed
}

/** Bento card: lighter top edge, soft corner glow, 1dp hairline, 24dp radius. */
@Composable
private fun BentoCard(warmGlow: Boolean = false, glowLeft: Boolean = false, content: @Composable () -> Unit) {
    val b = bento()
    val shape = RoundedCornerShape(24.dp)
    val glow = if (warmGlow) b.glow2 else b.glow1
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(b.cardTop, b.cardBase)))
            .drawBehind {
                val r = maxOf(size.minDimension * 1.2f, 1f)
                drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(if (glowLeft) 0f else size.width, 0f), radius = r))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), startY = 0f, endY = 64.dp.toPx()))
            }
            .border(1.dp, b.border, shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides b.text2) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

/** Small rounded plate that holds an icon glyph (bento icon plate). */
@Composable
private fun IconPlate(glyph: String) {
    val b = bento()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(42.dp).clip(shape).background(b.accentSoft).border(1.dp, b.plateBorder, shape),
        contentAlignment = Alignment.Center
    ) { Text(glyph, fontSize = 19.sp) }
}

@Composable
private fun SectionCard(
    title: String,
    icon: String = "",
    subtitle: String = "",
    warmGlow: Boolean = false,
    glowLeft: Boolean = false,
    content: @Composable () -> Unit
) {
    val b = bento()
    BentoCard(warmGlow = warmGlow, glowLeft = glowLeft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon.isNotEmpty()) {
                IconPlate(icon)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = b.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, color = b.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        content()
    }
}

/** Field label inside a bento card. */
@Composable
private fun Label(text: String) {
    Text(text, color = bento().text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

/** Muted helper text inside a bento card (>= 4.5:1 on cards in every theme). */
@Composable
private fun Hint(text: String) {
    Text(text, color = bento().muted, style = MaterialTheme.typography.bodySmall)
}

/** Pill badge with a coloured dot, e.g. "● Maya media pack". */
@Composable
private fun DotBadge(text: String, dot: Color) {
    val b = bento()
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(b.plate).border(1.dp, b.plateBorder, shape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text, color = b.text2, style = MaterialTheme.typography.labelMedium)
    }
}

/** Selectable pill: plate + hairline when idle, accent fill + on-accent ink when selected. */
@Composable
private fun BentoChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    val b = bento()
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(if (selected) b.accentFill else b.plate)
            .border(1.dp, if (selected) b.accentFill else b.plateBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) b.onAccent else b.text) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

/** Primary button: saturated accent fill, label in the theme's on-accent ink. */
@Composable
private fun BentoPrimary(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val b = bento()
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = b.accentFill, contentColor = b.onAccent,
            disabledContainerColor = b.plate, disabledContentColor = b.muted
        ),
        content = content
    )
}

/** Secondary / ghost button: plate fill + hairline + light label. */
@Composable
private fun BentoSecondary(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val b = bento()
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, b.plateBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = b.plate, contentColor = b.text,
            disabledContainerColor = b.plate, disabledContentColor = b.muted
        ),
        content = content
    )
}

@Composable
private fun bentoFieldColors() = bento().let { b ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = b.text, unfocusedTextColor = b.text,
        focusedContainerColor = b.plate, unfocusedContainerColor = b.plate,
        focusedBorderColor = b.accent, unfocusedBorderColor = b.plateBorder,
        focusedLabelColor = b.accent, unfocusedLabelColor = b.muted,
        cursorColor = b.accent
    )
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick, modifier = Modifier.widthIn(min = 56.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = bento().accent)
    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
}

/** Chips that wrap onto the next line instead of running off a 411 dp-wide screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { content() }
}

/** Buttons that wrap when the row is too narrow (e.g. large font scale). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val b = bento()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = b.text, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = b.onAccent, checkedTrackColor = b.accentFill, checkedBorderColor = b.accentFill,
                uncheckedThumbColor = b.muted, uncheckedTrackColor = b.plate, uncheckedBorderColor = b.plateBorder
            )
        )
    }
}

@Composable
private fun SecretField(label: String, initial: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(14.dp), colors = bentoFieldColors(),
            modifier = Modifier.weight(1f)
        )
        SaveButton { onSave(value) }
    }
}

@Composable
private fun TextSetting(label: String, initial: String, singleLine: Boolean = true, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = singleLine, shape = RoundedCornerShape(14.dp), colors = bentoFieldColors(),
            modifier = Modifier.weight(1f)
        )
        SaveButton { onSave(value) }
    }
}

private fun loadThumb(context: Context, ref: String): Bitmap? = try {
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    if (ref.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(ref))?.use { BitmapFactory.decodeStream(it, null, opts) }
    else BitmapFactory.decodeFile(ref, opts)
} catch (e: Exception) {
    null
}

@Composable
private fun Thumb(ref: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, ref) { value = withContext(Dispatchers.IO) { loadThumb(context, ref) } }
    val b = bento()
    val shape = RoundedCornerShape(16.dp)
    Surface(modifier = Modifier.size(88.dp).clip(shape).border(1.dp, b.plateBorder, shape).clickable(onClick = onClick), color = b.plate) {
        bmp?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

@Composable
private fun MediaStudioScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { MediaPrefs.get(context) }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(prefs.imageProvider) }
    var size by remember { mutableStateOf(prefs.imageSize) }
    var testPrompt by remember { mutableStateOf("a cozy tea stall in Mumbai rain at night, cinematic") }
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var gallery by remember { mutableStateOf(prefs.recentImages()) }


    var musicApp by remember { mutableStateOf(prefs.defaultMusicApp) }
    var fps by remember { mutableStateOf(prefs.commentaryFps) }
    var commentaryOn by remember { mutableStateOf(CommentaryMode.isOn) }

    val b = bento()
    CompositionLocalProvider(LocalBento provides b) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DotBadge("Maya media pack", b.success)
                    Spacer(Modifier.weight(1f))
                    BentoSecondary(onClick = onBack) { Text("Close") }
                }
                Text(
                    "Media Studio", color = b.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("Image generation, music, YouTube & maps keys, commentary.", color = b.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            SectionCard("Image generation", icon = "\uD83C\uDFA8", subtitle = "Providers, sizes and a live test") {
                Label("Provider")
                ChipFlow {
                    listOf(
                        MediaPrefs.PROVIDER_POLLINATIONS to "Pollinations",
                        MediaPrefs.PROVIDER_GEMINI_IMAGE to "Nano Banana",
                        MediaPrefs.PROVIDER_IMAGEN to "Imagen",
                        MediaPrefs.PROVIDER_OPENAI to "OpenAI"
                    ).forEach { (id, label) ->
                        BentoChip(selected = provider == id, onClick = { provider = id; prefs.imageProvider = id }, label = { Text(label) })
                    }
                }
                Text(
                    when (provider) {
                        MediaPrefs.PROVIDER_POLLINATIONS -> "Free, no key. Optional token raises rate limits."
                        MediaPrefs.PROVIDER_GEMINI_IMAGE -> "Uses your Gemini key (Settings → API). Model below; blank = ${MediaPrefs.DEFAULT_GEMINI_IMAGE_MODEL}."
                        MediaPrefs.PROVIDER_IMAGEN -> "Same Gemini key. Higher quality than Nano Banana, paid tier only — falls back automatically."
                        else -> "OpenAI images (b64). Uses the key below or your OpenAI chat key."
                    }, color = b.muted, style = MaterialTheme.typography.bodySmall
                )
                Label("Image size")
                ChipFlow {
                    listOf(
                        "square" to "Square",
                        "portrait" to "Portrait",
                        "landscape" to "Landscape",
                        "instagram_post" to "Post 4:5",
                        "instagram_story" to "Story / Reel 9:16"
                    ).forEach { (s, label) ->
                        BentoChip(
                            selected = ImageSize.parse(size) == ImageSize.parse(s),
                            onClick = { size = s; prefs.imageSize = s },
                            label = { Text(label) }
                        )
                    }
                }
                val dims = ImageSize.parse(size)
                Text("Output: ${dims.width} x ${dims.height} px (every provider is cropped/scaled to this size).", color = b.muted, style = MaterialTheme.typography.bodySmall)
                SecretField("Pollinations token (optional)", prefs.pollinationsToken) { prefs.pollinationsToken = it; toast(context, "Saved") }
                SecretField("OpenAI image API key", prefs.openAiImageKey) { prefs.openAiImageKey = it; toast(context, "Saved") }
                TextSetting("Gemini image model", if (provider == MediaPrefs.PROVIDER_IMAGEN) prefs.imagenModel else prefs.geminiImageModel) {
                    if (provider == MediaPrefs.PROVIDER_IMAGEN) prefs.imagenModel = it else prefs.geminiImageModel = it
                    toast(context, "Saved")
                }
                TextSetting("OpenAI image model (e.g. gpt-image-1)", prefs.openAiImageModel) { prefs.openAiImageModel = it; toast(context, "Saved") }
                HorizontalDivider(color = b.border)
                OutlinedTextField(value = testPrompt, onValueChange = { testPrompt = it }, label = { Text("Test prompt") }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(14.dp), colors = bentoFieldColors(), modifier = Modifier.fillMaxWidth())
                ButtonFlow {
                    BentoPrimary(enabled = !generating, onClick = {
                        generating = true; status = "Generating…"
                        scope.launch {
                            status = try {
                                val r = ImageGen.generate(context, testPrompt)
                                gallery = prefs.recentImages()
                                "Done via ${r.provider} (${r.width}x${r.height}). ${r.note}"
                            } catch (e: Exception) {
                                "Failed: ${e.message}"
                            }
                            generating = false
                        }
                    }) { Text("Generate") }
                    BentoSecondary(enabled = !generating, onClick = {
                        generating = true
                        scope.launch {
                            val (bmp, saved) = QuoteImageRenderer.renderAndSave(context, testPrompt, null, null, ImageSize.parse(size, ImageSize.STORY), false)
                            bmp.recycle()
                            gallery = prefs.recentImages()
                            status = if (saved != null) "Quote poster saved." else "Could not save poster."
                            generating = false
                        }
                    }) { Text("Quote poster") }
                    if (generating) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(22.dp), strokeWidth = 2.dp, color = b.accent)
                }
                if (status.isNotBlank()) Text(status, color = b.muted, style = MaterialTheme.typography.bodySmall)
                if (gallery.isNotEmpty()) {
                    Label("Generated images")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(gallery) { ref ->
                            Thumb(ref) {
                                MediaFiles.resolveToUri(context, ref)?.let { (u, m) -> MediaFiles.openViewer(context, u, m) }
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Music", icon = "\uD83C\uDFB5", subtitle = "Where songs open by default", warmGlow = true, glowLeft = true) {
                Label("Default music app")
                val installed = remember { MusicPlayback.installedApps(context).map { it.id }.toSet() }
                ChipFlow {
                    MusicPlayback.APPS.forEach { app ->
                        BentoChip(
                            selected = musicApp == app.id,
                            onClick = { musicApp = app.id; prefs.defaultMusicApp = app.id },
                            label = { Text(app.label + if (app.id in installed) "" else " (n/a)") }
                        )
                    }
                }
                val recent = remember { prefs.recentSongs().take(5) }
                if (recent.isNotEmpty()) Text("Recent: " + recent.joinToString(", "), color = b.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SectionCard("YouTube & Maps keys", icon = "\uD83D\uDD11", subtitle = "Optional API keys", glowLeft = true) {
                SecretField("YouTube Data API v3 key", prefs.youtubeApiKey) { prefs.youtubeApiKey = it; toast(context, "Saved") }
                Text("Used by youtube_channel_stats / video_stats / recent_videos / thumbnail. Falls back to the key in Jarvis' YouTube settings.", color = b.muted, style = MaterialTheme.typography.bodySmall)
                SecretField("Google Maps / Places API key", prefs.mapsApiKey) { prefs.mapsApiKey = it; toast(context, "Saved") }
                Text("Lets search_nearby read out results; without it a map search opens.", color = b.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SectionCard("Commentary mode", icon = "\uD83C\uDF99\uFE0F", subtitle = "Live esports commentary", warmGlow = true) {
                Text("Live esports commentary on your shared screen. Start the live session + screen share, then say \"commentary shuru karo\".", color = b.muted, style = MaterialTheme.typography.bodySmall)
                TextSetting("Default game", prefs.commentaryGame) { prefs.commentaryGame = it; toast(context, "Saved") }
                ChipFlow {
                    (1..4).forEach { f -> BentoChip(selected = fps == f, onClick = { fps = f; prefs.commentaryFps = f }, label = { Text("$f fps") }) }
                }
                ButtonFlow {
                    BentoPrimary(onClick = {
                        val (okk, msg) = CommentaryMode.start(context, null); commentaryOn = CommentaryMode.isOn; toast(context, msg)
                        if (!okk) status = msg
                    }) { Text("Start") }
                    BentoSecondary(onClick = { toast(context, CommentaryMode.stop()); commentaryOn = false }) { Text("Stop") }
                }
                Text(if (commentaryOn) "Commentary is running." else "Commentary is off.", color = b.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
}
