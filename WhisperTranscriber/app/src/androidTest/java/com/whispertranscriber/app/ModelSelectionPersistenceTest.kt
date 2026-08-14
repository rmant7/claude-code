package com.whispertranscriber.app

import android.content.Context
import android.widget.Spinner
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Re-picking the same model on every launch (often the largest, slowest-to-download one) was
 * needless friction, so MainActivity persists the last selection via Activity#getPreferences()
 * (backed by a SharedPreferences file named after the Activity, "MainActivity") and restores it
 * on the next launch.
 */
@RunWith(AndroidJUnit4::class)
class ModelSelectionPersistenceTest {

    @Test
    fun restoresTheLastSelectedModelOnLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val expectedModel = WhisperModel.ALL.last()

        prefs.edit().putString("last_model_id", expectedModel.id).apply()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.RESUMED)

                scenario.onActivity { activity ->
                    val spinner = activity.findViewById<Spinner>(R.id.modelSpinner)
                    assertEquals(
                        "Spinner should restore the previously selected model on launch",
                        WhisperModel.ALL.indexOf(expectedModel),
                        spinner.selectedItemPosition
                    )
                }
            }
        } finally {
            prefs.edit().remove("last_model_id").apply()
        }
    }
}
