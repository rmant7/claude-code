package com.whispertranscriber.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whispertranscriber.app.databinding.ActivityLlmModelsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browses [LlmModelCatalog.SEEDS], resolved live against Hugging Face, grouped by how comfortably
 * each model is expected to run on *this* device (see [DeviceCapabilities]). Downloading works;
 * running these models is a separate, not-yet-built step (no llama.cpp/GGUF inference engine is
 * wired into the app yet) — that's stated on-screen rather than implied by a working download button.
 */
class LlmModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLlmModelsBinding
    private lateinit var llmModelManager: LlmModelManager

    private data class Resolved(val seed: LlmModelSeed, val file: ResolvedLlmFile, val fit: ModelFit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLlmModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.llm_title)

        llmModelManager = LlmModelManager(applicationContext)

        val ramMb = DeviceCapabilities.totalRamMb(applicationContext)
        binding.deviceRamText.text = getString(R.string.llm_device_ram, (ramMb / 1024))

        binding.lightweightToggle.setOnClickListener {
            toggleSection(binding.lightweightList, binding.lightweightToggle, R.string.llm_show_lightweight)
        }
        binding.advancedToggle.setOnClickListener {
            toggleSection(binding.advancedList, binding.advancedToggle, R.string.llm_show_advanced)
        }

        loadCatalog(ramMb)
    }

    private fun toggleSection(list: LinearLayout, toggle: Button, collapsedLabelRes: Int) {
        val show = list.visibility != View.VISIBLE
        list.visibility = if (show) View.VISIBLE else View.GONE
        toggle.text = if (show) getString(R.string.llm_hide) else getString(collapsedLabelRes)
    }

    private fun loadCatalog(ramMb: Long) {
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                LlmModelCatalog.SEEDS.map { seed ->
                    async {
                        runCatching { HfFileResolver.resolve(seed.repoId) }.getOrNull()?.let { file ->
                            val fit = DeviceCapabilities.classifyFit(file.sizeBytes / (1024 * 1024), ramMb)
                            Resolved(seed, file, fit)
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            binding.llmLoadingSpinner.visibility = View.GONE

            if (resolved.isEmpty()) {
                binding.llmEmptyText.visibility = View.VISIBLE
                return@launch
            }

            val recommended = resolved.filter { it.fit == ModelFit.RECOMMENDED }
            val lightweight = resolved.filter { it.fit == ModelFit.LIGHTWEIGHT }
            val advanced = resolved.filter { it.fit == ModelFit.ADVANCED || it.fit == ModelFit.TOO_LARGE }

            if (recommended.isNotEmpty()) {
                binding.recommendedHeader.visibility = View.VISIBLE
                recommended.forEach { addRow(binding.recommendedList, it) }
            }
            if (lightweight.isNotEmpty()) {
                binding.lightweightToggle.visibility = View.VISIBLE
                lightweight.forEach { addRow(binding.lightweightList, it) }
            }
            if (advanced.isNotEmpty()) {
                binding.advancedToggle.visibility = View.VISIBLE
                advanced.forEach { addRow(binding.advancedList, it) }
            }
            if (recommended.isEmpty() && lightweight.isEmpty() && advanced.isEmpty()) {
                binding.llmEmptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun addRow(container: LinearLayout, item: Resolved) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_llm_model, container, false)
        val name = row.findViewById<TextView>(R.id.llmItemName)
        val status = row.findViewById<TextView>(R.id.llmItemStatus)
        val progress = row.findViewById<ProgressBar>(R.id.llmItemProgress)
        val action = row.findViewById<Button>(R.id.llmItemAction)

        name.text = "${item.seed.provider.displayName} ${item.seed.paramsLabel}"

        fun renderIdleState() {
            val fitLabel = getString(
                when (item.fit) {
                    ModelFit.LIGHTWEIGHT -> R.string.llm_fit_lightweight
                    ModelFit.RECOMMENDED -> R.string.llm_fit_recommended
                    ModelFit.ADVANCED -> R.string.llm_fit_advanced
                    ModelFit.TOO_LARGE -> R.string.llm_fit_too_large
                }
            )
            val sizeLabel = formatSize(item.file.sizeBytes)
            val downloaded = llmModelManager.isDownloaded(item.seed)
            status.text = if (downloaded) {
                getString(R.string.llm_size, getString(R.string.llm_downloaded), sizeLabel)
            } else {
                getString(R.string.llm_size, fitLabel, sizeLabel)
            }
            progress.visibility = View.GONE
            action.isEnabled = true
            action.text = getString(if (downloaded) R.string.llm_delete else R.string.download_model)
        }
        renderIdleState()

        action.setOnClickListener {
            if (llmModelManager.isDownloaded(item.seed)) {
                llmModelManager.deleteModel(item.seed)
                renderIdleState()
                return@setOnClickListener
            }

            action.isEnabled = false
            progress.visibility = View.VISIBLE
            progress.progress = 0
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        llmModelManager.download(item.seed, item.file) { dl ->
                            val percent = if (dl.bytesTotal > 0) ((dl.bytesDownloaded * 100) / dl.bytesTotal).toInt() else 0
                            runOnUiThread {
                                progress.progress = percent
                                status.text = getString(R.string.llm_downloading, percent)
                            }
                        }
                    }
                    renderIdleState()
                } catch (e: Exception) {
                    progress.visibility = View.GONE
                    action.isEnabled = true
                    status.text = getString(R.string.llm_download_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }

        container.addView(row)
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 0.9) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024.0))
    }
}
