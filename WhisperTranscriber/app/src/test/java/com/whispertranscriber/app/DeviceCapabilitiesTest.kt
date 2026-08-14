package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCapabilitiesTest {

    private fun classify(modelSizeMb: Long, deviceRamMb: Long) =
        DeviceCapabilities.classifyFit(modelSizeMb, deviceRamMb)

    @Test
    fun `a small model on a high-end phone is lightweight`() {
        assertEquals(ModelFit.LIGHTWEIGHT, classify(modelSizeMb = 900, deviceRamMb = 16_000))
    }

    @Test
    fun `a model sized to the recommended ceiling is recommended, not advanced`() {
        // 16000 * 0.35 = 5600
        assertEquals(ModelFit.RECOMMENDED, classify(modelSizeMb = 5600, deviceRamMb = 16_000))
    }

    @Test
    fun `a model just past the recommended ceiling is advanced`() {
        assertEquals(ModelFit.ADVANCED, classify(modelSizeMb = 5601, deviceRamMb = 16_000))
    }

    @Test
    fun `a model past the advanced ceiling is too large`() {
        // 16000 * 0.55 = 8800
        assertEquals(ModelFit.TOO_LARGE, classify(modelSizeMb = 8801, deviceRamMb = 16_000))
    }

    @Test
    fun `the same model shifts tiers as device RAM changes`() {
        val modelSizeMb = 4800L // ~Qwen3 8B at Q4_K_M
        assertEquals(ModelFit.TOO_LARGE, classify(modelSizeMb, deviceRamMb = 4_000))
        assertEquals(ModelFit.ADVANCED, classify(modelSizeMb, deviceRamMb = 8_000))
        assertEquals(ModelFit.RECOMMENDED, classify(modelSizeMb, deviceRamMb = 16_000))
        assertEquals(ModelFit.LIGHTWEIGHT, classify(modelSizeMb, deviceRamMb = 32_000))
    }

    @Test
    fun `tiers are monotonic as size grows for a fixed device`() {
        val order = listOf(ModelFit.LIGHTWEIGHT, ModelFit.RECOMMENDED, ModelFit.ADVANCED, ModelFit.TOO_LARGE)
        var lastIndex = -1
        for (sizeMb in listOf(200L, 1000L, 3000L, 5000L, 7000L, 9000L, 20000L)) {
            val tier = classify(sizeMb, deviceRamMb = 16_000)
            val index = order.indexOf(tier)
            assertEquals(true, index >= lastIndex)
            lastIndex = index
        }
    }

    @Test
    fun `non-positive inputs are treated as too large rather than crashing`() {
        assertEquals(ModelFit.TOO_LARGE, classify(modelSizeMb = 0, deviceRamMb = 16_000))
        assertEquals(ModelFit.TOO_LARGE, classify(modelSizeMb = 100, deviceRamMb = 0))
        assertEquals(ModelFit.TOO_LARGE, classify(modelSizeMb = -5, deviceRamMb = 16_000))
    }
}
