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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.Transition
import java.time.Instant
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
        val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
        fun openStore(now: Instant): Triple<HamsterDatabase, CoroutineScope, HamsterRepository> {
            val database = HamsterDatabase.open(context, "$name.db")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
            return Triple(database, scope, HamsterRepository(database, preferences, TimeSource { now }))
        }
        fun show(repository: HamsterRepository, now: Instant) {
            val snapshot = runBlocking { (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot }
            assertEquals(1, snapshot.events.size)
            val input = snapshot.input(now) // No coverage start date: a fresh-install daily projection.
            compose.activity.runOnUiThread { compose.activity.setContent {
                HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    DashboardScreen(input, AttendanceEngine.derive(input), trackingReady = true)
                } }
            } }
            compose.onNodeWithTag("today_leave").assertTextEquals("You can leave at 3:00 PM")
            compose.onNodeWithTag("ROLLING_30_balance").assertTextContains("Unknown")
        }
        var (database, scope, repository) = openStore(firstNow)
        try {
            runBlocking {
                repository.saveOffice(office)
                repository.appendRawEvents(listOf(RecordedEvent(
                    RawEvent("enter", office.id, Transition.ENTER, firstNow.minusSeconds(3 * 3600)), firstNow)))
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
        }
    }
}
