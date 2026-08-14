package com.whispertranscriber.app

import android.app.ActivityManager
import android.content.Context

/** How comfortably a model of a given size is expected to run on this device. */
enum class ModelFit { LIGHTWEIGHT, RECOMMENDED, ADVANCED, TOO_LARGE }

object DeviceCapabilities {

    fun totalRamMb(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /**
     * Classifies a model's weight size against total device RAM. Weights are only part of an LLM's
     * memory use — KV cache, activations, and the OS/app itself all compete for the rest — so the
     * thresholds leave generous headroom rather than assuming the weights are all that has to fit.
     *
     * Pure function (no Context) so the tiering logic is directly unit-testable across a range of
     * device sizes without needing a real ActivityManager.
     */
    internal fun classifyFit(modelSizeMb: Long, deviceRamMb: Long): ModelFit {
        if (deviceRamMb <= 0 || modelSizeMb <= 0) return ModelFit.TOO_LARGE
        val recommendedMax = (deviceRamMb * RECOMMENDED_RAM_FRACTION).toLong()
        val advancedMax = (deviceRamMb * ADVANCED_RAM_FRACTION).toLong()
        val lightweightMax = (recommendedMax * LIGHTWEIGHT_OF_RECOMMENDED_FRACTION).toLong()
        return when {
            modelSizeMb <= lightweightMax -> ModelFit.LIGHTWEIGHT
            modelSizeMb <= recommendedMax -> ModelFit.RECOMMENDED
            modelSizeMb <= advancedMax -> ModelFit.ADVANCED
            else -> ModelFit.TOO_LARGE
        }
    }

    // Weights alone should stay under ~35% of total RAM to leave comfortable room for KV cache,
    // activations, and everything else already running.
    private const val RECOMMENDED_RAM_FRACTION = 0.35
    // Up to ~55% is "will probably run, but slowly and with little other headroom" territory.
    private const val ADVANCED_RAM_FRACTION = 0.55
    // Meaningfully smaller than the comfortable ceiling, not just under it, to actually read as fast.
    private const val LIGHTWEIGHT_OF_RECOMMENDED_FRACTION = 0.4
}
