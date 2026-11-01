package com.jarvis.assistant.androllm.engine.support

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Ported from AndroLLM core/models Enums.kt (model-related subset only).

/** Supported model file formats. */
@Serializable
enum class ModelFormat {
    @SerialName("gguf") GGUF,
    @SerialName("ggml") GGML,
    @SerialName("qnn") QNN,
    @SerialName("onnx") ONNX,
    @SerialName("safetensors") SAFETENSORS,
    @SerialName("pt") PYTORCH,
    /** LiteRT-LM container: TFLite model + tokenizer + chat template in one file. */
    @SerialName("litertlm") LITERTLM,
    /** Plain TensorFlow Lite flatbuffer (e.g. EmbeddingGemma `.tflite`). */
    @SerialName("tflite") TFLITE,
    @SerialName("unknown") UNKNOWN
}

/** Download status of a model. */
@Serializable
enum class DownloadStatus {
    @SerialName("not_downloaded") NOT_DOWNLOADED,
    @SerialName("queued") QUEUED,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("paused") PAUSED,
    @SerialName("downloaded") DOWNLOADED,
    @SerialName("error") ERROR
}

/** Load status of a model. */
@Serializable
enum class ModelStatus {
    @SerialName("not_loaded") NOT_LOADED,
    @SerialName("loading") LOADING,
    @SerialName("loaded") LOADED,
    @SerialName("error") ERROR
}
