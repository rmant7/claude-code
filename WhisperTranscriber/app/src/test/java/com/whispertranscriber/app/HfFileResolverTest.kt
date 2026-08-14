package com.whispertranscriber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HfFileResolverTest {

    private fun entry(path: String, sizeBytes: Long = 1_000_000L) = HfFileEntry(path, sizeBytes)

    @Test
    fun `prefers Q4_K_M when available among several quant levels`() {
        val candidates = listOf(
            entry("model-Q8_0.gguf"),
            entry("model-Q4_K_M.gguf"),
            entry("model-Q5_K_M.gguf"),
        )
        assertEquals("model-Q4_K_M.gguf", HfFileResolver.pickBestGgufFile(candidates)?.path)
    }

    @Test
    fun `falls back down the quant priority list when Q4_K_M is absent`() {
        val candidates = listOf(entry("model-Q8_0.gguf"), entry("model-Q4_0.gguf"))
        assertEquals("model-Q4_0.gguf", HfFileResolver.pickBestGgufFile(candidates)?.path)
    }

    @Test
    fun `quant matching is case-insensitive`() {
        val candidates = listOf(entry("model-q4_k_m.gguf"))
        assertEquals("model-q4_k_m.gguf", HfFileResolver.pickBestGgufFile(candidates)?.path)
    }

    @Test
    fun `excludes multi-part split files even when they look like a preferred quant`() {
        val candidates = listOf(
            entry("model-Q4_K_M-00001-of-00003.gguf"),
            entry("model-Q4_K_M-00002-of-00003.gguf"),
            entry("model-Q8_0.gguf"),
        )
        // The split files must never be picked, even though "Q4_K_M" appears in their names and
        // would otherwise win — downloading only part 1 of 3 would silently be a broken model.
        assertEquals("model-Q8_0.gguf", HfFileResolver.pickBestGgufFile(candidates)?.path)
    }

    @Test
    fun `ignores non-gguf files entirely`() {
        val candidates = listOf(entry("README.md"), entry("config.json"), entry("model-Q4_K_M.gguf"))
        assertEquals("model-Q4_K_M.gguf", HfFileResolver.pickBestGgufFile(candidates)?.path)
    }

    @Test
    fun `falls back to the smallest file when no known quant tag matches`() {
        val candidates = listOf(
            entry("model-fp16.gguf", sizeBytes = 9_000_000L),
            entry("model-mystery-quant.gguf", sizeBytes = 2_000_000L),
        )
        assertEquals("model-mystery-quant.gguf", HfFileResolver.pickBestGgufFile(candidates)?.path)
    }

    @Test
    fun `returns null when there are no gguf files at all`() {
        assertNull(HfFileResolver.pickBestGgufFile(listOf(entry("README.md"), entry("config.json"))))
    }

    @Test
    fun `returns null for an empty file list`() {
        assertNull(HfFileResolver.pickBestGgufFile(emptyList()))
    }

    @Test
    fun `parses a tree response using the LFS pointer size when present`() {
        val json = """
            [
              {"type": "file", "path": "README.md", "size": 512},
              {"type": "file", "path": "model-Q4_K_M.gguf", "size": 134,
               "lfs": {"oid": "abc", "size": 4823456789, "pointerSize": 134}}
            ]
        """.trimIndent()
        val entries = HfFileResolver.parseTreeResponse(json)
        val ggufEntry = entries.first { it.path == "model-Q4_K_M.gguf" }
        assertEquals(4823456789L, ggufEntry.sizeBytes)
        val readme = entries.first { it.path == "README.md" }
        assertEquals(512L, readme.sizeBytes)
    }

    @Test
    fun `parses a tree response with no LFS files`() {
        val json = """[{"type": "file", "path": "config.json", "size": 700}]"""
        val entries = HfFileResolver.parseTreeResponse(json)
        assertEquals(700L, entries.single().sizeBytes)
    }

    @Test
    fun `skips entries with no path (directories, malformed rows)`() {
        val json = """
            [
              {"type": "directory", "path": ""},
              {"type": "file", "path": "model.gguf", "size": 100}
            ]
        """.trimIndent()
        assertEquals(1, HfFileResolver.parseTreeResponse(json).size)
    }
}
