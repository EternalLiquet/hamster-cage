package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import dev.hamstercage.MainActivity
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VisibleClockTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun stoppedActivityDoesNotTickAndResumeReadsFreshTimeImmediately() {
        val instant = AtomicReference(Instant.parse("2026-09-23T16:00:00Z"))
        val reads = AtomicInteger()
        val source = TimeSource { reads.incrementAndGet(); instant.get() }
        compose.activity.runOnUiThread { compose.activity.setContent { Text(rememberVisibleNow(source, true, 50).toString()) } }
        compose.onNodeWithText("2026-09-23T16:00:00Z").assertTextEquals("2026-09-23T16:00:00Z")
        compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(100)
        val stoppedReads = reads.get()
        instant.set(Instant.parse("2026-09-23T17:00:00Z"))
        Thread.sleep(150)
        assertEquals(stoppedReads, reads.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("2026-09-23T17:00:00Z").assertTextEquals("2026-09-23T17:00:00Z")
    }
}
