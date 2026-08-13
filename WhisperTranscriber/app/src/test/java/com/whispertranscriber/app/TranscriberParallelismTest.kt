package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriberParallelismTest {

    @Test
    fun `short clip stays single-processor to avoid degenerate chunks`() {
        val (numProcessors, threadsPerProcessor) = computeParallelism(
            durationSeconds = 4f, coreBudget = 4, minChunkSeconds = 30
        )
        assertEquals(1, numProcessors)
        assertEquals(4, threadsPerProcessor)
    }

    @Test
    fun `clip exactly at the chunk boundary stays single-processor`() {
        val (numProcessors, _) = computeParallelism(
            durationSeconds = 30f, coreBudget = 4, minChunkSeconds = 30
        )
        assertEquals(1, numProcessors)
    }

    @Test
    fun `long clip splits across the full core budget`() {
        val (numProcessors, threadsPerProcessor) = computeParallelism(
            durationSeconds = 600f, coreBudget = 4, minChunkSeconds = 30
        )
        assertEquals(4, numProcessors)
        assertEquals(1, threadsPerProcessor)
    }

    @Test
    fun `moderately long clip only splits as far as chunk size allows`() {
        val (numProcessors, _) = computeParallelism(
            durationSeconds = 65f, coreBudget = 4, minChunkSeconds = 30
        )
        assertEquals(2, numProcessors)
    }

    @Test
    fun `single-core device never splits`() {
        val (numProcessors, threadsPerProcessor) = computeParallelism(
            durationSeconds = 600f, coreBudget = 1, minChunkSeconds = 30
        )
        assertEquals(1, numProcessors)
        assertEquals(1, threadsPerProcessor)
    }

    @Test
    fun `total threads never exceed the core budget`() {
        for (duration in listOf(1f, 10f, 30f, 45f, 90f, 300f, 3600f)) {
            for (coreBudget in 1..4) {
                val (numProcessors, threadsPerProcessor) =
                    computeParallelism(duration, coreBudget, minChunkSeconds = 30)
                assertTrue(
                    "processors=$numProcessors threads=$threadsPerProcessor budget=$coreBudget duration=$duration",
                    numProcessors * threadsPerProcessor <= coreBudget
                )
            }
        }
    }
}
