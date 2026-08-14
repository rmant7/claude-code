package com.whispertranscriber.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes an audio or video source into mono 16 kHz float PCM, the input format whisper.cpp
 * expects. Android's MediaExtractor/MediaCodec natively demux most common audio and video
 * containers (mp3, wav, m4a/aac, flac, ogg/opus, amr, mp4, 3gp, webm/mkv, …); for video only the
 * audio track is selected and decoded, video frames are ignored.
 *
 * Decoding is **streaming**: PCM is handed back in chunks as it is produced, rather than
 * accumulated into one array at the end. That matters for three reasons — transcription of chunk N
 * can start while chunk N+1 is still being decoded (so the first text appears in seconds instead of
 * after the whole file is decoded), peak memory stays bounded by the chunk size instead of scaling
 * with file length, and an http(s) source can be fed straight to MediaExtractor, which downloads it
 * progressively, so there is no separate "download the whole file first" phase at all.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16000

    /**
     * Whisper's own analysis window is 30s and it pads shorter input up to that length, so chunking
     * on the same boundary is what keeps chunked decoding from wasting inference work.
     */
    const val DEFAULT_CHUNK_SECONDS = 30

    private const val TIMEOUT_US = 10_000L

    /** Streams [uri] (a content:// or file:// URI) as 16 kHz mono float PCM chunks. */
    fun streamFromUri(
        context: Context,
        uri: Uri,
        chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
        onProgress: ((Int) -> Unit)? = null,
        onChunk: (FloatArray) -> Unit
    ) = decodeStreaming({ it.setDataSource(context, uri, null) }, chunkSeconds, onProgress, onChunk)

    /**
     * Streams [source] — a local file path *or* an http(s) URL — as 16 kHz mono float PCM chunks.
     * For a URL, MediaExtractor fetches progressively, so decoding starts almost immediately
     * instead of after a full download.
     */
    fun streamFromPathOrUrl(
        source: String,
        chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
        onProgress: ((Int) -> Unit)? = null,
        onChunk: (FloatArray) -> Unit
    ) = decodeStreaming({ it.setDataSource(source) }, chunkSeconds, onProgress, onChunk)

    /** Decodes a local file fully into one array. Retained for tests and one-shot callers. */
    fun decodeFromPath(path: String, onProgress: ((Int) -> Unit)? = null): FloatArray {
        val chunks = mutableListOf<FloatArray>()
        streamFromPathOrUrl(path, onProgress = onProgress) { chunks.add(it) }
        return concat(chunks)
    }

    /** Decodes a URI fully into one array. Retained for tests and one-shot callers. */
    fun decodeToPcm16k(context: Context, uri: Uri, onProgress: ((Int) -> Unit)? = null): FloatArray {
        val chunks = mutableListOf<FloatArray>()
        streamFromUri(context, uri, onProgress = onProgress) { chunks.add(it) }
        return concat(chunks)
    }

    private fun concat(chunks: List<FloatArray>): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }

    private fun decodeStreaming(
        setSource: (MediaExtractor) -> Unit,
        chunkSeconds: Int,
        onProgress: ((Int) -> Unit)?,
        onChunk: (FloatArray) -> Unit
    ) {
        val extractor = MediaExtractor()
        try {
            setSource(extractor)
        } catch (e: Exception) {
            extractor.release()
            // MediaExtractor's own message here is the near-useless "Failed to instantiate
            // extractor", which says nothing about *why*. By far the most common cause in practice
            // is being handed something that isn't a media stream at all — a web page URL rather
            // than a direct media link — so name that explicitly.
            throw IOException(
                "Could not read this as an audio/video stream. If this is a link, it must point " +
                    "directly at a media file, not at a web page that plays one.",
                e
            )
        }

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
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IOException("No audio track found in this file")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sourceSampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        val sourceChannels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            -1L
        }

        // Chunk size is measured in interleaved source-rate samples, and must stay a whole number
        // of frames so downmixing never splits a frame across two chunks.
        val chunkFrames = chunkSeconds.coerceAtLeast(1) * sourceSampleRate
        val chunkSamples = chunkFrames * sourceChannels

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pending = PcmBuffer()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var lastReportedPercent = -1
        var emittedAnything = false

        fun emit(sampleCount: Int) {
            if (sampleCount <= 0) return
            val raw = pending.take(sampleCount)
            val mono = if (sourceChannels > 1) downmixToMono(raw, sourceChannels) else raw
            val floats = FloatArray(mono.size) { mono[it] / 32768.0f }
            val resampled = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                resample(floats, sourceSampleRate, TARGET_SAMPLE_RATE)
            } else {
                floats
            }
            if (resampled.isNotEmpty()) {
                emittedAnything = true
                onChunk(resampled)
            }
        }

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
                        pending.append(shortBuffer)
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

                while (pending.size >= chunkSamples) {
                    emit(chunkSamples)
                }
            }
            // Whatever is left over after the last full chunk is still real audio, so it gets its
            // own (short) final chunk rather than being dropped.
            emit(pending.size)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        if (!emittedAnything) {
            throw IOException("This file contained no decodable audio")
        }
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    /**
     * Growable short buffer with cheap head removal, so completed chunks can be handed off without
     * copying the not-yet-full remainder around on every append.
     */
    private class PcmBuffer {
        private var data = ShortArray(INITIAL_CAPACITY)
        private var start = 0
        private var end = 0

        val size: Int get() = end - start

        fun append(source: java.nio.ShortBuffer) {
            val count = source.remaining()
            ensureRoom(count)
            source.get(data, end, count)
            end += count
        }

        fun take(count: Int): ShortArray {
            val n = count.coerceAtMost(size)
            val out = ShortArray(n)
            data.copyInto(out, 0, start, start + n)
            start += n
            if (start == end) {
                start = 0
                end = 0
            }
            return out
        }

        private fun ensureRoom(count: Int) {
            if (end + count <= data.size) return
            // Reclaim the already-consumed head first; only grow if that isn't enough.
            if (size + count <= data.size) {
                data.copyInto(data, 0, start, end)
            } else {
                var capacity = data.size
                while (capacity < size + count) capacity *= 2
                val grown = ShortArray(capacity)
                data.copyInto(grown, 0, start, end)
                data = grown
            }
            end = size
            start = 0
        }

        private companion object {
            const val INITIAL_CAPACITY = 1 shl 16
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
