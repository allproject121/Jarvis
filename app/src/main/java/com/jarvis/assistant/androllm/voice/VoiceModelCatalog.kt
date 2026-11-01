package com.jarvis.assistant.androllm.voice

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmPaths
import java.io.File

/** What a voice model pack is used for. */
enum class VoiceModelKind(val label: String) {
    WAKE_WORD("Wake word"),
    ASR("Speech recognition"),
    TTS("Text to speech")
}

/** How a TTS pack must be configured in sherpa-onnx. */
enum class TtsFlavor { NONE, VITS_LEXICON, PIPER_ESPEAK }

/** One file fetched over HTTP into the pack directory. */
data class RemoteFile(
    val url: String,
    val localName: String,
    /** Exact byte size published by the host (used for progress + verification). */
    val sizeBytes: Long,
    /** sha256 of the file (Git LFS oid on Hugging Face); null for small non-LFS text files. */
    val sha256: String? = null
)

/**
 * A directory listed at download time via the Hugging Face tree API (used for
 * Piper's espeak-ng-data, which is several hundred small files).
 */
data class RemoteDir(
    val repo: String,
    val revision: String,
    val path: String,
    val approxBytes: Long
)

/**
 * A downloadable sherpa-onnx model pack. All packs are stored under
 * `AndroLlmPaths.voiceModelsDir(context)/<id>/` and are only ever downloaded
 * when the user taps Download (see [VoiceModelDownloader]).
 */
data class VoiceModelPack(
    val id: String,
    val kind: VoiceModelKind,
    val title: String,
    val description: String,
    val source: String,
    val files: List<RemoteFile>,
    val remoteDir: RemoteDir? = null,
    /** Text files written into the pack dir after download (e.g. KWS keywords.txt). */
    val generatedFiles: Map<String, String> = emptyMap(),
    val ttsFlavor: TtsFlavor = TtsFlavor.NONE
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes } + (remoteDir?.approxBytes ?: 0L)

    fun dir(context: Context): File = File(AndroLlmPaths.voiceModelsDir(context), id)

    fun file(context: Context, localName: String): File = File(dir(context), localName)

    /** Marker written only after every file downloaded and verified. */
    fun completeMarker(context: Context): File = File(dir(context), ".complete")

    fun isInstalled(context: Context): Boolean =
        completeMarker(context).exists() && files.all { file(context, it.localName).length() == it.sizeBytes }
}

/**
 * The fixed catalog of voice packs. Every URL is pinned to a Hugging Face commit
 * so the published size + sha256 cannot drift underneath the verifier.
 *
 * Sources:
 *  - KWS: byte-identical re-host of the official k2-fsa release
 *    `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2`
 *    (upstream publishes only .tar.bz2, which Android cannot extract without an extra
 *    dependency). NOTE: third-party mirror (mobilebytesensei) - hashes pinned.
 *  - ASR: csukuangfj (sherpa-onnx author) mirror of
 *    sherpa-onnx-streaming-zipformer-en-2023-06-26 - the model AndroLLM uses.
 *  - TTS: csukuangfj/vits-ljs (AndroLLM's bundled VITS voice) and
 *    csukuangfj/vits-piper-en_US-amy-low (Piper voice + espeak-ng-data).
 */
object VoiceModelCatalog {

    private const val HF = "https://huggingface.co"

    private fun hf(repo: String, revision: String, path: String) = "$HF/$repo/resolve/$revision/$path"

    // ── Wake word ────────────────────────────────────────────────────────────

    private const val KWS_REPO = "mobilebytesensei/sherpa-onnx-kws-zipformer-gigaspeech-3.3M"
    private const val KWS_REV = "e8ca74a794ce66e38a100e7c60c0214189ff4100"

    /**
     * Jarvis wake phrases tokenized with the model's own bpe.model (sentencepiece,
     * done offline at port time). Format: `<tokens> @<NAME>` - boost/threshold come
     * from KeywordSpotterConfig. Phrases mirror Jarvis's Vosk wake grammar.
     */
    val JARVIS_KEYWORDS: String = listOf(
        "▁HE Y ▁JA R VI S @HEY_JARVIS",
        "▁HI ▁JA R VI S @HI_JARVIS",
        "▁HE LL O ▁JA R VI S @HELLO_JARVIS",
        "▁O K ▁JA R VI S @OK_JARVIS",
        "▁OKAY ▁JA R VI S @OKAY_JARVIS",
        "▁WA KE ▁UP ▁JA R VI S @WAKE_UP_JARVIS"
    ).joinToString("\n", postfix = "\n")

