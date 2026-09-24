package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.capture.CoverageStore
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.Correction
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DashboardRestartTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()

    @Test fun activityRestartReopensFactsAndRecomputesFreshInstallLeaveTime() {
        val context = compose.activity.applicationContext
        val name = "daily-leave-${UUID.randomUUID()}"
        val firstNow = Instant.parse("2026-09-23T16:00:00Z")
        val today = LocalDate.parse("2026-09-23")
        val old = Instant.parse("2026-09-18T19:00:00Z")
        val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
        val previousCoverage = runBlocking { CoverageStore.state(context).first() }
        fun openStore(now: Instant): Triple<HamsterDatabase, CoroutineScope, HamsterRepository> {
            val database = HamsterDatabase.open(context, "$name.db")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
            return Triple(database, scope, HamsterRepository(database, preferences, TimeSource { now }))
        }
        fun show(repository: HamsterRepository, now: Instant) {
            val snapshot = runBlocking { (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot }
            assertEquals(2, snapshot.events.size)
            assertEquals(1, snapshot.corrections.size)
            val coverage = runBlocking { CoverageStore.state(context).first() }
            val input = snapshot.input(now).copy(historyStartDate = coverage.historyStartDate,
                unknownDates = coverage.unreviewedUnknownDates)
            compose.activity.runOnUiThread { compose.activity.setContent {
                HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    DashboardScreen(input, AttendanceEngine.derive(input), trackingReady = true)
                } }
            } }
            compose.onNodeWithTag("today_leave").assertTextEquals("You can leave at 3:00 PM")
            compose.onNodeWithTag("ROLLING_30_balance").assertTextContains("Unknown")
            compose.onNodeWithTag("ROLLING_30_days").performScrollTo().assertTextContains("3")
            compose.onNodeWithTag("ROLLING_30_required").performScrollTo().assertTextContains("18h 0m")
            compose.onNodeWithTag("ROLLING_30_pretracking").performScrollTo()
                .assertTextContains("earlier dates outside reliable tracking", substring = true)
            compose.onNodeWithTag("ROLLING_30_unknown").performScrollTo()
                .assertTextContains("1 later calendar day", substring = true)
        }
        var (database, scope, repository) = openStore(firstNow)
        try {
            runBlocking {
                repository.saveOffice(office)
                repository.appendRawEvents(listOf(RecordedEvent(
                    RawEvent("enter", office.id, Transition.ENTER, firstNow.minusSeconds(3 * 3600)), firstNow),
                    RecordedEvent(RawEvent("old-exit", office.id, Transition.EXIT, old), firstNow)))
                repository.appendCorrection(Correction("backfill", "session:old-exit",
                    Instant.parse("2026-09-18T13:00:00Z"), old, firstNow))
                CoverageStore.change(context) { CoverageLedger(historyStartDate = today.minusDays(2),
                    unknownDates = setOf(today.minusDays(1))) }
            }
            show(repository, firstNow)
            compose.onNodeWithTag("today_credit").assertTextEquals("2h 55m")
            compose.activityRule.scenario.recreate()
            database.close()
            scope.cancel()
            runBlocking { scope.coroutineContext[Job]?.join() }
            val reopened = openStore(firstNow.plusSeconds(60))
            database = reopened.first
            scope = reopened.second
            repository = reopened.third
            show(repository, firstNow.plusSeconds(60))
            compose.onNodeWithTag("today_credit").assertTextEquals("2h 56m")
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            database.close()
            scope.cancel()
            runBlocking { scope.coroutineContext[Job]?.join() }
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
            runBlocking { CoverageStore.change(context) { previousCoverage } }
        }
    }
}
