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
import androidx.compose.foundation.Image
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

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
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
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onSave(value) }, modifier = Modifier.widthIn(min = 56.dp)) { Text("Save") }
    }
}

@Composable
private fun TextSetting(label: String, initial: String, singleLine: Boolean = true, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = singleLine, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onSave(value) }, modifier = Modifier.widthIn(min = 56.dp)) { Text("Save") }
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
    Surface(modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), color = MaterialTheme.colorScheme.surfaceVariant) {
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Media Studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onBack) { Text("Close") }
            }
            Text("Image generation, music, YouTube & maps keys, commentary.", style = MaterialTheme.typography.bodySmall)
        }

        item {
            SectionCard("Image generation") {
                Text("Provider", style = MaterialTheme.typography.labelLarge)
                ChipFlow {
                    listOf(
                        MediaPrefs.PROVIDER_POLLINATIONS to "Pollinations",
                        MediaPrefs.PROVIDER_GEMINI_IMAGE to "Nano Banana",
                        MediaPrefs.PROVIDER_IMAGEN to "Imagen",
                        MediaPrefs.PROVIDER_OPENAI to "OpenAI"
                    ).forEach { (id, label) ->
                        FilterChip(selected = provider == id, onClick = { provider = id; prefs.imageProvider = id }, label = { Text(label) })
                    }
                }
                Text(
                    when (provider) {
                        MediaPrefs.PROVIDER_POLLINATIONS -> "Free, no key. Optional token raises rate limits."
                        MediaPrefs.PROVIDER_GEMINI_IMAGE -> "Uses your Gemini key (Settings → API). Model below; blank = ${MediaPrefs.DEFAULT_GEMINI_IMAGE_MODEL}."
                        MediaPrefs.PROVIDER_IMAGEN -> "Same Gemini key. Higher quality than Nano Banana, paid tier only — falls back automatically."
                        else -> "OpenAI images (b64). Uses the key below or your OpenAI chat key."
                    }, style = MaterialTheme.typography.bodySmall
                )
                Text("Image size", style = MaterialTheme.typography.labelLarge)
                ChipFlow {
                    listOf(
                        "square" to "Square",
                        "portrait" to "Portrait",
                        "landscape" to "Landscape",
                        "instagram_post" to "Post 4:5",
                        "instagram_story" to "Story / Reel 9:16"
                    ).forEach { (s, label) ->
                        FilterChip(
                            selected = ImageSize.parse(size) == ImageSize.parse(s),
                            onClick = { size = s; prefs.imageSize = s },
                            label = { Text(label) }
                        )
                    }
                }
                val dims = ImageSize.parse(size)
                Text("Output: ${dims.width} x ${dims.height} px (every provider is cropped/scaled to this size).", style = MaterialTheme.typography.bodySmall)
                SecretField("Pollinations token (optional)", prefs.pollinationsToken) { prefs.pollinationsToken = it; toast(context, "Saved") }
                SecretField("OpenAI image API key", prefs.openAiImageKey) { prefs.openAiImageKey = it; toast(context, "Saved") }
                TextSetting("Gemini image model", if (provider == MediaPrefs.PROVIDER_IMAGEN) prefs.imagenModel else prefs.geminiImageModel) {
                    if (provider == MediaPrefs.PROVIDER_IMAGEN) prefs.imagenModel = it else prefs.geminiImageModel = it
                    toast(context, "Saved")
                }
                TextSetting("OpenAI image model (e.g. gpt-image-1)", prefs.openAiImageModel) { prefs.openAiImageModel = it; toast(context, "Saved") }
                HorizontalDivider()
                OutlinedTextField(value = testPrompt, onValueChange = { testPrompt = it }, label = { Text("Test prompt") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                ButtonFlow {
                    Button(enabled = !generating, onClick = {
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
                    OutlinedButton(enabled = !generating, onClick = {
                        generating = true
                        scope.launch {
                            val (bmp, saved) = QuoteImageRenderer.renderAndSave(context, testPrompt, null, null, ImageSize.parse(size, ImageSize.STORY), false)
                            bmp.recycle()
                            gallery = prefs.recentImages()
                            status = if (saved != null) "Quote poster saved." else "Could not save poster."
                            generating = false
                        }
                    }) { Text("Quote poster") }
                    if (generating) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(22.dp), strokeWidth = 2.dp)
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                if (gallery.isNotEmpty()) {
                    Text("Generated images", style = MaterialTheme.typography.labelLarge)
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
            SectionCard("Music") {
                Text("Default music app", style = MaterialTheme.typography.labelLarge)
                val installed = remember { MusicPlayback.installedApps(context).map { it.id }.toSet() }
                ChipFlow {
                    MusicPlayback.APPS.forEach { app ->
                        FilterChip(
                            selected = musicApp == app.id,
                            onClick = { musicApp = app.id; prefs.defaultMusicApp = app.id },
                            label = { Text(app.label + if (app.id in installed) "" else " (n/a)") }
                        )
                    }
                }
                val recent = remember { prefs.recentSongs().take(5) }
                if (recent.isNotEmpty()) Text("Recent: " + recent.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SectionCard("YouTube & Maps keys") {
                SecretField("YouTube Data API v3 key", prefs.youtubeApiKey) { prefs.youtubeApiKey = it; toast(context, "Saved") }
                Text("Used by youtube_channel_stats / video_stats / recent_videos / thumbnail. Falls back to the key in Jarvis' YouTube settings.", style = MaterialTheme.typography.bodySmall)
                SecretField("Google Maps / Places API key", prefs.mapsApiKey) { prefs.mapsApiKey = it; toast(context, "Saved") }
                Text("Lets search_nearby read out results; without it a map search opens.", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SectionCard("Commentary mode") {
                Text("Live esports commentary on your shared screen. Start the live session + screen share, then say \"commentary shuru karo\".", style = MaterialTheme.typography.bodySmall)
                TextSetting("Default game", prefs.commentaryGame) { prefs.commentaryGame = it; toast(context, "Saved") }
                ChipFlow {
                    (1..4).forEach { f -> FilterChip(selected = fps == f, onClick = { fps = f; prefs.commentaryFps = f }, label = { Text("$f fps") }) }
                }
                ButtonFlow {
                    Button(onClick = {
                        val (okk, msg) = CommentaryMode.start(context, null); commentaryOn = CommentaryMode.isOn; toast(context, msg)
                        if (!okk) status = msg
                    }) { Text("Start") }
                    OutlinedButton(onClick = { toast(context, CommentaryMode.stop()); commentaryOn = false }) { Text("Stop") }
                }
                Text(if (commentaryOn) "Commentary is running." else "Commentary is off.", style = MaterialTheme.typography.bodySmall)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
