package com.whispertranscriber.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch-only check. Resolving the catalog hits the real network (Hugging Face), whose
 * availability/latency in CI isn't something a test should depend on — this only asserts the
 * screen reaches RESUMED and survives whatever that resolution does (succeeds, partially fails, or
 * fails entirely), not that any particular model actually resolved.
 */
@RunWith(AndroidJUnit4::class)
class LlmModelsActivitySmokeTest {

    @Test
    fun launchesWithoutCrashing() {
        ActivityScenario.launch(LlmModelsActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
}
