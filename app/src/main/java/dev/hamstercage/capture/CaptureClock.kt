package dev.hamstercage.capture

import java.time.Instant

/**
 * Wall-clock time for facts and ledger writes. Stored times are whole milliseconds, and newer
 * Android releases give `Instant.now()` microsecond precision, which the repository rejects.
 */
internal fun captureNow(millis: () -> Long = System::currentTimeMillis): Instant = Instant.ofEpochMilli(millis())
