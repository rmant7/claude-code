package com.whispertranscriber.app

import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device/emulator rather than the JVM unit test suite specifically to catch what
 * unit tests structurally can't: manifest/service registration mistakes, and crashes on the real
 * Android runtime (a plain JVM unit test would happily "pass" against Android SDK stub classes
 * that just throw "not implemented" if actually invoked). Deliberately does not exercise the
 * native whisper.cpp path — that needs a real model file and audio input, out of scope for a
 * lightweight launch smoke test.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun launchesWithoutCrashingAndStartsInAKnownState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                val transcribeButton = activity.findViewById<Button>(R.id.transcribeButton)
                assertFalse(
                    "Transcribe should start disabled until a model is downloaded and a source is picked",
                    transcribeButton.isEnabled
                )

                val fileModeRadio = activity.findViewById<RadioButton>(R.id.modeFileRadio)
                assertTrue("File mode should be selected by default", fileModeRadio.isChecked)

                val modelSpinner = activity.findViewById<Spinner>(R.id.modelSpinner)
                assertEquals(
                    "Model spinner should list every known Whisper model",
                    WhisperModel.ALL.size,
                    modelSpinner.adapter.count
                )

                val versionBanner = activity.findViewById<TextView>(R.id.versionBanner)
                assertTrue(
                    "Version banner should show the app's version name so an install can be confirmed",
                    versionBanner.text.contains(BuildConfig.VERSION_NAME)
                )

                val stopButton = activity.findViewById<Button>(R.id.stopButton)
                assertEquals(
                    "Stop should stay hidden until a batch is actually running",
                    View.GONE,
                    stopButton.visibility
                )
            }
        }
    }
}
