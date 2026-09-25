package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.hamstercage.data.*
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val today = LocalDate.of(2026, 9, 23)
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
    private fun source() = AttendanceInput(listOf(office), emptyList(), now = now)
    private fun show(input: AttendanceInput, scale: Float = 1f) {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { HistoryScreen(input, AttendanceEngine.derive(input)) } }
            }
        }
    }

    @Test fun emptyHistoryOffersOptionalBrowseWithoutAReviewListAtLargeText() {
        show(source(), 2f)
        compose.onNodeWithText("No attendance recorded yet").assertIsDisplayed()
        compose.onNodeWithTag("history_review_alert").assertDoesNotExist()
        compose.onNodeWithTag("history_date_$today").assertDoesNotExist()
        compose.onNodeWithTag("history_browse_before").performScrollTo().performClick()
        compose.onNodeWithTag("history_before_explanation").performScrollTo().assertTextEquals(
            "Before tracking · ordinary dates were not captured and need no review. Saved attendance records that need review stay visible. Add attendance only if you choose.")
        compose.onNodeWithTag("history_date_$today").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_required_$today").assertDoesNotExist()
        compose.onNodeWithText("Explain $today").performScrollTo().performClick()
        compose.onNodeWithTag("detail_before_tracking").performScrollTo().assertTextEquals(
            "Before tracking began. No attendance requirement or balance applies to this date.")
        compose.onNodeWithTag("detail_required").assertDoesNotExist()
        compose.onNodeWithText("Expected weekday", substring = true).assertDoesNotExist()
    }

    @Test fun capturedDayIsPrimaryAndLaterGapPromptsReview() {
        val entered = source().copy(events = listOf(RawEvent("enter", office.id, Transition.ENTER, now.minusSeconds(2 * 86400L + 3600)),
            RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(2 * 86400L))),
            unknownDates = setOf(today.minusDays(1)))
        show(entered)
        compose.onNodeWithTag("history_review_alert").performScrollTo().assertTextEquals("2 days have missing attendance coverage.")
        compose.onNodeWithTag("history_review_action").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_date_${today.minusDays(3)}").assertDoesNotExist()
        compose.onNodeWithTag("history_date_${today.minusDays(1)}").performScrollTo().assertIsDisplayed()
    }

    @Test fun rejectedRawFactsBeforeTrackingRemainInDefaultReviewFlow() {
        show(source().copy(events = listOf(
            RawEvent("duplicate", office.id, Transition.ENTER, now.minusSeconds(3600)),
            RawEvent("duplicate", office.id, Transition.ENTER, now.minusSeconds(86400 + 3600)))))
        compose.onNodeWithTag("history_review_alert").performScrollTo().assertTextEquals("2 days have saved attendance records to review.")
        compose.onNodeWithTag("history_date_$today").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_date_${today.minusDays(1)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_before_message_$today").performScrollTo()
            .assertTextEquals("Tracking had not begun for this date. A saved attendance record needs review.")
        compose.onNodeWithTag("history_required_$today").assertDoesNotExist()
        compose.onNodeWithTag("history_review_action").performScrollTo().performClick()
        compose.onNodeWithTag("detail_before_tracking").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail_required").assertDoesNotExist()
        compose.onNodeWithText("Review explanations", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun largeHistoryRendersOnlyFourteenDaysAndPagesToOlderSource() {
        show(source().copy(historyStartDate = today.minusYears(10)))
        val dates = hasTestTag("history_date_${today.minusDays(14)}")
        compose.onNode(dates).assertDoesNotExist()
        compose.onNodeWithText("Earlier 14 days").performScrollTo().performClick()
        compose.onNodeWithTag("history_date_${today.minusDays(14)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_date_$today").assertDoesNotExist()
        compose.onNodeWithText("Newer 14 days").performScrollTo().performClick()
        compose.onNodeWithTag("history_date_$today").performScrollTo().assertIsDisplayed()
    }

    @Test fun policyTimezoneAndTextBadgesRemainExplicit() {
        val date = LocalDate.of(2026, 9, 23)
        show(source().copy(now = Instant.parse("2026-09-24T02:00:00Z"), policy = Policy(
            zoneId = ZoneId.of("America/New_York"), excludedDates = listOf(ExcludedDate(date, ExclusionReason.BANK_HOLIDAY)), wfhDates = setOf(date)),
            manualSessions = listOf(ManualSession("manual", office.id, now.minusSeconds(3600), now, now))))
        compose.onNodeWithTag("history_date_$date").assertIsDisplayed()
        compose.onNodeWithText("HOLIDAY").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("WFH").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MANUAL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_required_$date").performScrollTo().assertTextContains("0m")
    }

    @Test fun savedCorrectionAndPolicyFlowsRefreshVisibleHistoryWithoutRestart() {
        val context = compose.activity.applicationContext
        val name = "history-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = HamsterDatabase.open(context, "$name.db")
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val repository = HamsterRepository(database, preferences, TimeSource { now })
        val seen = AtomicReference<StorageState>(StorageState.Loading)
        try {
            val manual = ManualSession("manual", office.id, now.minusSeconds(7200), now, now)
            val initial = runBlocking {
                repository.saveOffice(office)
                repository.appendManualSession(manual)
                withTimeout(10_000) { repository.state.first() }
            }
            assertTrue("Initial isolated repository state: ${initial::class.simpleName}", initial is StorageState.Ready)
            assertEquals(1, (initial as StorageState.Ready).snapshot.manualSessions.size)
            compose.setContent {
                // Read in this composition scope so its SideEffect tracks each emission,
                // rather than only the independently recomposing Column content below.
                val state = repository.state.collectAsState(StorageState.Loading).value
                SideEffect { seen.set(state) }
                HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    (state as? StorageState.Ready)?.snapshot?.input(now)?.let { input -> HistoryScreen(input, AttendanceEngine.derive(input)) }
                } }
            }
            compose.waitForIdle() // Commit the initial composition before observing its SideEffect.
            try { compose.waitUntil(10_000) { (seen.get() as? StorageState.Ready)?.snapshot?.manualSessions?.size == 1 } }
            catch (failure: Throwable) { throw AssertionError("First rendered storage state: ${seen.get()::class.simpleName}", failure) }
            compose.onNodeWithTag("history_credit_$today").assertTextContains("2h 0m")
            val snapshot = (seen.get() as StorageState.Ready).snapshot
            val session = snapshot.derive(now).sessions.single()
            runBlocking {
                repository.appendCorrection(Correction("fix", session.id, now.minusSeconds(3600), now, now))
                repository.savePolicy(PolicySettings(targetMinutesPerDay = 480))
            }
            compose.waitUntil(10_000) { (seen.get() as? StorageState.Ready)?.snapshot?.let { it.corrections.size == 1 && it.policy.targetMinutesPerDay == 480 } == true }
            compose.onNodeWithTag("history_credit_$today").assertTextContains("1h 0m")
            compose.onNodeWithTag("history_required_$today").assertTextContains("8h 0m")
            assertEquals(listOf(manual), (seen.get() as StorageState.Ready).snapshot.manualSessions)
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            database.close()
            scope.cancel()
            runBlocking { scope.coroutineContext[Job]?.join() }
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }
}
