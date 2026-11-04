package com.jarvis.assistant.maya.guardian

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.security.guardian.TouchGuardManager
import com.jarvis.assistant.security.guardian.WatchmanSecurityManager
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Maya "Settings → Advanced → Faces" for Jarvis: Faces, Face Lock & more, Watchman,
 * Touch Guard incidents, Listen mode, God Mode, Device admin.
 */
class FacesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ENROLL_NAME = "enroll_name"
        const val EXTRA_ENROLL_ROLE = "enroll_role"
    }

    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val enrollName = intent?.getStringExtra(EXTRA_ENROLL_NAME)
        val enrollRole = intent?.getStringExtra(EXTRA_ENROLL_ROLE)
        setContent {
            AndroLLMTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FacesScreen(this@FacesActivity, refresh.intValue, { refresh.intValue++ }, enrollName, enrollRole)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }
}

private fun fmtTime(ms: Long): String = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(ms))

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Thumb(path: String, sizeDp: Int = 72) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Exception) { null }
        }
    }
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("—")
    }
}

@Composable
private fun RadioRow(label: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FacesScreen(
    activity: FacesActivity,
    refreshKey: Int,
    onChanged: () -> Unit,
    initialEnrollName: String?,
    initialEnrollRole: String?
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { GuardianPrefs(ctx) }
    val store = remember { FaceStore(ctx.filesDir) }

    val people = remember(refreshKey) { store.listPeople() }
    val intruders = remember(refreshKey) { WatchmanSecurityManager(ctx).listIntruderPhotos() }
    val incidents = remember(refreshKey) { TouchGuardIncidents.of(ctx).list() }
    val voices = remember(refreshKey) { try { JarvisApp.instance.voiceGuardian.listVoices() } catch (_: Throwable) { emptyList() } }
    val gallery = remember(refreshKey) { store.readGalleryIndex() }

    var faceLock by remember(refreshKey) { mutableStateOf(prefs.faceLockEnabled) }
    var threshold by remember(refreshKey) { mutableFloatStateOf(prefs.faceThreshold) }
    var watchMode by remember(refreshKey) { mutableStateOf(prefs.watchmanMode) }
    var listenMode by remember(refreshKey) { mutableStateOf(ListenModeController.mode(ctx)) }
    var godMode by remember(refreshKey) { mutableStateOf(prefs.godMode) }
    var adminActive by remember(refreshKey) { mutableStateOf(DevicePolicyAdmin.isActive(ctx)) }

    var showEnroll by remember { mutableStateOf(initialEnrollName != null) }
    var forgetTarget by remember { mutableStateOf<FacePerson?>(null) }
    var selectedPerson by remember { mutableStateOf<String?>(null) }

    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showEnroll = true else Toast.makeText(ctx, "Camera permission is needed to save a face", Toast.LENGTH_LONG).show()
    }
    val mediaPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            GalleryFaceIndexer.enqueue(ctx)
            Toast.makeText(ctx, "Scanning your newest ${GalleryFaceIndexer.LIMIT} photos in the background…", Toast.LENGTH_LONG).show()
        } else Toast.makeText(ctx, "Photos permission denied", Toast.LENGTH_SHORT).show()
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        adminActive = DevicePolicyAdmin.isActive(ctx)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Faces & Guardian", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "A face print is a list of numbers, not a photo. It stays on this phone — never uploaded, synced or backed up. " +
                        "Recognition is a basic on-device matcher (ML Kit finds the face, an LBP/texture print compares it) — not a neural face model.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------------- Faces
        item {
            SectionCard("Faces", "People Jarvis can recognise. Save yourself first as Boss.") {
                if (people.isEmpty()) Text("No faces saved yet.", style = MaterialTheme.typography.bodyMedium)
                people.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { person ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { forgetTarget = person }.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val sample = person.samplePaths.lastOrNull()
                                if (sample != null) Thumb(sample, 64) else Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                Text(person.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${person.role} · ${person.vectors.size}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (FaceCamera.hasCameraPermission(ctx)) showEnroll = true else cameraPerm.launch(Manifest.permission.CAMERA)
                    }) { Text("Add person") }
                }
                Text("Tap a person to forget them.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------------- Face Lock & more
        item {
            SectionCard("Face Lock & more", "When the Jarvis app lock asks, your face is tried for 5 s before the phone's fingerprint/PIN. Convenience only — a photo of you may fool it.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Face Lock", Modifier.weight(1f))
                    Switch(checked = faceLock, onCheckedChange = { on ->
                        if (on && store.owners().none { it.vectors.isNotEmpty() }) {
                            Toast.makeText(ctx, "Save your own face as Boss first", Toast.LENGTH_LONG).show()
                        } else {
                            prefs.faceLockEnabled = on
                            faceLock = on
                        }
                    })
                }
                Text("Match threshold: ${"%.2f".format(threshold)} (higher = stricter)", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { prefs.faceThreshold = threshold },
                    valueRange = FaceMatcher.MIN_THRESHOLD..FaceMatcher.MAX_THRESHOLD
                )
                val last = prefs.faceLockLastAttemptAt
                Text(
                    "Failed face checks in a row: ${prefs.faceLockFailedAttempts}" + if (last > 0) " · last attempt ${fmtTime(last)}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Find people in your photos", fontWeight = FontWeight.SemiBold)
                Text(
                    if (gallery.scannedAt > 0) "Indexed ${gallery.scannedCount} photos on ${fmtTime(gallery.scannedAt)} · ${gallery.entries.size} with faces"
                    else "Not scanned yet. Scans your newest ${GalleryFaceIndexer.LIMIT} photos on the phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = {
                        if (GalleryFaceIndexer.hasPermission(ctx)) {
                            GalleryFaceIndexer.enqueue(ctx)
                            Toast.makeText(ctx, "Scanning in the background…", Toast.LENGTH_SHORT).show()
                        } else mediaPerm.launch(GalleryFaceIndexer.mediaPermission())
                    }) { Text("Scan photos") }
                }
                if (gallery.scannedAt > 0 && people.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        items(people) { person ->
                            FilterChip(
                                selected = selectedPerson == person.name,
                                onClick = { selectedPerson = if (selectedPerson == person.name) null else person.name },
                                label = { Text("${person.name} (${store.photosOf(person.name).size})") }
                            )
                        }
                    }
                    val sel = selectedPerson
                    if (sel != null) {
                        val photos = store.photosOf(sel).take(30)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            items(photos) { e ->
                                Box(Modifier.clickable {
                                    try {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), "image/*")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    } catch (_: Exception) {}
                                }) { UriThumb(e.uri) }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- Watchman
        item {
            SectionCard("Watchman", "When the phone is moved: take a front-camera photo; Boss/family faces stand it down, unknown faces are saved here.") {
                listOf(
                    GuardianPrefs.WATCHMAN_OFF to "Off",
                    GuardianPrefs.WATCHMAN_PHOTO to "Photo (silent)",
                    GuardianPrefs.WATCHMAN_PHOTO_SIREN to "Photo + siren"
                ).forEach { (key, label) ->
                    RadioRow(label, null, watchMode == key) {
                        if (key == GuardianPrefs.WATCHMAN_OFF && godMode) {
                            Toast.makeText(ctx, "God Mode is on — switch God Mode off first", Toast.LENGTH_LONG).show()
                            return@RadioRow
                        }
                        prefs.watchmanMode = key
                        watchMode = key
                        if (TouchGuardManager.isArmed()) TouchGuardManager.disarm(activity)
                        if (key != GuardianPrefs.WATCHMAN_OFF) TouchGuardManager.arm(activity, stealth = key == GuardianPrefs.WATCHMAN_PHOTO)
                        onChanged()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Who tried to get in (${intruders.size})", fontWeight = FontWeight.SemiBold)
                if (intruders.isEmpty()) Text("No intruder photos.", style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(intruders) { f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Thumb(f.absolutePath, 88)
                            Text(fmtTime(f.lastModified()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (intruders.isNotEmpty()) {
                    TextButton(onClick = {
                        val n = WatchmanFaceMonitor.clearIntruderGallery(ctx)
                        Toast.makeText(ctx, "Deleted $n photo(s)", Toast.LENGTH_SHORT).show()
                        onChanged()
                    }) { Text("Clear photos") }
                }
            }
        }

        // ---------------- Touch Guard
        item {
            SectionCard("Touch Guard", null) {
                val armed = TouchGuardManager.isArmed()
                Text(
                    if (armed) "ARMED${if (TouchGuardManager.armedAtMs > 0) " since ${fmtTime(TouchGuardManager.armedAtMs)}" else ""} · ${if (TouchGuardManager.isStealth()) "silent" else "siren"}"
                    else "Off",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                if (incidents.isEmpty()) Text("No incidents — nobody touched the phone.", style = MaterialTheme.typography.bodySmall)
                incidents.take(20).forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (i.photoPath != null && File(i.photoPath).exists()) Thumb(i.photoPath, 48) else Spacer(Modifier.width(48.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${i.timeText()} · ${i.source}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${i.verdict.lowercase(Locale.ROOT)} · motion ${"%.1f".format(i.motionDelta)}" +
                                    (if (i.names.isNotEmpty()) " · ${i.names.joinToString()}" else ""),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (incidents.isNotEmpty()) {
                    TextButton(onClick = { TouchGuardIncidents.of(ctx).clear(); onChanged() }) { Text("Clear incidents") }
                }
            }
        }

        // ---------------- Listen mode
        item {
            SectionCard("Listen mode — whose voice reaches Jarvis", "Strangers get one polite warning; the phone locks only if they try something sensitive (messages, calls, device control).") {
                ListenMode.values().forEach { m ->
                    RadioRow(m.label, m.summary, listenMode == m) {
                        ListenModeController.setMode(ctx, m)
                        listenMode = m
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Enrolled voices", fontWeight = FontWeight.SemiBold)
                if (voices.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall)
                voices.forEach { v ->
                    Text("• ${v.name} — ${v.role.name.lowercase(Locale.ROOT)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(ListenModeController.describeLastSpeaker(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------------- God Mode
        item {
            SectionCard(
                "God Mode",
                "Full autonomy: risky actions run without confirmation prompts. Needs your voice enrolled; it can only be switched off by your verified voice — or here, after your phone's fingerprint/PIN."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (godMode) "ON" else "Off", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = godMode, onCheckedChange = { on ->
                        if (on) {
                            val r = ListenModeController.setGodMode(ctx, true)
                            Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
                            godMode = prefs.godMode
                        } else {
                            FaceLockController.biometric(activity, "Turn off God Mode") { res ->
                                if (res.success) {
                                    val r = ListenModeController.setGodMode(ctx, false, viaTrustedUi = true)
                                    Toast.makeText(ctx, r.message, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, "God Mode stays on (${res.message})", Toast.LENGTH_SHORT).show()
                                }
                                godMode = prefs.godMode
                            }
                        }
                    })
                }
            }
        }

        // ---------------- Device admin
        item {
            SectionCard("Device admin", "Lets Jarvis lock the phone instantly and erase it only on the owner's verified voice + the exact phrase \"${WipeGuard.CONFIRM_PHRASE}\".") {
                Text(if (adminActive) "Active" else "Not active", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (!adminActive) {
                        Button(onClick = {
                            try { adminLauncher.launch(DevicePolicyAdmin.enableIntent(ctx)) } catch (e: Exception) {
                                Toast.makeText(ctx, "Couldn't open device admin: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Enable") }
                    } else {
                        OutlinedButton(onClick = {
                            try { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
                        }) { Text("Manage in Settings") }
                    }
                    OutlinedButton(onClick = {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        } catch (_: Exception) {
                            try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                        }
                    }) { Text("Battery: don't optimise") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEnroll) {
        EnrollDialog(
            initialName = initialEnrollName.orEmpty(),
            initialRole = initialEnrollRole ?: if (store.owners().isEmpty()) FaceStore.ROLE_BOSS else FaceStore.ROLE_FAMILY,
            onDone = { showEnroll = false; onChanged() }
        )
    }

    val target = forgetTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${target.name}?") },
            text = { Text("Deletes ${target.vectors.size} face print(s) and sample images. Role: ${target.role}.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { store.remove(target.name) }
                    forgetTarget = null
                    onChanged()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun UriThumb(uri: String) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { GalleryFaceIndexer.decodeSampled(ctx, uri, 256) }
    }
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Add-person flow: Camera2 preview on a TextureView (no CameraX / PreviewView), then
 * [FaceEnrollment.SAMPLES] snapshots, each detected + printed via [FaceEnrollment.addFrame].
 */
@Composable
private fun EnrollDialog(initialName: String, initialRole: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var role by remember { mutableStateOf(FaceStore.normaliseRole(initialRole)) }
    var status by remember { mutableStateOf("Look straight at the camera in good light.") }
    var captured by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<FaceCamera.PreviewSession?>(null) }
    var cameraReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    Dialog(onDismissRequest = { if (!busy) onDone() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("Add person", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Only save someone who is with you and agrees. You can delete it any time here.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)),
                    factory = { c ->
                        TextureView(c).apply {
                            // Front camera preview is mirrored like a selfie.
                            scaleX = -1f
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val s = FaceCamera.PreviewSession(c, st, front = true)
                                    session = s
                                    s.open(onReady = { cameraReady = true }, onFail = { msg -> status = msg })
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    session?.close()
                                    session = null
                                    cameraReady = false
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(FaceStore.ROLES) { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r) })
                    }
                }
                Text("$status  ($captured/${FaceEnrollment.SAMPLES})", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = cameraReady && !busy && name.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                var tries = 0
                                while (captured < FaceEnrollment.SAMPLES && tries < FaceEnrollment.SAMPLES * 3) {
                                    tries++
                                    val s = session ?: break
                                    val frame = suspendCancellableCoroutine<Bitmap?> { cont -> s.snap { b -> if (cont.isActive) cont.resume(b) } }
                                    if (frame == null) { status = "Camera didn't return a frame"; delay(400); continue }
                                    when (val step = withContext(Dispatchers.Default) { FaceEnrollment.addFrame(ctx, name, role, frame) }) {
                                        is FaceEnrollment.Step.Added -> { captured++; status = "Got it — turn your head slightly" }
                                        is FaceEnrollment.Step.Rejected -> status = step.reason
                                    }
                                    delay(700)
                                }
                                busy = false
                                if (captured >= FaceEnrollment.SAMPLES) {
                                    Toast.makeText(ctx, "Saved ${name.trim()} ($captured samples)", Toast.LENGTH_SHORT).show()
                                    onDone()
                                }
                            }
                        }
                    ) { Text(if (busy) "Capturing…" else "Capture ${FaceEnrollment.SAMPLES}") }
                    TextButton(enabled = !busy, onClick = onDone) { Text(if (captured > 0) "Done" else "Cancel") }
                }
            }
        }
    }
}
