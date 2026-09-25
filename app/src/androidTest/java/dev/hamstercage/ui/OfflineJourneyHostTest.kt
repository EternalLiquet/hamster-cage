package dev.hamstercage.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Process
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.MainActivity
import dev.hamstercage.capture.GeofenceObservation
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.capture.CoverageStore
import dev.hamstercage.data.*
import dev.hamstercage.domain.*
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in host journey. Mutates only an explicitly fresh synthetic preview installation.
 * Synthetic observations exercise the parser/repository boundary, not OS geofence delivery. */
class OfflineJourneyHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = HamsterRepository.get(context)
    private fun snapshot() = runBlocking { (repository.state.first() as StorageState.Ready).snapshot }
    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
    private fun nav(text: String) = compose.onNode(hasText(text) and hasClickAction()).performClick()
    private fun field(label: String, value: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    private fun pickStart(value: LocalDateTime) {
        compose.onNodeWithTag("correction_start_date").performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "Choose synthetic correction date"
            override fun perform(uiController: UiController, view: View) {
                (view as DatePicker).updateDate(value.year, value.monthValue - 1, value.dayOfMonth)
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
        compose.onNodeWithTag("correction_start_time").performScrollTo().performClick()
        onView(isAssignableFrom(TimePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "Choose synthetic correction time"
            override fun perform(uiController: UiController, view: View) {
                (view as TimePicker).hour = value.hour
                view.minute = value.minute
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun requireFreshRecord(facts: AppSnapshot, reset: PrivacyResetState, coverage: CoverageLedger) {
        check(facts.offices.isEmpty() && facts.events.isEmpty() && facts.corrections.isEmpty() && facts.manualSessions.isEmpty() &&
            facts.policy == Policy() && reset == PrivacyResetState.Idle(0) && coverage == CoverageLedger()) {
            "Use a fresh synthetic installation; existing private state was preserved"
        }
    }
    @Test fun existingPolicyResetOrCoverageCannotAuthorizeSyntheticMutation() {
        val fresh = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        requireFreshRecord(fresh, PrivacyResetState.Idle(0), CoverageLedger())
        assertTrue(runCatching { requireFreshRecord(fresh.copy(policy = Policy(targetMinutesPerDay = 420)),
            PrivacyResetState.Idle(0), CoverageLedger()) }.isFailure)
        assertTrue(runCatching { requireFreshRecord(fresh, PrivacyResetState.Idle(1), CoverageLedger()) }.isFailure)
        assertTrue(runCatching { requireFreshRecord(fresh, PrivacyResetState.Pending(1), CoverageLedger()) }.isFailure)
        assertTrue(runCatching { requireFreshRecord(fresh, PrivacyResetState.Idle(0),
            CoverageLedger(lastObservationAt = Instant.EPOCH)) }.isFailure)
    }
    private fun assertTotals(now: Instant) {
        val input = snapshot().input(now)
        val expected = AttendanceEngine.daily(input, AttendanceEngine.derive(input), now.atZone(input.policy.zoneId).toLocalDate())
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("today_credit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today_credit").assertTextEquals(minutesText(expected.creditedMinutes))
        compose.onNodeWithTag("office_state").assertTextEquals("Office state unknown")
        compose.onNodeWithTag("today_balance").assertTextContains("Unknown")
    }

    @Test fun completeOfflineJourneyAndFreshProcessReopen() {
        val action = InstrumentationRegistry.getArguments().getString("offlineJourneyAction")
        assumeTrue(action in setOf("exercise", "restart", "delete", "deleted-restart", "assert-synthetic"))
        assertEquals("dev.hamstercage.preview", context.packageName)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        if (action == "assert-synthetic") {
            val facts = snapshot()
            assertEquals(setOf("Synthetic Journey A", "Synthetic Journey B"), facts.offices.map { it.name }.toSet())
            assertTrue(facts.offices.all { it.latitude == 0.0 && it.longitude in setOf(0.0, 0.01) })
            assertTrue(facts.events.size <= 4 && facts.events.all { event -> facts.offices.any { it.id == event.officeId } })
            assertTrue(facts.corrections.size <= 1 && facts.corrections.all { it.note.isEmpty() })
            assertTrue(facts.manualSessions.isEmpty() && facts.policy.excludedDates.all { it.note.isEmpty() })
            return
        }
        val receipt = File(context.filesDir, "synthetic-offline-journey.json")
        if (action == "exercise") {
            val initial = snapshot()
            requireFreshRecord(initial, runBlocking { PrivacyResetStore.read(context) },
                runBlocking { CoverageStore.state(context).first() })
            assertFalse("Existing synthetic receipt was preserved", receipt.exists())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Office state unknown")
                nav("Offices")
                for ((name, longitude) in listOf("Synthetic Journey A" to "0.0", "Synthetic Journey B" to "0.01")) {
                    click("Add office")
                    field("Office name", name)
                    click("Advanced: coordinates and walking grace")
                    field("Latitude (degrees)", "0.0"); field("Longitude (degrees)", longitude)
                    click("Review manual coordinates")
                    click("Confirm pin and radius")
                    click("Save office"); awaitText(name)
                }
                val now = Instant.ofEpochMilli(System.currentTimeMillis())
                val offices = snapshot().offices.sortedBy { it.name }
                assertEquals(2, offices.size)
                val observations = listOf(
                    Triple(offices[0].id, Transition.ENTER, now.minusSeconds(7200)),
                    Triple(offices[0].id, Transition.EXIT, now.minusSeconds(3600)),
                    Triple(offices[1].id, Transition.ENTER, now.minusSeconds(3300)),
                    Triple(offices[1].id, Transition.EXIT, now.minusSeconds(1800)),
                ).flatMap { (office, transition, at) ->
                    GeofenceObservation.records(listOf(office), transition, Location("synthetic-test-only").apply { time = at.toEpochMilli() }, now)
                }
                runBlocking { repository.appendRawEvents(observations) }
                val before = snapshot().input(now)
                val session = AttendanceEngine.derive(before).sessions.first { it.officeId == offices[0].id }
                nav("Dashboard"); assertTotals(Instant.now())
                nav("History")
                val day = session.start!!.atZone(before.policy.zoneId).toLocalDate()
                click("Explain $day"); click("Correct ${session.id}")
                val selectedInstant = session.start!!.minusSeconds(300)
                val selectedLocal = selectedInstant.atZone(before.policy.zoneId).toLocalDateTime()
                    .truncatedTo(ChronoUnit.MINUTES)
                pickStart(selectedLocal)
                overlapChoiceFor(selectedInstant, before.policy.zoneId)?.let { choice ->
                    compose.onNodeWithTag("correction_start_overlap_$choice").performScrollTo().performClick()
                }
                val expectedStart = resolveLocalBoundary(LocalBoundary(selectedLocal,
                    overlapChoiceFor(selectedInstant, before.policy.zoneId)), before.policy.zoneId)
                click("Preview attendance change")
                assertTrue(snapshot().corrections.isEmpty())
                click("Confirm attendance change"); awaitText("Attendance change saved")
                assertEquals(observations.map { it.event }, snapshot().events)
                assertEquals(1, snapshot().corrections.size)
                assertEquals(expectedStart, snapshot().corrections.single().start)
                click("Back to history")
                nav("Settings"); click("Edit policy")
                compose.onNodeWithTag("policy_target").performScrollTo().performTextReplacement("420")
                compose.onNodeWithTag("policy_zone").performScrollTo().performTextReplacement("Etc/UTC")
                click("Save and recalculate"); awaitText("Policy saved. Attendance has been recalculated.")
                assertEquals(420, snapshot().policy.targetMinutesPerDay)
                assertEquals("Etc/UTC", snapshot().policy.zoneId.id)
                val today = now.atZone(snapshot().policy.zoneId).toLocalDate()
                val requirementBeforeWfh = AttendanceEngine.daily(snapshot().input(now), snapshot().derive(now), today).requiredMinutes
                compose.onNodeWithTag("wfh_date").performScrollTo().performTextReplacement(today.toString())
                click("Label WFH date"); awaitText("Calendar saved. Attendance has been recalculated.")
                assertTrue(today in snapshot().policy.wfhDates)
                assertEquals(requirementBeforeWfh, AttendanceEngine.daily(snapshot().input(now), snapshot().derive(now), today).requiredMinutes)
                click("Add excluded date")
                compose.onNodeWithTag("calendar_date").performScrollTo().performTextReplacement(today.toString())
                click("Save exclusion"); awaitText("Calendar saved. Attendance has been recalculated.")
                assertEquals(today, snapshot().policy.excludedDates.single().date)
                nav("Dashboard"); assertTotals(Instant.now())
                assertEquals(0, AttendanceEngine.daily(snapshot().input(now), snapshot().derive(now), today).requiredMinutes)
                scenario.recreate(); assertTotals(Instant.now())
                receipt.writeText(JSONObject().put("pid", Process.myPid()).put("evaluation", Instant.now().toString())
                    .put("events", 4).put("offices", 2).put("corrections", 1).put("excludedDate", today.toString()).toString())
            }
        } else if (action == "restart") {
            assertTrue("Run the synthetic exercise first", receipt.isFile)
            val expected = JSONObject(receipt.readText())
            assertNotEquals(expected.getInt("pid"), Process.myPid())
            val reopened = snapshot()
            assertEquals(2, reopened.offices.size); assertEquals(4, reopened.events.size); assertEquals(1, reopened.corrections.size)
            assertEquals(420, reopened.policy.targetMinutesPerDay); assertEquals("Etc/UTC", reopened.policy.zoneId.id)
            assertEquals(1, reopened.policy.wfhDates.size)
            assertTrue(reopened.offices.all { it.name in setOf("Synthetic Journey A", "Synthetic Journey B") && it.latitude == 0.0 })
            assertEquals(expected.getString("excludedDate"), reopened.policy.excludedDates.single().date.toString())
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTotals(Instant.now())
                nav("History"); awaitText("Every total comes from your local record.")
                nav("Settings"); awaitText("Attendance policy")
            }
            receipt.writeText(expected.put("reopenedPid", Process.myPid()).put("freshProcessVerified", true).toString())
        } else if (action == "delete") {
            val expected = JSONObject(receipt.readText())
            assertTrue(expected.getBoolean("freshProcessVerified"))
            val before = snapshot()
            assertEquals(4, before.events.size)
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText("Office state unknown"); nav("Settings")
                compose.onNodeWithTag("privacy_delete_history").performScrollTo().performClick()
                compose.onNodeWithTag("privacy_cancel").performScrollTo().performClick()
                assertEquals(before, snapshot())
                compose.onNodeWithTag("privacy_delete_history").performScrollTo().performClick()
                compose.onNodeWithTag("privacy_confirm").performScrollTo().performClick()
                awaitText("Attendance and calendar history deleted.")
                val after = snapshot()
                assertTrue(after.events.isEmpty() && after.corrections.isEmpty() && after.manualSessions.isEmpty())
                assertTrue(after.policy.excludedDates.isEmpty() && after.policy.wfhDates.isEmpty())
                assertEquals(before.offices, after.offices)
                assertEquals(before.policy.copy(excludedDates = emptyList(), wfhDates = emptySet()), after.policy)
                nav("Dashboard"); assertTotals(Instant.now())
            }
            receipt.writeText(expected.put("deletedPid", Process.myPid()).put("confirmedDeletionVerified", true).toString())
        } else {
            val expected = JSONObject(receipt.readText())
            assertTrue(expected.getBoolean("confirmedDeletionVerified"))
            assertNotEquals(expected.getInt("deletedPid"), Process.myPid())
            val after = snapshot()
            assertTrue(after.events.isEmpty() && after.corrections.isEmpty() && after.manualSessions.isEmpty())
            assertTrue(after.policy.excludedDates.isEmpty() && after.policy.wfhDates.isEmpty())
            assertEquals(2, after.offices.size)
            assertEquals(420, after.policy.targetMinutesPerDay); assertEquals("Etc/UTC", after.policy.zoneId.id)
            ActivityScenario.launch(MainActivity::class.java).use { assertTotals(Instant.now()) }
            receipt.writeText(expected.put("afterDeleteReopenedPid", Process.myPid()).put("deletedRestartVerified", true).toString())
        }
    }
}
