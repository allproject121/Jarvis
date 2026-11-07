package com.jarvis.brain

/** Raw JNI bindings. Seedha use na karein — LocalLlamaEngine use karein. */
internal object LlamaNative {
    init { System.loadLibrary("jarvisbrain") }

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeGenerate(
        handle: Long, prompt: String, grammar: String, maxTokens: Int, temperature: Float
    ): String
    external fun nativeCancel(handle: Long)
    external fun nativeFree(handle: Long)
}
