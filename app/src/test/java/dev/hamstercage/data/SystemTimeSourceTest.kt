package dev.hamstercage.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemTimeSourceTest {
    @Test fun adapterUsesInjectedClockInsteadOfWallTime() {
        val instant = Instant.parse("2026-09-23T12:34:00Z")
        assertEquals(instant, SystemTimeSource(Clock.fixed(instant, ZoneOffset.UTC)).now())
    }
}
