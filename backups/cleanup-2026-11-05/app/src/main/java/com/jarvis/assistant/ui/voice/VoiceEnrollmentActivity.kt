package com.jarvis.assistant.ui.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.databinding.ActivityVoiceEnrollmentBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class VoiceEnrollmentActivity : AppCompatActivity() {

    companion object {
        private val voskInitScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )
    }

    private lateinit var binding: ActivityVoiceEnrollmentBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val samplePrompts = listOf(
        "\"Hey Jarvis, wake up\"",
        "\"Jarvis, turn on the flashlight\"",
        "\"Jarvis, what is the weather today?\""
    )

    private val recordedVectors = mutableListOf<FloatArray>()
    private var currentSampleIndex = 0

    private var isRecording = false
    private var isTesting = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recordingBuffer = ByteArrayOutputStream()

    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Microphone permission required for voice enrollment", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val topInset = statusBarInsets.top
            val safeTopPadding = if (topInset > 0) topInset + (6 * density).toInt() else (14 * density).toInt()
            v.setPadding(v.paddingLeft, safeTopPadding, v.paddingRight, v.paddingBottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }

        // Trigger Vosk initialization in background if not already started. Uses a process-wide
        // scope: with lifecycleScope, leaving this screen mid-unpack cancelled the speaker-model load.
        VoskModelManager.init(applicationContext, voskInitScope)

        setupUI()
        setupListeners()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupUI() {
        val hasProfile = OwnerVoiceManager.isEnrolled(preferences)
        val isEnabled = OwnerVoiceManager.isVoiceLockActive(preferences)
        val statusLine = OwnerVoiceManager.voiceLockStatusLine(preferences)

        binding.switchOwnerOnly.isChecked = isEnabled
        binding.switchOwnerOnly.isEnabled = hasProfile

        if (hasProfile) {
            binding.tvEnrollmentStatus.text = if (isEnabled) "VOICE LOCK: ENROLLED" else "VOICE LOCK: ENROLLED (PAUSED)"
            binding.tvEnrollmentStatus.setTextColor(
                if (isEnabled) ThemeManager.attrColor(this, R.attr.themeAccent, getColor(R.color.primary_cyan))
                else ThemeManager.attrColor(this, R.attr.themeTextSecondary, getColor(R.color.text_secondary))
            )
            binding.tvStatusDescription.text = if (isEnabled) {
                "$statusLine.\nJarvis is calibrated to your voice (${preferences.ownerVoiceSampleCount} samples). Speech from other people is blocked."
            } else {
                "$statusLine.\nTurn on the owner-only switch to block other voices again."
            }
            binding.viewStatusDot.setBackgroundResource(R.drawable.circle_cyan)
        } else {
            binding.tvEnrollmentStatus.text = "VOICE LOCK: NOT ENROLLED"
            binding.tvEnrollmentStatus.setTextColor(getColor(R.color.status_amber))
            binding.tvStatusDescription.text = "$statusLine.\nEnroll 3 voice samples below. Once saved, Jarvis will respond ONLY to you and ignore anyone else."
        }

        // Sensitivity
        val threshold = preferences.ownerVoiceThreshold
        when {
            threshold <= OwnerVoiceManager.STRICT_THRESHOLD + 0.02f -> binding.rbStrict.isChecked = true
            threshold >= OwnerVoiceManager.RELAXED_THRESHOLD - 0.02f -> binding.rbRelaxed.isChecked = true
            else -> binding.rbBalanced.isChecked = true
        }

        updateEnrollmentStepUI()
    }

    private fun updateEnrollmentStepUI() {
        if (currentSampleIndex < samplePrompts.size) {
            binding.tvStepIndicator.text = "TRAIN STEP ${currentSampleIndex + 1} OF 3"
            binding.tvPromptPhrase.text = samplePrompts[currentSampleIndex]
            binding.btnRecordSample.text = "TAP & SPEAK PHRASE"
            binding.tvRecordFeedback.text = "Tap to start recording sample ${currentSampleIndex + 1}"
        } else {
            binding.tvStepIndicator.text = "ENROLLMENT COMPLETE"
            binding.tvPromptPhrase.text = "All 3 samples registered!"
            binding.btnRecordSample.text = "RE-RECORD SAMPLES"
            binding.tvRecordFeedback.text = "Owner voice profile is active and secured."
        }

        // Dot indicators
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val mutedColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_muted)

        val greenColor = ContextCompat.getColor(this, R.color.status_green)

        binding.tvStep1Dot.setTextColor(if (recordedVectors.size >= 1) greenColor else if (currentSampleIndex == 0) accentColor else mutedColor)
        binding.tvStep2Dot.setTextColor(if (recordedVectors.size >= 2) greenColor else if (currentSampleIndex == 1) accentColor else mutedColor)
        binding.tvStep3Dot.setTextColor(if (recordedVectors.size >= 3) greenColor else if (currentSampleIndex == 2) accentColor else mutedColor)
    }

    private fun setupListeners() {
        binding.switchOwnerOnly.setOnCheckedChangeListener { _, isChecked ->
            preferences.isOwnerVoiceEnabled = isChecked
            setupUI()
        }

        binding.btnRecordSample.setOnClickListener {
            if (isRecording) {
                stopRecordingSample()
            } else {
                if (currentSampleIndex >= samplePrompts.size) {
                    // Reset to start over
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    updateEnrollmentStepUI()
                }
                startRecordingSample()
            }
        }

        binding.btnTestVoice.setOnClickListener {
            if (isTesting) {
                stopTestingVoice()
            } else {
                startTestingVoice()
            }
        }

        binding.rgSensitivity.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbStrict -> preferences.ownerVoiceThreshold = OwnerVoiceManager.STRICT_THRESHOLD
                R.id.rbBalanced -> preferences.ownerVoiceThreshold = OwnerVoiceManager.DEFAULT_THRESHOLD
                R.id.rbRelaxed -> preferences.ownerVoiceThreshold = OwnerVoiceManager.RELAXED_THRESHOLD
            }
            Toast.makeText(this, "Sensitivity updated", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearProfile.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset Voice Profile?")
                .setMessage("This will remove your enrolled voiceprint. Jarvis will respond to all voices until you re-train.")
                .setPositiveButton("RESET") { _, _ ->
                    preferences.clearOwnerVoiceProfile()
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    setupUI()
                    Toast.makeText(this, "Voice profile cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingSample() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }
        if (!ensureSpeakerModelReady()) {
            binding.tvRecordFeedback.text = "Voice model is still loading - wait a few seconds and tap again."
            return
        }

        isRecording = true
        binding.btnRecordSample.text = "STOP & PROCESS"
        binding.btnRecordSample.setBackgroundColor(getColor(R.color.status_amber))
        binding.tvRecordFeedback.text = "Listening... Speak the phrase above now!"

        startAudioCapture { pcmChunk ->
            recordingBuffer.write(pcmChunk)
        }
    }

    private fun stopRecordingSample() {
        isRecording = false
        stopAudioCapture()

        val pcmAudio = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        if (pcmAudio.size < 16000 * 2 * 0.4) {
            binding.btnRecordSample.isEnabled = true
            binding.btnRecordSample.setBackgroundColor(ThemeManager.attrColor(this@VoiceEnrollmentActivity, R.attr.themeAccent, getColor(R.color.primary_cyan)))
            binding.tvRecordFeedback.text = "Recording was too short. Please speak the full phrase and tap Stop."
            binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
            return
        }

        binding.btnRecordSample.isEnabled = false
        binding.tvRecordFeedback.text = "Analyzing vocal acoustic signature..."

        lifecycleScope.launch(Dispatchers.Default) {
            val vector = OwnerVoiceManager.extractSpeakerVector(pcmAudio)

            withContext(Dispatchers.Main) {
                binding.btnRecordSample.isEnabled = true
                binding.btnRecordSample.setBackgroundColor(ThemeManager.attrColor(this@VoiceEnrollmentActivity, R.attr.themeAccent, getColor(R.color.primary_cyan)))

                if (vector != null && vector.isNotEmpty()) {
                    recordedVectors.add(vector)
                    currentSampleIndex++

                    if (currentSampleIndex >= samplePrompts.size) {
                        // Average vectors and save both centroid and individual samples
                        val masterProfile = OwnerVoiceManager.averageVectors(recordedVectors)
                        if (masterProfile != null) {
                            preferences.saveOwnerVoiceProfile(masterProfile, recordedVectors)
                            preferences.isOwnerVoiceEnabled = true
                            Toast.makeText(this@VoiceEnrollmentActivity, "Voice profile trained successfully!", Toast.LENGTH_LONG).show()
                            setupUI()
                        } else {
                            Toast.makeText(this@VoiceEnrollmentActivity, "Failed to average voice vectors. Please retry.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@VoiceEnrollmentActivity, "Sample ${currentSampleIndex} saved! Say next phrase.", Toast.LENGTH_SHORT).show()
                        updateEnrollmentStepUI()
                    }
                } else {
                    binding.tvRecordFeedback.text = "Could not detect clear speech. Please speak louder and retry."
                    binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
                }
            }
        }
    }

    /**
     * The speaker (x-vector) model is unpacked/loaded asynchronously. Without it every sample
     * "fails" with a misleading "could not detect clear speech", so wait for it explicitly.
     */
    private fun ensureSpeakerModelReady(): Boolean {
        if (VoskModelManager.model != null && VoskModelManager.speakerModel != null) return true
        VoskModelManager.init(applicationContext, voskInitScope)
        return false
    }

    @SuppressLint("MissingPermission")
    private fun startTestingVoice() {
        if (!OwnerVoiceManager.isEnrolled(preferences)) {
            Toast.makeText(this, "Please enroll your voice samples first!", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }
        if (!ensureSpeakerModelReady()) {
            Toast.makeText(this, "Voice model is still loading - try again in a few seconds.", Toast.LENGTH_SHORT).show()
            return
        }

        isTesting = true
        binding.btnTestVoice.text = "STOP & VERIFY NOW"
        val amberColor = ContextCompat.getColor(this, R.color.status_amber)
        binding.btnTestVoice.setTextColor(amberColor)
        binding.btnTestVoice.strokeColor = android.content.res.ColorStateList.valueOf(amberColor)
        binding.layoutTestResult.visibility = View.GONE

        startAudioCapture { pcmChunk ->
            recordingBuffer.write(pcmChunk)
        }
    }

    private fun stopTestingVoice() {
        isTesting = false
        stopAudioCapture()

        val pcmAudio = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        binding.btnTestVoice.text = "TEST VOICE MATCH (SPEAK NOW)"
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.primary_cyan)
        binding.btnTestVoice.setTextColor(accentColor)
        binding.btnTestVoice.strokeColor = android.content.res.ColorStateList.valueOf(accentColor)

        if (pcmAudio.size < 16000 * 2 * 0.4) {
            binding.layoutTestResult.visibility = View.VISIBLE
            binding.tvTestVerdict.text = "RECORDING TOO SHORT"
            binding.tvTestVerdict.setTextColor(getColor(R.color.status_amber))
            binding.tvTestDetails.text = "Please speak a full sentence (at least 1-2 seconds) to verify voice match."
            return
        }

        binding.btnTestVoice.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) {
            // Compare against the profile directly so the test is real even while the lock is paused
            // (verifySpeakerAudio accepts every voice when the lock is off).
            val vector = OwnerVoiceManager.extractSpeakerVector(pcmAudio)
            val result = if (vector == null) {
                com.jarvis.assistant.audio.VoiceMatchResult(
                    isMatch = false,
                    distance = 1.0f,
                    similarityPercent = 0,
                    threshold = preferences.ownerVoiceThreshold,
                    hasExtractedVector = false
                )
            } else {
                OwnerVoiceManager.verifySpeakerVector(vector, preferences)
            }

            withContext(Dispatchers.Main) {
                binding.btnTestVoice.isEnabled = true
                binding.layoutTestResult.visibility = View.VISIBLE

                if (result.isMatch) {
                    binding.tvTestVerdict.text = "VERIFIED: OWNER (${result.similarityPercent}% MATCH)"
                    binding.tvTestVerdict.setTextColor(getColor(R.color.status_green))
                    binding.tvTestDetails.text = "Voice matched! Jarvis will accept commands. (Distance: ${String.format(Locale.ROOT, "%.2f", result.distance)}, Threshold: ${result.threshold})"
                } else {
                    binding.tvTestVerdict.text = "REJECTED: UNKNOWN VOICE (${result.similarityPercent}% MATCH)"
                    binding.tvTestVerdict.setTextColor(getColor(R.color.status_red))
                    binding.tvTestDetails.text = "Non-owner voice detected! Jarvis will ignore. (Distance: ${String.format(Locale.ROOT, "%.2f", result.distance)}, Threshold: ${result.threshold})"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(onChunk: (ByteArray) -> Unit) {
        stopAudioCapture()
        recordingBuffer.reset()

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufSize * 2, 32000)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return@launch
                }

                record.startRecording()
                audioRecord = record

                val chunk = ByteArray(640) // 20ms chunk at 16kHz 16-bit
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        onChunk(chunk.copyOf(read))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    private fun stopAudioCapture() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    override fun onDestroy() {
        stopAudioCapture()
        super.onDestroy()
    }
}
