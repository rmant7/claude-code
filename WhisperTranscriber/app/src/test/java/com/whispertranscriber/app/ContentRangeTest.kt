package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRangeTest {

    @Test
    fun `parses a standard Content-Range header`() {
        assertEquals(9999L, parseContentRangeTotal("bytes 1234-5678/9999"))
    }

    @Test
    fun `parses when the range starts at zero`() {
        assertEquals(500L, parseContentRangeTotal("bytes 0-99/500"))
    }

    @Test
    fun `returns null for a missing header`() {
        assertNull(parseContentRangeTotal(null))
    }

    @Test
    fun `returns null when there is no slash`() {
        assertNull(parseContentRangeTotal("bytes 1234-5678"))
    }

    @Test
    fun `returns null when the total is not a number`() {
        assertNull(parseContentRangeTotal("bytes 1234-5678/*"))
    }
}
