package com.whispertranscriber.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioDecoderTest {

    @Test
    fun `downmix averages channels per frame`() {
        val stereo = shortArrayOf(10, 20, 30, 40, -10, -20)
        val mono = AudioDecoder.downmixToMono(stereo, channels = 2)
        assertArrayEquals(shortArrayOf(15, 35, -15), mono)
    }

    @Test
    fun `downmix handles three channels`() {
        val triple = shortArrayOf(3, 6, 9, 12, 15, 18)
        val mono = AudioDecoder.downmixToMono(triple, channels = 3)
        assertArrayEquals(shortArrayOf(6, 15), mono)
    }

    @Test
    fun `resample is a no-op when rates already match`() {
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)
        val result = AudioDecoder.resample(samples, fromRate = 16000, toRate = 16000)
        assertSame(samples, result)
    }

    @Test
    fun `resample is a no-op on empty input`() {
        val result = AudioDecoder.resample(FloatArray(0), fromRate = 8000, toRate = 16000)
        assertEquals(0, result.size)
    }

    @Test
    fun `upsampling roughly doubles the sample count`() {
        val samples = FloatArray(100) { it / 100f }
        val result = AudioDecoder.resample(samples, fromRate = 8000, toRate = 16000)
        assertEquals(200, result.size)
    }

    @Test
    fun `downsampling roughly halves the sample count`() {
        val samples = FloatArray(200) { it / 200f }
        val result = AudioDecoder.resample(samples, fromRate = 16000, toRate = 8000)
        assertEquals(100, result.size)
    }

    @Test
    fun `resample preserves the first sample value`() {
        val samples = floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f)
        val result = AudioDecoder.resample(samples, fromRate = 8000, toRate = 16000)
        assertEquals(0.5f, result[0], 1e-6f)
    }
}
