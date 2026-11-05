package com.jarvis.assistant.ai.compat

import com.jarvis.assistant.androllm.engine.support.ModelMetadataRegistry

/**
 * The model families supported on-device — shared by BOTH local runtimes:
 *
 *  - the GGUF / llama.cpp path ([com.jarvis.assistant.ai.provider.LocalPhoneProvider]),
 *    which resolves a family from the model id via [resolveFromModelId] and
 *    uses [bosToken] / [eosToken] / [stopTokens] plus [ChatTemplateRenderer]'s
 *    hand-written family templates, and
 *  - the LiteRT-LM compatibility layer (`androllm.engine.compat`), which
 *    resolves a family from container metadata via [fromLlmModelType] /
 *    [fromEngineKey] / [fromName] and uses [aliases], [tokenizerKind],
 *    [nativeToolMarkers] and [toolAdvertisementCapChars] with the official
 *    Jinja chat templates.
 *
 * A family is a group of models that share the same tokenizer family, the same
 * official chat template, and the same special-token / stop-token conventions.
 * Every supported model must resolve to exactly one [ModelFamily]; on the
 * LiteRT side the resolution is driven by container metadata first (the LiteRT
 * `LlmMetadata` proto embedded in the `.litertlm` file) and falls back to
 * template/stop-token signatures and finally to the model name ONLY when no
 * metadata exists.
 *
 * Adding support for a new family means adding one enum value here (plus one
 * entry in `ModelFamilyRegistry`) — never touching the engine.
 */

/**
 * Character cap for the tool advertisement on families whose smallest repacks
 * degrade with a long tool list (see [ModelFamily.toolAdvertisementCapChars]).
 * 4500 chars ≈ 1100 tokens — comfortably below the measured ~5.7K-char
 * breakpoint for a 1.5B Qwen while still advertising ~23 tools. Top-level (not
 * in the companion) because enum entries are constructed before the companion
 * object exists.
 */
private const val TOOL_AD_CAP_SMALL_MODEL = 4500

