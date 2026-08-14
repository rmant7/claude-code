package com.whispertranscriber.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.whispertranscriber.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class Mode { FILE, URL, FOLDER }

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private lateinit var resultsAdapter: ResultsAdapter

    private val results = mutableListOf<TranscriptionResult>()
    private var batchRunning = false

    private var currentMode = Mode.FILE
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var selectedFolderFiles: List<DocumentFile> = emptyList()

    private val selectedModel: WhisperModel
        get() = WhisperModel.ALL[binding.modelSpinner.selectedItemPosition]

    private val selectedLanguage: WhisperLanguage
        get() = WhisperLanguage.ALL[binding.languageSpinner.selectedItemPosition]

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedFileUri = uri
            selectedFileName = uri.lastPathSegment ?: uri.toString()
            binding.selectedFileText.text = selectedFileName
            updateTranscribeButtonState()
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val tree = DocumentFile.fromTreeUri(this, uri)
            val files = tree?.let { MediaFileUtils.listMediaFilesRecursively(it) }.orEmpty()
            selectedFolderFiles = files
            binding.selectedFolderText.text = getString(R.string.folder_files_found, files.size)
            updateTranscribeButtonState()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* work proceeds either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(applicationContext)

        setUpModelSpinner()
        setUpLanguageSpinner()
        setUpSourceModeSwitcher()
        setUpResultsList()
        observeDownloadState()
        observeTranscriptionState()

        binding.downloadButton.setOnClickListener { startModelDownload() }
        binding.deleteButton.setOnClickListener {
            modelManager.deleteModel(selectedModel)
            refreshModelStatus()
        }
        binding.pickFileButton.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        binding.pickFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.transcribeButton.setOnClickListener { onTranscribeClicked() }
        binding.stopButton.setOnClickListener { onStopClicked() }
        binding.copyAllButton.setOnClickListener { copyAllResults() }
        binding.shareAllButton.setOnClickListener { shareAllResults() }
        binding.viewTranscriptsButton.setOnClickListener {
            startActivity(Intent(this, TranscriptsActivity::class.java))
        }

        binding.versionBanner.text = getString(
            R.string.version_banner, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER
        )

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateTranscribeButtonState()
        })

        refreshModelStatus()
    }

    private fun setUpModelSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            WhisperModel.ALL.map { "${it.displayName} (~${it.approxSizeMb} MB)" }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter

        // Re-picking the same (often large, slow-to-download) model on every single launch is
        // needless friction, so the last choice is remembered across restarts.
        val lastModelId = getPreferences(Context.MODE_PRIVATE).getString(PREF_LAST_MODEL_ID, null)
        val lastPosition = WhisperModel.ALL.indexOfFirst { it.id == lastModelId }
        if (lastPosition >= 0) {
            binding.modelSpinner.setSelection(lastPosition)
        }

        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putString(PREF_LAST_MODEL_ID, WhisperModel.ALL[position].id)
                    .apply()
                refreshModelStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setUpLanguageSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            WhisperLanguage.ALL.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter

        val lastCode = getPreferences(Context.MODE_PRIVATE).getString(PREF_LAST_LANGUAGE_CODE, null)
        val lastPosition = WhisperLanguage.ALL.indexOfFirst { it.code == lastCode }
        if (lastPosition >= 0) {
            binding.languageSpinner.setSelection(lastPosition)
        }

        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putString(PREF_LAST_LANGUAGE_CODE, WhisperLanguage.ALL[position].code)
                    .apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setUpSourceModeSwitcher() {
        binding.sourceModeGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            currentMode = when (checkedId) {
                R.id.modeUrlRadio -> Mode.URL
                R.id.modeFolderRadio -> Mode.FOLDER
                else -> Mode.FILE
            }
            binding.fileModeLayout.visibility = if (currentMode == Mode.FILE) View.VISIBLE else View.GONE
            binding.urlModeLayout.visibility = if (currentMode == Mode.URL) View.VISIBLE else View.GONE
            binding.folderModeLayout.visibility = if (currentMode == Mode.FOLDER) View.VISIBLE else View.GONE
            updateTranscribeButtonState()
        }
    }

    private fun setUpResultsList() {
        resultsAdapter = ResultsAdapter(results)
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = resultsAdapter
    }

    /** Reflects [ModelDownloadService]'s progress, which keeps running while this Activity is gone. */
    private fun observeDownloadState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadState.status.collect { status ->
                    when (status) {
                        is ModelDownloadState.Status.Downloading -> {
                            binding.downloadButton.isEnabled = false
                            binding.downloadProgressBar.visibility = View.VISIBLE
                            binding.downloadProgressBar.progress = status.percent
                        }

                        is ModelDownloadState.Status.Completed -> {
                            binding.downloadProgressBar.visibility = View.GONE
                            refreshModelStatus()
                            Toast.makeText(this@MainActivity, R.string.download_complete, Toast.LENGTH_SHORT).show()
                        }

                        is ModelDownloadState.Status.Failed -> {
                            binding.downloadProgressBar.visibility = View.GONE
                            refreshModelStatus()
                            Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
                        }

                        ModelDownloadState.Status.Idle -> {
                            binding.downloadProgressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    /**
     * Reflects [TranscriptionService]'s progress. Because the batch runs in a foreground service
     * rather than a coroutine tied to this Activity, reopening the app after it was backgrounded
     * (even overnight) re-attaches to whatever the service has already published instead of
     * finding a blank slate.
     */
    private fun observeTranscriptionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TranscriptionState.status.collect { status ->
                    when (status) {
                        TranscriptionState.Status.Idle -> {
                            batchRunning = false
                            binding.stopButton.visibility = View.GONE
                            updateTranscribeButtonState()
                        }

                        is TranscriptionState.Status.Running -> {
                            batchRunning = true
                            results.clear()
                            results.addAll(status.results)
                            resultsAdapter.notifyDataSetChanged()

                            binding.resultsSection.visibility = View.VISIBLE
                            binding.batchProgressText.visibility = View.VISIBLE
                            binding.batchProgressText.text = if (status.loadingModel) {
                                getString(R.string.loading_model_notification)
                            } else {
                                getString(R.string.batch_progress, status.currentIndex + 1, status.total)
                            }
                            binding.transcribeProgressBar.visibility = View.VISIBLE
                            binding.transcribeProgressBar.progress = (status.currentIndex * 100) / status.total
                            binding.stopButton.visibility = View.VISIBLE
                            binding.stopButton.isEnabled = !TranscriptionControl.stopRequested.get()
                            binding.stopButton.setText(
                                if (TranscriptionControl.stopRequested.get()) R.string.stopping else R.string.stop
                            )
                            binding.copyAllButton.isEnabled = false
                            binding.shareAllButton.isEnabled = false
                            updateTranscribeButtonState()
                        }

                        is TranscriptionState.Status.Finished -> {
                            batchRunning = false
                            results.clear()
                            results.addAll(status.results)
                            resultsAdapter.notifyDataSetChanged()

                            binding.resultsSection.visibility = View.VISIBLE
                            binding.batchProgressText.visibility = View.GONE
                            binding.transcribeProgressBar.visibility = View.GONE
                            binding.stopButton.visibility = View.GONE
                            val anyDone = status.results.any {
                                it.status == TranscriptionResult.Status.DONE ||
                                    it.status == TranscriptionResult.Status.CANCELLED
                            }
                            binding.copyAllButton.isEnabled = anyDone
                            binding.shareAllButton.isEnabled = anyDone
                            updateTranscribeButtonState()
                        }
                    }
                }
            }
        }
    }

    private fun refreshModelStatus() {
        val downloaded = modelManager.isDownloaded(selectedModel)
        binding.modelStatusText.text = getString(
            if (downloaded) R.string.status_downloaded else R.string.status_not_downloaded
        )
        binding.downloadButton.isEnabled = !downloaded
        binding.deleteButton.isEnabled = downloaded
        updateTranscribeButtonState()
    }

    private fun updateTranscribeButtonState() {
        val modelReady = modelManager.isDownloaded(selectedModel)
        val hasSource = when (currentMode) {
            Mode.FILE -> selectedFileUri != null
            Mode.URL -> binding.urlInput.text?.toString()?.isNotBlank() == true
            Mode.FOLDER -> selectedFolderFiles.isNotEmpty()
        }
        binding.transcribeButton.isEnabled = modelReady && hasSource && !batchRunning
    }

    private fun startModelDownload() {
        val model = selectedModel
        requestNotificationPermissionIfNeeded()

        binding.downloadButton.isEnabled = false
        binding.downloadProgressBar.visibility = View.VISIBLE
        binding.downloadProgressBar.progress = 0
        ModelDownloadService.start(applicationContext, model)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onTranscribeClicked() {
        val model = selectedModel
        val language = selectedLanguage.code
        requestNotificationPermissionIfNeeded()

        when (currentMode) {
            Mode.FILE -> {
                val uri = selectedFileUri ?: return
                TranscriptionService.startForFiles(
                    applicationContext, model, listOf(MediaSource.LocalFile(uri, selectedFileName)), language
                )
            }

            Mode.URL -> {
                val url = binding.urlInput.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) return
                TranscriptionService.startForUrl(applicationContext, model, url, language)
            }

            Mode.FOLDER -> {
                if (selectedFolderFiles.isEmpty()) return
                val sources = selectedFolderFiles.map { doc ->
                    MediaSource.LocalFile(doc.uri, doc.name ?: doc.uri.lastPathSegment ?: "file")
                }
                TranscriptionService.startForFiles(applicationContext, model, sources, language)
            }
        }

        batchRunning = true
        updateTranscribeButtonState()
    }

    private fun onStopClicked() {
        TranscriptionControl.requestStop()
        binding.stopButton.isEnabled = false
        binding.stopButton.setText(R.string.stopping)
    }

    private fun buildCombinedText(): String =
        results.filter {
            it.status == TranscriptionResult.Status.DONE || it.status == TranscriptionResult.Status.CANCELLED
        }.joinToString("\n\n") { r ->
            "${r.source.displayName}:\n${r.text.ifBlank { getString(R.string.no_speech_detected) }}"
        }

    private fun copyAllResults() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcripts", buildCombinedText()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAllResults() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildCombinedText())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_all)))
    }

    private companion object {
        const val PREF_LAST_MODEL_ID = "last_model_id"
        const val PREF_LAST_LANGUAGE_CODE = "last_language_code"
    }
}
