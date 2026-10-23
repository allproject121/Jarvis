package com.jarvis.assistant.voice

import com.getmaya.android.agentic.ondevice.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests verifying [PocketTtsWrapper] behavior, lifecycle, threading, and error handling.
 * Uses a mock [PocketTtsBridge] test double so tests execute reliably on the JVM without native libraries.
 */
class PocketTtsWrapperTest {

    private class FakePocketTtsBridge : PocketTtsBridge {
        var loadLibraryResult: String? = null
        var createEngineResult: Long = 1001L
        var configureResult: Int = 0
        var markReadyResult: Int = 0
        var speakResult: Int = 0

        var loadLibraryCalled = false
        var createEngineCalledWithThreads = -1
        var configureCalled = false
        var markReadyCalled = false
        var cutCalledWithId: Long? = null
        var closeCalledWithId: Long? = null
        var speakCalledWithBytes: ByteArray? = null
        var lastErrorString: String? = "Test error"
        var infoString: String? = "PocketTTS v1.0.0 (threads=2)"

        var shouldSimulatePcmGeneration = true
        var simulatePcmChunkSize = 256
        var simulateChunksCount = 3

        override fun loadLibrary(): String? {
            loadLibraryCalled = true
            return loadLibraryResult
        }

        override fun createEngine(threads: Int): Long {
            createEngineCalledWithThreads = threads
            return createEngineResult
        }

        override fun configure(
            id: Long,
            temperature: Float,
            eosThreshold: Float,
            eosExtraFrames: Int,
            firstChunkFrames: Int,
            maxChunkFrames: Int,
            maxTokensPerChunk: Int,
            padShortInputs: Boolean,
            appendTerminalPunctuation: Boolean,
            voiceStateCache: Int
        ): Int {
            configureCalled = true
            return configureResult
        }

        override fun markReady(id: Long): Int {
            markReadyCalled = true
            return markReadyResult
        }

        override fun speak(id: Long, utf8: ByteArray, sink: PcmSink): Int {
            speakCalledWithBytes = utf8
            if (shouldSimulatePcmGeneration) {
                val dummyChunk = ByteArray(simulatePcmChunkSize) { 0x1F.toByte() }
                for (i in 0 until simulateChunksCount) {
                    val continueStreaming = sink.onPcm(dummyChunk, dummyChunk.size)
                    if (!continueStreaming) {
                        return -1 // Stopped early
                    }
                }
            }
            return speakResult
        }

        override fun cut(id: Long) {
            cutCalledWithId = id
        }

        override fun close(id: Long) {
            closeCalledWithId = id
        }

        override fun lastError(id: Long): String? = lastErrorString
        override fun info(id: Long): String? = infoString
    }

    @Test
    fun testInitializationSuccess() {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)

