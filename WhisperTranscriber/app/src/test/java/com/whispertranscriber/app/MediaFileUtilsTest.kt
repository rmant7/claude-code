package com.whispertranscriber.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileUtilsTest {

    @Test
    fun `recognizes common audio extensions`() {
        for (name in listOf("song.mp3", "voice.wav", "note.m4a", "clip.aac", "track.flac", "sample.ogg", "memo.amr")) {
            assertTrue("expected $name to be recognized", MediaFileUtils.isMediaFile(name))
        }
    }

    @Test
    fun `recognizes common video extensions since only the audio track is used`() {
        for (name in listOf("movie.mp4", "clip.mov", "call.3gp", "stream.webm", "video.mkv")) {
            assertTrue("expected $name to be recognized", MediaFileUtils.isMediaFile(name))
        }
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(MediaFileUtils.isMediaFile("SONG.MP3"))
        assertTrue(MediaFileUtils.isMediaFile("Voice.Wav"))
    }

    @Test
    fun `rejects unrelated extensions`() {
        for (name in listOf("document.pdf", "photo.jpg", "archive.zip", "notes.txt")) {
            assertFalse("expected $name to be rejected", MediaFileUtils.isMediaFile(name))
        }
    }

    @Test
    fun `rejects files with no extension`() {
        assertFalse(MediaFileUtils.isMediaFile("README"))
        assertFalse(MediaFileUtils.isMediaFile(""))
    }

    @Test
    fun `only the final extension counts for a dotted filename`() {
        assertTrue(MediaFileUtils.isMediaFile("recording.2024.01.01.mp3"))
        assertFalse(MediaFileUtils.isMediaFile("recording.mp3.txt"))
    }
}
