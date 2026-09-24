package dev.hamstercage.data

import dev.hamstercage.domain.TimeSource
import java.time.Clock
import java.time.Instant

/** Platform adapter; persistence and capture adapters are delivered by their own issues. */
class SystemTimeSource(private val clock: Clock = Clock.systemUTC()) : TimeSource {
    override fun now(): Instant = clock.instant()
}
