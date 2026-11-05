package com.jarvis.assistant.androllm.models

import com.jarvis.assistant.androllm.engine.support.Model
import com.jarvis.assistant.androllm.models.compatibility.CompatibilityAnalyzer
import com.jarvis.assistant.androllm.models.compatibility.CompatibilityRating
import com.jarvis.assistant.telemetry.DeviceHardwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityAnalyzerTest {

    private fun device(ramGb: Float, cores: Int = 8) = DeviceHardwareInfo(
        deviceName = "Test",
        manufacturer = "Test",
        androidVersion = "15",
        apiLevel = 35,
        abi = "arm64-v8a",
        cpuCores = cores,
        totalRamBytes = (ramGb * 1024 * 1024 * 1024).toLong(),
        availableRamBytes = 0L,
        totalRamGb = ramGb,
        freeStorageBytes = 0L,
        isVulkanSupported = false
    )

    private val model = Model(
        id = "m",
        name = "M",
        fileSize = 1024L * 1024 * 1024,
        contextLength = 4096,
        minRamGb = 4f,
        recommendedRamGb = 8f
    )

    @Test
    fun excellent_when_ram_exceeds_recommended() {
        val a = CompatibilityAnalyzer.analyze(model, device(12f))
        assertEquals(CompatibilityRating.EXCELLENT, a.rating)
        assertTrue(a.isRamSufficient)
        assertEquals(16.5f, a.estimatedTokPerSec)
    }

    @Test
    fun moderate_between_min_and_recommended() {
        val a = CompatibilityAnalyzer.analyze(model, device(6f))
        assertEquals(CompatibilityRating.MODERATE, a.rating)
        assertTrue(a.isRamSufficient)
    }

    @Test
    fun insufficient_below_min() {
        val a = CompatibilityAnalyzer.analyze(model, device(3f))
        assertEquals(CompatibilityRating.INSUFFICIENT_RAM, a.rating)
        assertFalse(a.isRamSufficient)
        assertEquals(0f, a.estimatedTokPerSec)
    }

    @Test
    fun memory_estimate_includes_kv_cache() {
        val a = CompatibilityAnalyzer.analyze(model, device(12f))
        assertEquals((model.fileSize + 4096L * 1024 * 2) / (1024 * 1024), a.estimatedMemoryMb)
    }
}
