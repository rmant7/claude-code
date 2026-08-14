package com.whispertranscriber.app

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch-only check for the dictation screen: it must reach RESUMED and register in the manifest.
 * Recording itself needs the RECORD_AUDIO permission and a real microphone, so it isn't exercised
 * here — this catches the manifest/binding class of mistakes that a JVM test structurally cannot.
 */
@RunWith(AndroidJUnit4::class)
class LiveTranscriptionActivitySmokeTest {

    @After
    fun tearDown() = LiveTranscriptionState.reset()

    @Test
    fun launchesIdleWithNothingToCopy() {
        LiveTranscriptionState.reset()

        ActivityScenario.launch(LiveTranscriptionActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                val micButton = activity.findViewById<Button>(R.id.micButton)
                assertEquals(
                    "Mic button should offer to start when nothing is recording",
                    activity.getString(R.string.live_start),
                    micButton.text.toString()
                )

                assertEquals("", activity.findViewById<TextView>(R.id.liveText).text.toString())
                assertFalse(
                    "Copy should stay disabled with an empty transcript",
                    activity.findViewById<Button>(R.id.liveCopyButton).isEnabled
                )
                assertEquals(
                    "Mic pipeline diagnostics should stay hidden before any dictation has run",
                    View.GONE,
                    activity.findViewById<TextView>(R.id.liveDebugText).visibility
                )
            }
        }
    }
}