    val KWS_GIGASPEECH = VoiceModelPack(
        id = "kws-zipformer-gigaspeech-3.3M",
        kind = VoiceModelKind.WAKE_WORD,
        title = "Zipformer KWS (GigaSpeech 3.3M)",
        description = "Offline \"Hey Jarvis\" keyword spotter, int8, ~6 MB.",
        source = "$HF/$KWS_REPO",
        files = listOf(
            RemoteFile(
                hf(KWS_REPO, KWS_REV, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
                "encoder.onnx", 4_807_159L,
                "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"
            ),
            RemoteFile(
                hf(KWS_REPO, KWS_REV, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"),
                "decoder.onnx", 1_063_189L,
                "f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3"
            ),
            RemoteFile(
                hf(KWS_REPO, KWS_REV, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
                "joiner.onnx", 163_380L,
                "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"
            ),
            RemoteFile(
                hf(KWS_REPO, KWS_REV, "tokens.txt"),
                "tokens.txt", 5_006L,
                "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"
            )
        ),
        generatedFiles = mapOf("keywords.txt" to JARVIS_KEYWORDS)
    )

    // ── Streaming ASR ────────────────────────────────────────────────────────

    private const val ASR_REPO = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26"
    private const val ASR_REV = "672fbf1b30579d6585301139bb363f42a0ad4a24"

    val ASR_ZIPFORMER_EN = VoiceModelPack(
        id = "asr-streaming-zipformer-en-2023-06-26",
        kind = VoiceModelKind.ASR,
        title = "Streaming Zipformer (English)",
        description = "Offline streaming speech-to-text, int8 encoder, ~75 MB.",
        source = "$HF/$ASR_REPO",
        files = listOf(
            RemoteFile(
                hf(ASR_REPO, ASR_REV, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                "encoder.onnx", 71_083_163L,
                "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"
            ),
            RemoteFile(
                hf(ASR_REPO, ASR_REV, "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"),
                "decoder.onnx", 2_092_621L,
                "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef"
            ),
            RemoteFile(
                hf(ASR_REPO, ASR_REV, "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                "joiner.onnx", 259_335L,
                "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"
            ),
            RemoteFile(hf(ASR_REPO, ASR_REV, "tokens.txt"), "tokens.txt", 5_048L)
        )
    )

    // ── Offline TTS ──────────────────────────────────────────────────────────

    private const val LJS_REPO = "csukuangfj/vits-ljs"
    private const val LJS_REV = "7ac337c834f318e45a34037cb3371cc3929187ff"

    private val LJS_TEXT_FILES = listOf(
        RemoteFile(hf(LJS_REPO, LJS_REV, "tokens.txt"), "tokens.txt", 1_084L),
        RemoteFile(hf(LJS_REPO, LJS_REV, "lexicon.txt"), "lexicon.txt", 3_708_181L)
    )

    val TTS_VITS_LJS_INT8 = VoiceModelPack(
        id = "tts-vits-ljs-int8",
        kind = VoiceModelKind.TTS,
        title = "VITS LJSpeech (int8)",
        description = "Offline English female voice, quantized, ~41 MB.",
        source = "$HF/$LJS_REPO",
        files = listOf(
            RemoteFile(
                hf(LJS_REPO, LJS_REV, "vits-ljs.int8.onnx"),
                "model.onnx", 37_423_560L,
                "b3aa88de0202a040d7d75bac9dbf1a59d81d60c7369046f32241afa7dec65cc1"
            )
        ) + LJS_TEXT_FILES,
        ttsFlavor = TtsFlavor.VITS_LEXICON
    )

    val TTS_VITS_LJS = VoiceModelPack(
        id = "tts-vits-ljs",
        kind = VoiceModelKind.TTS,
        title = "VITS LJSpeech (full)",
        description = "AndroLLM's default offline voice, full precision, ~118 MB.",
        source = "$HF/$LJS_REPO",
        files = listOf(
            RemoteFile(
                hf(LJS_REPO, LJS_REV, "vits-ljs.onnx"),
                "model.onnx", 114_124_456L,
                "5bbd273797a9ecf8d94bd6ec02ad16cb41cbb85f055ad98d528ced3e44c9b31a"
            )
        ) + LJS_TEXT_FILES,
        ttsFlavor = TtsFlavor.VITS_LEXICON
    )

    private const val PIPER_REPO = "csukuangfj/vits-piper-en_US-amy-low"
    private const val PIPER_REV = "76da6ca287517a49f58b943c4c4fdb0c1e94d61f"

    val TTS_PIPER_AMY = VoiceModelPack(
        id = "tts-piper-en_US-amy-low",
        kind = VoiceModelKind.TTS,
        title = "Piper en_US Amy (low)",
        description = "Piper voice with espeak-ng phonemizer, ~81 MB.",
        source = "$HF/$PIPER_REPO",
        files = listOf(
            RemoteFile(
                hf(PIPER_REPO, PIPER_REV, "en_US-amy-low.onnx"),
                "model.onnx", 63_104_657L,
                "8275b02c37c4ce6483b26a91704807e6de0c3f0bf8068e5d3b3598ab0da4253e"
            ),
            RemoteFile(hf(PIPER_REPO, PIPER_REV, "tokens.txt"), "tokens.txt", 763L)
        ),
        remoteDir = RemoteDir(PIPER_REPO, PIPER_REV, "espeak-ng-data", approxBytes = 17_991_651L),
        ttsFlavor = TtsFlavor.PIPER_ESPEAK
    )

    val all: List<VoiceModelPack> = listOf(
        KWS_GIGASPEECH,
        ASR_ZIPFORMER_EN,
        TTS_VITS_LJS_INT8,
        TTS_VITS_LJS,
        TTS_PIPER_AMY
    )

    fun byId(id: String?): VoiceModelPack? = all.firstOrNull { it.id == id }

    fun ofKind(kind: VoiceModelKind): List<VoiceModelPack> = all.filter { it.kind == kind }

    const val SAMPLE_RATE = 16000
    const val FEATURE_DIM = 80
}
