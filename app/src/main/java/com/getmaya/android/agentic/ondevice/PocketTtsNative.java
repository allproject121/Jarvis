package com.getmaya.android.agentic.ondevice;

import java.nio.ByteBuffer;

public final class PocketTtsNative {
    public static final int CUT = 0;
    public static final int E_TEXT = -7;
    public static final int GRAPH_DECODER = 3;
    public static final int GRAPH_FLOW = 2;
    public static final int GRAPH_MAIN = 1;
    public static final int GRAPH_TEXT = 0;
    public static final int SPOKEN = 1;

    private static volatile String failure;
    private static volatile boolean tried;
    public static final PocketTtsNative INSTANCE = new PocketTtsNative();
    public static final int $stable = 8;

    private PocketTtsNative() {
    }

    public static final native void nativeClose(long id);

    public static final native int nativeConfigure(long id, float temperature, float eosThreshold, int eosExtraFrames, int firstChunkFrames, int maxChunkFrames, int maxTokensPerChunk, boolean padShortInputs, boolean appendTerminalPunctuation, int voiceStateCache);

    public static final native void nativeCut(long id);

    public static final native int nativeGraphFromFile(long id, int graph, String path);

    public static final native int nativeGraphFromStage(long id, int graph);

    public static final native String nativeInfo(long id);

    public static final native String nativeLastError(long id);

    public static final native int nativeLiveEngines();

    public static final native long nativeNew(int threads);

    public static final native int nativePrime(long id);

    public static final native int nativePurge();

    public static final native int nativeReady(long id);

    public static final native long nativeRssKb(boolean peak);

    public static final native int nativeSetVoice(long id, String name);

    public static final native int nativeSpeak(long id, byte[] utf8, PcmSink sink);

    public static final native ByteBuffer nativeStage(long id, long size);

    public static final native int nativeTokenizerFromFile(long id, String path);

    public static final native int nativeTokenizerFromStage(long id);

    public static final native void nativeUnstage(long id);

    public static final native int nativeVoiceFromFile(long id, String name, String path);

    public static final native int nativeVoiceFromStage(long id, String name);

    public final synchronized String load() {
        if (tried) {
            return failure;
        }
        tried = true;
        String strConcat = null;
        try {
            System.loadLibrary("onnxruntime");
            System.loadLibrary("pockettts_jni");
        } catch (SecurityException e) {
            strConcat = "library: " + e.getClass().getSimpleName();
        } catch (UnsatisfiedLinkError e2) {
            String message = e2.getMessage();
            if (message == null) {
                message = "";
            }
            if (message.length() > 120) {
                message = message.substring(0, 120);
            }
            strConcat = "library: " + message;
        }
        failure = strConcat;
        return failure;
    }
}
