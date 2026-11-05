package com.jarvis.assistant.androllm.voice.model

import android.content.Context

/**
 * Asset / file locations of the voice models used by the optional AndroLLM voice
 * assistant (ported from AndroLLM `VoiceModels`).
 *
 * AndroLLM bundled its sherpa-onnx models under `assets/voice/`; Jarvis instead
 * downloads them on demand ([com.jarvis.assistant.androllm.voice.VoiceModelCatalog] /
 * [com.jarvis.assistant.androllm.voice.VoiceModelDownloader], files under
 * `filesDir/models/voice/<pack>`), so only the shared constants and the bundled
 * openWakeWord assets are referenced here. Keeping all references in one place
 * means the voice layer never hard-codes a path.
 */
object VoiceModels {

    /** openWakeWord feature models shipped by Jarvis (assets/wakeword/). */
    const val OWW_DIR = "wakeword"
    const val OWW_MELSPECTROGRAM = "wakeword/melspectrogram.tflite"
    const val OWW_EMBEDDING = "wakeword/embedding_model.tflite"

    /** Feature models that are not wake-word classifiers. */
    private val OWW_FEATURE_MODELS = setOf("melspectrogram", "embedding_model")

    /** Jarvis's bundled Vosk acoustic model (assets/model-en-us), unpacked by VoskModelManager. */
    const val VOSK_MODEL_ASSET = "model-en-us"

    const val SAMPLE_RATE = 16000
    const val FEATURE_DIM = 80

    /** Asset path of an openWakeWord classifier (e.g. "hey_maya"). */
    fun openWakeWordClassifier(name: String): String = "$OWW_DIR/$name.tflite"

    /** Bundled openWakeWord classifier names (e.g. hey_maya, hello_maya, wake_up_maya). */
    fun availableOpenWakeWordModels(context: Context): List<String> =
        runCatching { context.assets.list(OWW_DIR)?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it.endsWith(".tflite") }
            .map { it.removeSuffix(".tflite") }
            .filter { it !in OWW_FEATURE_MODELS }
            .sorted()

    /** "hey_maya" → "Hey Maya". */
    fun displayName(model: String): String =
        model.split('_').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
