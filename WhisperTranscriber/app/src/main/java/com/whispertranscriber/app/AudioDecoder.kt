package com.whispertranscriber.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes an audio or video file into mono 16 kHz float PCM samples, the input format
 * expected by whisper.cpp. Android's MediaExtractor/MediaCodec natively demux most common
 * audio and video containers (mp3, wav, m4a/aac, flac, ogg/opus, amr, mp4, 3gp, webm/mkv, …);
 * for video files only the audio track is selected and decoded, video frames are ignored.
 */
object AudioDecoder {

    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    fun decodeToPcm16k(context: Context, uri: Uri, onProgress: ((Int) -> Unit)? = null): FloatArray {
        val tempFile = copyUriToTempFile(context, uri)
        return try {
            decodeFile(tempFile.absolutePath, onProgress)
        } finally {
            tempFile.delete()
        }
    }

    /** Same as [decodeToPcm16k] but for a file already sitting on local disk (e.g. a downloaded URL). */
    fun decodeFromPath(path: String, onProgress: ((Int) -> Unit)? = null): FloatArray = decodeFile(path, onProgress)

    private fun copyUriToTempFile(context: Context, uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open selected file")
        val temp = File.createTempFile("audio", ".amr", context.cacheDir)
        input.use { inStream ->
            FileOutputStream(temp).use { out -> inStream.copyTo(out) }
        }
        return temp
    }

    private fun decodeFile(path: String, onProgress: ((Int) -> Unit)? = null): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "No audio track found in the selected file" }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            TARGET_SAMPLE_RATE
        }
        val sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            -1L
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmChunks = mutableListOf<ShortArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var lastReportedPercent = -1

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(chunk)
                        pcmChunks.add(chunk)
                    }
                    if (durationUs > 0 && onProgress != null) {
                        val percent = ((bufferInfo.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        require(pcmChunks.isNotEmpty()) { "The file contained no decodable audio" }

        val totalSamples = pcmChunks.sumOf { it.size }
        val pcm = ShortArray(totalSamples)
        var offset = 0
        for (chunk in pcmChunks) {
            chunk.copyInto(pcm, offset)
            offset += chunk.size
        }

        val mono = if (sourceChannels > 1) downmixToMono(pcm, sourceChannels) else pcm
        val floatSamples = FloatArray(mono.size) { mono[it] / 32768.0f }

        return if (sourceSampleRate != TARGET_SAMPLE_RATE) {
            resample(floatSamples, sourceSampleRate, TARGET_SAMPLE_RATE)
        } else {
            floatSamples
        }
    }

    // internal (not private) so unit tests can exercise this pure math directly, without needing
    // a real MediaCodec/device to produce a ShortArray to feed it.
    internal fun downmixToMono(pcm: ShortArray, channels: Int): ShortArray {
        val frames = pcm.size / channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    internal fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputLength = (input.size * ratio).toInt()
        val output = FloatArray(outputLength)
        for (i in output.indices) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = input.getOrElse(idx) { 0f }
            val b = input.getOrElse(idx + 1) { a }
            output[i] = a + (b - a) * frac
        }
        return output
    }
}