        val result = wrapper.initialize(threads = 4)
        assertTrue("Initialization should succeed", result.isSuccess)
        assertEquals(1001L, result.getOrNull())
        assertTrue("Wrapper should report ready", wrapper.isReady())
        assertEquals(1001L, wrapper.getEngineId())
        assertTrue(fakeBridge.loadLibraryCalled)
        assertEquals(4, fakeBridge.createEngineCalledWithThreads)
        assertTrue(fakeBridge.configureCalled)
        assertTrue(fakeBridge.markReadyCalled)
    }

    @Test
    fun testInitializationFailureOnLibraryLoad() {
        val fakeBridge = FakePocketTtsBridge().apply {
            loadLibraryResult = "library: UnsatisfiedLinkError libonnxruntime.so"
        }
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)

        val result = wrapper.initialize()
        assertTrue("Initialization should fail when library cannot load", result.isFailure)
        assertFalse("Wrapper should not report ready", wrapper.isReady())
        assertEquals(0L, wrapper.getEngineId())
        assertFalse("Should not attempt to create engine if library failed", fakeBridge.configureCalled)
    }

    @Test
    fun testInitializationFailureOnEngineAllocation() {
        val fakeBridge = FakePocketTtsBridge().apply {
            createEngineResult = 0L // allocation failed
        }
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)

        val result = wrapper.initialize()
        assertTrue("Initialization should fail if nativeNew returns 0", result.isFailure)
        assertFalse(wrapper.isReady())
        assertEquals(0L, wrapper.getEngineId())
    }

    @Test
    fun testSynthesizeValidInputDeliversPcmChunks() = runBlocking {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        val pcmChunksReceived = AtomicInteger(0)
        val totalBytesReceived = AtomicInteger(0)

        val testText = "Hello Jarvis, how are you?"
        val synthResult = wrapper.synthesize(testText) { buffer, length ->
            pcmChunksReceived.incrementAndGet()
            totalBytesReceived.addAndGet(length)
            true
        }

        assertTrue("Synthesize should succeed", synthResult.isSuccess)
        assertEquals(0, synthResult.getOrNull())
        assertEquals(3, pcmChunksReceived.get())
        assertEquals(3 * 256, totalBytesReceived.get())
        assertArrayEquals(
            testText.toByteArray(StandardCharsets.UTF_8),
            fakeBridge.speakCalledWithBytes
        )
    }

    @Test
    fun testSynthesizeBlankOrEmptyInputReturnsImmediately() = runBlocking {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        val emptyResult = wrapper.synthesize("") { _, _ -> true }
        assertTrue(emptyResult.isSuccess)
        assertNull("Native speak should not be called for empty string", fakeBridge.speakCalledWithBytes)

        val blankResult = wrapper.synthesize("   \n\t  ") { _, _ -> true }
        assertTrue(blankResult.isSuccess)
        assertNull("Native speak should not be called for blank string", fakeBridge.speakCalledWithBytes)
    }

    @Test
    fun testSynthesizeWhenUninitializedFailsGracefully() = runBlocking {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)

        val result = wrapper.synthesize("Hello") { _, _ -> true }
        assertTrue("Synthesizing without initialization must fail gracefully", result.isFailure)
        assertTrue(result.exceptionOrNull() is PocketTtsException)
    }

    @Test
    fun testSynthesizeCancellationWhenSinkReturnsFalse() = runBlocking {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        val chunksCount = AtomicInteger(0)
        val result = wrapper.synthesize("Long narration...") { _, _ ->
            val count = chunksCount.incrementAndGet()
            count < 2 // Cancel on chunk 2
        }

        assertTrue(result.isSuccess)
        assertEquals(-1, result.getOrNull()) // Stopped early
        assertEquals(2, chunksCount.get())
    }

    @Test
    fun testStopCallsNativeCut() {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        wrapper.stop()
        assertEquals(1001L, fakeBridge.cutCalledWithId)
    }

    @Test
    fun testCloseReleasesNativeResourcesAndResetsState() {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        assertTrue(wrapper.isReady())
        wrapper.close()

        assertFalse("Engine must not be ready after close", wrapper.isReady())
        assertEquals(0L, wrapper.getEngineId())
        assertEquals(1001L, fakeBridge.closeCalledWithId)

        // Calling close again must be safe and idempotent
        wrapper.close()
    }

    @Test
    fun testNativeErrorAndInfoQueries() {
        val fakeBridge = FakePocketTtsBridge()
        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        assertEquals("Test error", wrapper.getLastError())
        assertEquals("PocketTTS v1.0.0 (threads=2)", wrapper.getEngineInfo())
    }

    @Test
    fun testNativeExceptionCaughtGracefullyDuringSynthesis() = runBlocking {
        val fakeBridge = object : PocketTtsBridge by FakePocketTtsBridge() {
            override fun loadLibrary(): String? = null
            override fun createEngine(threads: Int): Long = 555L
            override fun configure(
                id: Long, temperature: Float, eosThreshold: Float, eosExtraFrames: Int,
                firstChunkFrames: Int, maxChunkFrames: Int, maxTokensPerChunk: Int,
                padShortInputs: Boolean, appendTerminalPunctuation: Boolean, voiceStateCache: Int
            ): Int = 0
            override fun markReady(id: Long): Int = 0
            override fun speak(id: Long, utf8: ByteArray, sink: PcmSink): Int {
                throw RuntimeException("Fatal native signal from libpockettts_jni.so")
            }
        }

        val wrapper = PocketTtsWrapper(bridge = fakeBridge, dispatcher = Dispatchers.Unconfined)
        wrapper.initialize()

        val result = wrapper.synthesize("Trigger native crash") { _, _ -> true }
        assertTrue("Exception must be caught as Result.failure", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Fatal native signal") == true)
    }
}
