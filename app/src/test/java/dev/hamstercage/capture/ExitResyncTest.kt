package dev.hamstercage.capture

import dev.hamstercage.data.EXIT_VERIFY_OUTSIDE
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which EXITs re-arm the platform's inside state so a later real departure is still reported. */
class ExitResyncTest {
    private val at = Instant.parse("2026-03-04T15:00:00Z")
    private val office = Office("a", "A", 0.0, 0.0, radiusMeters = 150f)
    private fun event(id: String, type: Transition, second: Long = 0, accuracy: Float? = null,
        officeId: String = "a", source: String = "PLAY_SERVICES_GEOFENCE") = RecordedEvent(
            RawEvent(id, officeId, type, at.plusSeconds(second)), at.plusSeconds(second),
            at.plusSeconds(second), source, accuracy)

    @Test fun onlyNewlyStoredExitsWiderThanTheOfficeReArm() {
        val coarse = event("coarse", Transition.EXIT, accuracy = 400f)
        assertEquals(listOf("a"), implausibleExitOfficeIds(listOf(coarse), setOf("coarse"), listOf(office)))
        // A precise departure, an unknown accuracy, a replay or an arrival changes nothing.
        assertEquals(emptyList<String>(), implausibleExitOfficeIds(listOf(event("fine", Transition.EXIT, accuracy = 100f)),
            setOf("fine"), listOf(office)))
        assertEquals(emptyList<String>(), implausibleExitOfficeIds(listOf(event("edge", Transition.EXIT, accuracy = 150f)),
            setOf("edge"), listOf(office)))
        assertEquals(emptyList<String>(), implausibleExitOfficeIds(listOf(event("unknown", Transition.EXIT)),
            setOf("unknown"), listOf(office)))
        assertEquals(emptyList<String>(), implausibleExitOfficeIds(listOf(coarse), emptySet(), listOf(office)))
        assertEquals(emptyList<String>(), implausibleExitOfficeIds(listOf(event("in", Transition.ENTER, accuracy = 400f)),
            setOf("in"), listOf(office)))
        assertEquals(emptyList<String>(), implausibleExitOfficeIds(listOf(event("nan", Transition.EXIT, accuracy = Float.NaN)),
            setOf("nan"), listOf(office)))
    }

    @Test fun ineligibleOfficesAreNotReArmedAndOneDeliveryCanCoverSeveral() {
        val wide = Office("b", "B", 1.0, 1.0, radiusMeters = 500f)
        val disabled = office.copy(id = "c", enabled = false)
        val noCredit = office.copy(id = "d", countsTowardAttendance = false)
        val delivery = listOf("a", "b", "c", "d", "missing").map {
            event("exit-$it", Transition.EXIT, accuracy = 400f, officeId = it)
        }
        assertEquals(listOf("a"), implausibleExitOfficeIds(delivery, delivery.map { it.event.id }.toSet(),
            listOf(office, wide, disabled, noCredit)))
        assertEquals(listOf("a", "b"), implausibleExitOfficeIds(delivery, delivery.map { it.event.id }.toSet(),
            listOf(office, wide.copy(radiusMeters = 150f), disabled, noCredit)))
    }

    @Test fun pendingOrExpiredUndecidedExitReArmsButRejectedOrConfirmedDoesNot() {
        // Verification follows an EXIT only from a known inside state.
        val arrived = event("arrived", Transition.ENTER, -3600, accuracy = 10f)
        val exit = event("exit", Transition.EXIT, accuracy = 400f)
        // Still awaiting checks, and expired without a decision.
        assertEquals(listOf("a"), unresolvedExitOfficeIds(listOf(arrived, exit), listOf(exit), at.plusSeconds(30)))
        assertEquals(listOf("a"), unresolvedExitOfficeIds(listOf(arrived, exit), listOf(exit), at.plusSeconds(400)))
        // A platform ENTER seconds later rejects the phantom; the platform is inside again.
        val back = event("back", Transition.ENTER, 10, accuracy = 20f)
        assertEquals(emptyList<String>(), unresolvedExitOfficeIds(listOf(arrived, exit, back), listOf(exit), at.plusSeconds(400)))
        // Two outside checks at least 45 s apart confirm the departure.
        val outside = listOf(event("o1", Transition.ABSENCE, 30, 20f, source = EXIT_VERIFY_OUTSIDE),
            event("o2", Transition.ABSENCE, 90, 20f, source = EXIT_VERIFY_OUTSIDE))
        assertEquals(emptyList<String>(), unresolvedExitOfficeIds(listOf(arrived, exit) + outside, listOf(exit), at.plusSeconds(400)))
        // An arrival batch never re-arms.
        val enter = event("enter", Transition.ENTER)
        assertEquals(emptyList<String>(), unresolvedExitOfficeIds(listOf(enter), listOf(enter), at.plusSeconds(30)))
    }
}