enum class ModelFamily(
    /** Canonical family name shown in the UI / diagnostics. */
    val displayName: String,
    /** Name-based aliases used only when metadata is unavailable. */
    val aliases: List<String>,
    /** The tokenizer kind the family ships with. */
    val tokenizerKind: TokenizerKind,
    /**
     * True when the family's repacks can emit native `<|tool_call|>` markers
     * during decoding (Qwen's official function calling, and the function-
     * calling Gemma repacks LiteRT-LM ships as `function_gemma`). The chat
     * layer skips the slow JSON-compat planner for these families — an
     * answer without markers is authoritative and gets committed immediately.
     */
    val nativeToolMarkers: Boolean = false,
    /**
     * Upper bound (chars) on the tool-advertisement system message this
     * family's smallest repacks can absorb before degrading to empty/garbage
     * output. Measured on-device: Qwen2.5-1.5B degrades between ~5.7K and
     * ~9.4K chars of tool-list system prompt, and Qwen3-0.6B overflows its
     * 2048-token real context with the full ~2.3K-token list. Gemma 4B
     * handles the full list. `Int.MAX_VALUE` = no family cap (context budget
     * still applies).
     */
    val toolAdvertisementCapChars: Int = Int.MAX_VALUE,
    /**
     * GGUF/llama.cpp path only. Beginning-of-sequence token the hand-written
     * family template in [ChatTemplateRenderer] (companion `render`) assumes.
     */
    val bosToken: String,
    /**
     * GGUF/llama.cpp path only. End-of-sequence token of the family.
     */
    val eosToken: String,
    /**
     * GGUF/llama.cpp path only. Stop strings passed to llama.cpp's sampler so
     * generation halts at the family's turn terminators.
     */
    val stopTokens: List<String>
) {
    GEMMA(
        "Gemma",
        listOf("gemma"),
        TokenizerKind.SENTENCEPIECE,
        nativeToolMarkers = true,
        bosToken = "<bos>",
        eosToken = "<eos>",
        stopTokens = listOf("<end_of_turn>", "<eos>")
    ),
    QWEN2(
        "Qwen2",
        listOf("qwen2", "qwen1.5", "qwen1.6", "qwen-vl"),
        TokenizerKind.BPE,
        nativeToolMarkers = true,
        toolAdvertisementCapChars = TOOL_AD_CAP_SMALL_MODEL,
        bosToken = "",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>", "</tool_call>")
    ),
    QWEN2P5(
        "Qwen2.5",
        listOf("qwen2.5", "qwen2_5"),
        TokenizerKind.BPE,
        nativeToolMarkers = true,
        toolAdvertisementCapChars = TOOL_AD_CAP_SMALL_MODEL,
        bosToken = "",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>", "</tool_call>")
    ),
    QWEN3(
        "Qwen3",
        listOf("qwen3", "qwen3-vl"),
        TokenizerKind.BPE,
        nativeToolMarkers = true,
        toolAdvertisementCapChars = TOOL_AD_CAP_SMALL_MODEL,
        bosToken = "",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>", "</tool_call>")
    ),
    PHI(
        "Phi",
        listOf("phi", "phi-2", "phi-3", "phi-4", "phimoe"),
        TokenizerKind.BPE,
        bosToken = "<s>",
        eosToken = "<|endoftext|>",
        stopTokens = listOf("<|end|>", "<|endoftext|>")
    ),
    LLAMA3(
        "Llama 3",
        listOf("llama-3", "llama3", "llama-3.1", "llama-3.2", "llama-3.3"),
        TokenizerKind.BPE,
        bosToken = "<|begin_of_text|>",
        eosToken = "<|eot_id|>",
        stopTokens = listOf("<|eot_id|>", "<|end_of_text|>")
    ),
    // DeepSeek-R1-Distill uses its own fullwidth-bar template, not ChatML. With ChatML framing the
    // model never emitted a stop token and wrote "</|im_end|><|im_start|>user …" fake turns into replies.
    DEEPSEEK(
        "DeepSeek",
        listOf("deepseek"),
        TokenizerKind.BPE,
        bosToken = "<｜begin▁of▁sentence｜>",
        eosToken = "<｜end▁of▁sentence｜>",
        stopTokens = listOf("<｜end▁of▁sentence｜>", "<｜User｜>", "</tool_call>")
    ),
    MISTRAL(
        "Mistral",
        listOf("mistral"),
        TokenizerKind.BPE,
        bosToken = "<s>",
        eosToken = "</s>",
        stopTokens = listOf("</s>")
    ),
    SMOL(
        "SmolLM",
        listOf("smollm", "smol"),
        TokenizerKind.BPE,
        bosToken = "<|im_start|>",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>")
    ),
    TINYLLAMA(
        "TinyLlama",
        listOf("tinyllama", "tinylama"),
        TokenizerKind.BPE,
        bosToken = "<s>",
        eosToken = "</s>",
        stopTokens = listOf("</s>")
    ),
    /**
     * The permissive fallback for containers whose identifier is supported by
     * the runtime but has no bespoke family configuration (e.g. `fast_vlm`,
     * `minicpm5`) and for models with no identifying metadata at all. The
     * container's own embedded template drives chat (identity override), its
     * own stop tokens terminate generation — nothing is guessed.
     */
    GENERIC(
        "Generic",
        emptyList(),
        TokenizerKind.BPE,
        bosToken = "",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>")
    );

    companion object {
        /**
         * Maps a `LlmModelType` container identifier (the oneof member names
         * LiteRT-LM writes into `llm_model_type`, field 6 of `LlmMetadata`)
         * to its engine family via the shared [ModelMetadataRegistry]. Every
         * identifier the runtime supports is registered — none is ever
         * "rejected": identifiers without a bespoke engine family map to
         * [GENERIC], and `generic_model` returns null so detection continues
         * to the template/stop-token signatures.
         */
        fun fromLlmModelType(modelTypeName: String?): ModelFamily? =
            fromEngineKey(ModelMetadataRegistry.engineFamilyKeyForContainer(modelTypeName))

        /** Lookup by the registry's engine-family key (e.g. "QWEN3"). */
        fun fromEngineKey(engineKey: String?): ModelFamily? =
            entries.firstOrNull { it.name == engineKey }

        /** Case-insensitive alias lookup — LAST resort, never used when metadata exists. */
        fun fromName(name: String?): ModelFamily? {
            val n = name?.lowercase() ?: return null
            return entries.firstOrNull { family ->
                family.aliases.any { n.contains(it) }
            }
        }

        /**
         * GGUF/llama.cpp path only. Resolves the family from a GGUF model id
         * with the ordered substring heuristic the llama.cpp provider has
         * always used (first match wins; anything unknown is treated as a
         * ChatML/Qwen 2.5 model). Deliberately NOT delegated to [fromName] so
         * the llama.cpp prompt framing never changes.
         */
        fun resolveFromModelId(modelId: String): ModelFamily {
            val lower = modelId.lowercase()
            return when {
                lower.contains("gemma") -> GEMMA
                lower.contains("llama") -> LLAMA3
                lower.contains("deepseek") -> DEEPSEEK
                lower.contains("smol") -> SMOL
                else -> QWEN2P5
            }
        }
    }
}

/** Tokenizer technology a family uses. */
enum class TokenizerKind {
    /** Byte-level BPE tokenizer (`tokenizer.json` with vocab+merges). */
    BPE,
    /** SentencePiece tokenizer (`tokenizer.model`). */
    SENTENCEPIECE
}
