package com.whispertranscriber.app

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptsActivitySmokeTest {

    @Test
    fun launchesAndShowsEmptyStateWhenNoTranscriptsExist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transcriptsDir = java.io.File(context.getExternalFilesDir(null), "transcripts")
        transcriptsDir.deleteRecursively()

        ActivityScenario.launch(TranscriptsActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Loading the file list happens asynchronously (Dispatchers.IO); give it a moment to
            // settle before asserting on the result rather than racing it.
            Thread.sleep(500)

            scenario.onActivity { activity ->
                val emptyState = activity.findViewById<View>(R.id.emptyStateText)
                val recyclerView = activity.findViewById<RecyclerView>(R.id.transcriptFilesRecyclerView)
                assertEquals(
                    "Empty state should be visible when no transcripts have been saved yet",
                    View.VISIBLE,
                    emptyState.visibility
                )
                assertEquals(0, recyclerView.adapter?.itemCount ?: 0)
            }
        }
    }
}
