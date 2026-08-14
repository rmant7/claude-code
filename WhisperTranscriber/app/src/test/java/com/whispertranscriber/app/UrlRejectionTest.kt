package com.whispertranscriber.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A player-page link (the exact case that produced a bare "Failed to instantiate extractor" for a
 * user) must be rejected up front with a message that explains itself, rather than being fetched
 * and handed to a decoder that can only report that it isn't media.
 */
class UrlRejectionTest {

    @Test
    fun `rejects youtube share links in both forms`() {
        for (url in listOf(
            "https://youtu.be/0hZ9MDzwDBI?feature=shared",
            "https://www.youtube.com/watch?v=0hZ9MDzwDBI",
            "http://m.youtube.com/watch?v=abc",
        )) {
            assertNotNull("should reject $url", UrlDownloader.rejectionReason(url))
        }
    }

    @Test
    fun `rejection message names the site and says what to do instead`() {
        val reason = UrlDownloader.rejectionReason("https://youtu.be/0hZ9MDzwDBI?feature=shared")
        assertNotNull(reason)
        assertTrue("should name the host, was: $reason", reason!!.contains("youtu.be"))
        assertTrue("should suggest an alternative, was: $reason", reason.contains("pick the file"))
    }

    @Test
    fun `rejects other known player-page hosts`() {
        for (url in listOf(
            "https://vimeo.com/123456",
            "https://www.tiktok.com/@u/video/1",
            "https://x.com/u/status/1",
        )) {
            assertNotNull("should reject $url", UrlDownloader.rejectionReason(url))
        }
    }

    @Test
    fun `allows direct media links`() {
        for (url in listOf(
            "https://example.com/audio.mp3",
            "https://cdn.example.org/path/recording.m4a?token=xyz",
            "http://192.168.1.5:8080/clip.wav",
            "https://notyoutube.com/a.mp3",
        )) {
            assertNull("should allow $url", UrlDownloader.rejectionReason(url))
        }
    }

    @Test
    fun `host matching is not fooled by a lookalike domain`() {
        // youtube.com.evil.example is a different host and must not match by naive substring.
        assertNull(UrlDownloader.rejectionReason("https://youtube.com.evil.example/a.mp3"))
    }
}
