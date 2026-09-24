package dev.hamstercage.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.MainActivity
import dev.hamstercage.capture.GeofenceObservation
import dev.hamstercage.data.*
import dev.hamstercage.domain.*
import java.io.File
import java.time.Instant
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
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
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
        assumeTrue(action in setOf("exercise", "restart", "assert-synthetic"))
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
            assertTrue("Use a fresh synthetic installation", initial.offices.isEmpty() && initial.events.isEmpty() && initial.corrections.isEmpty() && initial.manualSessions.isEmpty())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Office state unknown")
                nav("Offices")
                for ((name, longitude) in listOf("Synthetic Journey A" to "0.0", "Synthetic Journey B" to "0.01")) {
                    click("Add office")
                    field("Office name", name); field("Latitude (degrees)", "0.0"); field("Longitude (degrees)", longitude)
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
                compose.onNodeWithTag("correction_start").performScrollTo().performTextReplacement(now.minusSeconds(7500).toString())
                click("Preview attendance change")
                assertTrue(snapshot().corrections.isEmpty())
                click("Confirm attendance change"); awaitText("Attendance change saved")
                assertEquals(observations.map { it.event }, snapshot().events)
                assertEquals(1, snapshot().corrections.size)
                click("Back to history")
                nav("Settings"); click("Add excluded date")
                val today = now.atZone(before.policy.zoneId).toLocalDate()
                compose.onNodeWithTag("calendar_date").performScrollTo().performTextReplacement(today.toString())
                click("Save exclusion"); awaitText("Calendar saved. Attendance has been recalculated.")
                assertEquals(today, snapshot().policy.excludedDates.single().date)
                nav("Dashboard"); assertTotals(Instant.now())
                assertEquals(0, AttendanceEngine.daily(snapshot().input(now), snapshot().derive(now), today).requiredMinutes)
                scenario.recreate(); assertTotals(Instant.now())
                receipt.writeText(JSONObject().put("pid", Process.myPid()).put("evaluation", Instant.now().toString())
                    .put("events", 4).put("offices", 2).put("corrections", 1).put("excludedDate", today.toString()).toString())
            }
        } else {
            assertTrue("Run the synthetic exercise first", receipt.isFile)
            val expected = JSONObject(receipt.readText())
            assertNotEquals(expected.getInt("pid"), Process.myPid())
            val reopened = snapshot()
            assertEquals(2, reopened.offices.size); assertEquals(4, reopened.events.size); assertEquals(1, reopened.corrections.size)
            assertTrue(reopened.offices.all { it.name in setOf("Synthetic Journey A", "Synthetic Journey B") && it.latitude == 0.0 })
            assertEquals(expected.getString("excludedDate"), reopened.policy.excludedDates.single().date.toString())
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTotals(Instant.now())
                nav("History"); awaitText("Every total comes from your local record.")
                nav("Settings"); awaitText("Attendance policy")
            }
            receipt.writeText(expected.put("reopenedPid", Process.myPid()).put("freshProcessVerified", true).toString())
        }
    }
}
