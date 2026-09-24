package dev.hamstercage.capture

import kotlinx.coroutines.sync.Mutex

/** One process owns capture, registration and privacy deletion. No callback can cross a reset journal boundary. */
internal object CaptureWriteGate {
    val mutex = Mutex()
}
