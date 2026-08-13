package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelTest {

    @Test
    fun `model list is non-empty`() {
        assertTrue(WhisperModel.ALL.isNotEmpty())
    }

    @Test
    fun `every model has non-blank fields`() {
        for (model in WhisperModel.ALL) {
            assertFalse("id blank for $model", model.id.isBlank())
            assertFalse("displayName blank for $model", model.displayName.isBlank())
            assertFalse("fileName blank for $model", model.fileName.isBlank())
            assertTrue("approxSizeMb must be positive for $model", model.approxSizeMb > 0)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = WhisperModel.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `file names are unique`() {
        val fileNames = WhisperModel.ALL.map { it.fileName }
        assertEquals(fileNames.size, fileNames.toSet().size)
    }

    @Test
    fun `file names follow the ggml naming convention`() {
        for (model in WhisperModel.ALL) {
            assertTrue(
                "expected ${model.fileName} to start with ggml- and end with .bin",
                model.fileName.startsWith("ggml-") && model.fileName.endsWith(".bin")
            )
        }
    }

    @Test
    fun `url is built from the huggingface repo and the file name`() {
        val model = WhisperModel.ALL.first()
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${model.fileName}",
            model.url
        )
    }
}
