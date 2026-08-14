package com.whispertranscriber.app

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HfFileEntry(val path: String, val sizeBytes: Long)

data class ResolvedLlmFile(val fileName: String, val sizeBytes: Long, val downloadUrl: String)

/**
 * Resolves a Hugging Face repo id to a concrete downloadable GGUF file, live, rather than baking a
 * specific filename into the app at build time. Quantized-model repos get restructured by their
 * maintainers on a timescale of weeks — exact file names, which quant levels are offered, even
 * which repo is "the" canonical one for a given model family, all change — so resolving at runtime
 * is what keeps the catalog pointing at whatever a repo actually contains today instead of what it
 * contained when this code was written.
 */
object HfFileResolver {

    fun resolve(repoId: String): ResolvedLlmFile? {
        val entries = fetchTree(repoId)
        val best = pickBestGgufFile(entries) ?: return null
        return ResolvedLlmFile(
            fileName = best.path,
            sizeBytes = best.sizeBytes,
            downloadUrl = "https://huggingface.co/$repoId/resolve/main/${best.path}"
        )
    }

    private fun fetchTree(repoId: String): List<HfFileEntry> {
        val connection =
            URL("https://huggingface.co/api/models/$repoId/tree/main").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Hugging Face returned HTTP ${connection.responseCode} for $repoId")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseTreeResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    // internal so the JSON-shape handling (in particular, the LFS-pointer-size fallback) is directly
    // testable against fixture text without a live Hugging Face connection.
    internal fun parseTreeResponse(body: String): List<HfFileEntry> {
        val array = JSONArray(body)
        val entries = mutableListOf<HfFileEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val path = obj.optString("path", "")
            if (path.isBlank()) continue
            // Large files are stored via Git LFS; the real byte size lives under "lfs.size" rather
            // than the top-level "size" (which for an LFS file is the tiny pointer file's size).
            val lfsSize = obj.optJSONObject("lfs")?.optLong("size", -1) ?: -1
            val size = if (lfsSize >= 0) lfsSize else obj.optLong("size", 0)
            entries.add(HfFileEntry(path, size))
        }
        return entries
    }

    // Split files ("model-00001-of-00003.gguf") aren't supported yet — downloading only the first
    // part would silently produce a truncated, unusable model — so they're excluded entirely rather
    // than picked and failing later in a confusing way.
    private val SPLIT_SUFFIX = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)

    // Ordered by what actually matters for a phone: Q4_K_M is the standard quality/size sweet spot
    // for llama.cpp inference; the rest are fallbacks for repos that don't offer it.
    private val QUANT_PRIORITY = listOf("Q4_K_M", "Q4_K_S", "Q4_0", "IQ4_XS", "Q3_K_M", "Q5_K_M", "Q8_0")

    /**
     * Picks one concrete file out of everything a repo offers. Pure and separated from the network
     * call specifically so this ranking — the part most likely to need tuning as new repos and
     * naming conventions are seen in the wild — is unit-testable without a live connection.
     */
    internal fun pickBestGgufFile(candidates: List<HfFileEntry>): HfFileEntry? {
        val ggufFiles = candidates.filter {
            it.path.endsWith(".gguf", ignoreCase = true) && !SPLIT_SUFFIX.containsMatchIn(it.path)
        }
        if (ggufFiles.isEmpty()) return null

        for (quant in QUANT_PRIORITY) {
            val match = ggufFiles.firstOrNull { it.path.contains(quant, ignoreCase = true) }
            if (match != null) return match
        }
        // No recognized quant tag matched (an unfamiliar naming convention) — fall back to the
        // smallest file, since for an unknown quant level, smaller is the safer default on a phone.
        return ggufFiles.minByOrNull { it.sizeBytes }
    }
}
