package com.whispertranscriber.app

import android.content.Context
import java.io.File

/** Storage and download for LLM (GGUF) models — the text-model counterpart to [ModelManager]. */
class LlmModelManager(private val context: Context) {

    fun modelsDir(): File = File(context.getExternalFilesDir(null), "llm_models").apply { mkdirs() }

    // Keyed by the catalog seed's stable id rather than the Hugging Face filename, so a file already
    // on disk is still found even if HfFileResolver later resolves a differently-named file for the
    // same seed (e.g. a repo renaming its quant files).
    fun localFile(seed: LlmModelSeed): File = File(modelsDir(), "${seed.id}.gguf")

    fun isDownloaded(seed: LlmModelSeed): Boolean {
        val file = localFile(seed)
        return file.exists() && file.length() > 10_000_000L
    }

    fun deleteModel(seed: LlmModelSeed) {
        localFile(seed).delete()
    }

    fun download(seed: LlmModelSeed, resolved: ResolvedLlmFile, onProgress: (ResumableDownloader.Progress) -> Unit) {
        val destination = localFile(seed)
        val tempFile = File(modelsDir(), "${seed.id}.gguf.part")
        ResumableDownloader.download(resolved.downloadUrl, destination, tempFile, onProgress)
    }
}
