package com.whispertranscriber.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.whispertranscriber.app.databinding.ActivityLiveTranscriptionBinding
import kotlinx.coroutines.launch

/**
 * Dictation screen: hold a conversation with the microphone and watch the transcript build up.
 *
 * The recording itself lives in [LiveTranscriptionService], so this Activity only renders state and
 * sends start/stop — closing or backgrounding the screen doesn't interrupt a dictation in progress.
 */
class LiveTranscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTranscriptionBinding
    private lateinit var modelManager: ModelManager

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startDictation()
            } else {
                Toast.makeText(this, R.string.live_needs_mic, Toast.LENGTH_LONG).show()
            }
        }

    // Must be registered as a field: registerForActivityResult() throws if called once the Activity
    // is already started, so it cannot be created lazily inside a click handler.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* dictation runs either way */ }

    private val selectedModel: WhisperModel?
        get() {
            val prefs = getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString(PREF_LAST_MODEL_ID, null)
            return WhisperModel.ALL.firstOrNull { it.id == id } ?: WhisperModel.ALL.firstOrNull()
        }

    private val selectedLanguage: String
        get() = getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE)
            .getString(PREF_LAST_LANGUAGE_CODE, null) ?: WhisperLanguage.AUTO_CODE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTranscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.live_title)

        modelManager = ModelManager(applicationContext)

        binding.micButton.setOnClickListener { onMicClicked() }
        binding.liveCopyButton.setOnClickListener { copyTranscript() }
        binding.liveShareButton.setOnClickListener { shareTranscript() }
        binding.liveClearButton.setOnClickListener { LiveTranscriptionState.reset() }

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveTranscriptionState.status.collect { status ->
                    binding.liveText.text = status.displayText
                    if (status.displayText.isNotEmpty()) {
                        binding.liveScroll.post { binding.liveScroll.fullScroll(android.view.View.FOCUS_DOWN) }
                    }

                    binding.micButton.setText(if (status.isRecording) R.string.live_stop else R.string.live_start)
                    binding.liveStatusText.text = when {
                        status.error != null -> getString(R.string.transcribe_error, status.error)
                        status.isLoadingModel -> getString(R.string.loading_model_notification)
                        status.isRecording -> getString(R.string.live_listening)
                        else -> getString(R.string.live_idle)
                    }

                    val hasText = status.displayText.isNotBlank()
                    binding.liveCopyButton.isEnabled = hasText
                    binding.liveShareButton.isEnabled = hasText
                    binding.liveClearButton.isEnabled = hasText && !status.isRecording
                }
            }
        }
    }

    private fun onMicClicked() {
        if (LiveTranscriptionState.status.value.isRecording) {
            LiveTranscriptionService.stop(applicationContext)
            return
        }

        val model = selectedModel
        if (model == null || !modelManager.isDownloaded(model)) {
            Toast.makeText(this, R.string.live_needs_model, Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startDictation()
    }

    private fun startDictation() {
        val model = selectedModel ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The dictation runs in a foreground service, which shows a notification; it still
            // works without this permission, the notification is just suppressed.
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        LiveTranscriptionService.start(applicationContext, model, selectedLanguage)
    }

    private fun copyTranscript() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", LiveTranscriptionState.status.value.displayText))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareTranscript() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, LiveTranscriptionState.status.value.displayText)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_all)))
    }

    private companion object {
        // MainActivity persists its spinners with Activity#getPreferences(), which names the file
        // after that Activity — so reading the same choices here means naming it explicitly.
        const val MAIN_PREFS = "MainActivity"
        const val PREF_LAST_MODEL_ID = "last_model_id"
        const val PREF_LAST_LANGUAGE_CODE = "last_language_code"
    }
}
