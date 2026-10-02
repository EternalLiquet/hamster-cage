package dev.hamstercage.capture

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WeekdaySchedulingFailureTest {
    @Test fun enqueueTimeoutIsReportedWithoutFailingRegistration() = runBlocking {
        var reports = 0
        scheduleWeekdayChecksBestEffort(schedule = { throw TimeoutException() }, reportFailure = { reports++ })
        assertEquals(1, reports)
    }

    @Test fun diagnosticFailureCannotFailRegistration() = runBlocking {
        scheduleWeekdayChecksBestEffort(schedule = { throw TimeoutException() },
            reportFailure = { throw IllegalStateException() })
    }

    @Test fun cancellationStillStopsScheduling() = runBlocking {
        val cancelled = CancellationException("Synthetic cancellation")
        var reports = 0
        try {
            scheduleWeekdayChecksBestEffort(schedule = { throw cancelled }, reportFailure = { reports++ })
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertEquals(0, reports)
    }
}
