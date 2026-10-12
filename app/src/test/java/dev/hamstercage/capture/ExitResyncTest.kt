package dev.hamstercage.capture

import dev.hamstercage.data.EXIT_VERIFY_OUTSIDE
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.exitVerificationState
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which EXITs re-arm the platform's inside state so a later real departure is still reported. */
class ExitResyncTest {
    private val at = Instant.parse("2026-03-04T15:00:00Z")
    private fun event(id: String, type: Transition, second: Long = 0, accuracy: Float? = null,
        officeId: String = "a", source: String = "PLAY_SERVICES_GEOFENCE") = RecordedEvent(
            RawEvent(id, officeId, type, at.plusSeconds(second)), at.plusSeconds(second),
            at.plusSeconds(second), source, accuracy)

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

    @Test fun reArmEnterObservedAfterTheFinalCheckDoesNotRejectTheDeparture() {
        // The final attempt runs about 270 s after the EXIT, beyond the 60 s quick-return window,
        // so an equally coarse platform ENTER observed then cannot erase a real departure: the EXIT stays
        // the observed boundary (undecided) and only the ENTER's own time opens a new visit.
        val arrived = event("arrived", Transition.ENTER, -3600, accuracy = 10f)
        val exit = event("exit", Transition.EXIT, accuracy = 400f)
        val reArmed = event("rearmed", Transition.ENTER, 275, accuracy = 400f)
        val state = exitVerificationState(listOf(arrived, exit, reArmed), at.plusSeconds(400))
        assertFalse("exit" in state.rejectedIds)
        assertTrue("exit" in state.uncertainIds)
        // The re-arm ENTER supersedes the candidate, so no further re-arm follows it.
        assertFalse(confirmableCandidate(listOf(arrived, exit, reArmed), "exit", "a"))
    }
}
