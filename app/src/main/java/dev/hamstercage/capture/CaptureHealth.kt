package dev.hamstercage.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RegistrationStatus { UNKNOWN, REGISTERING, NEEDS_SETUP, NO_OFFICES, ACTIVE, FAILED }

/** Sanitized process-local health, refreshed by registration on each app launch. */
data class CaptureStatus(
    val registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
    val registeredCount: Int = 0,
    val deliveryFailure: Boolean = false,
)

object CaptureHealth {
    private val mutable = MutableStateFlow(CaptureStatus())
    val state: StateFlow<CaptureStatus> = mutable

    fun registration(status: RegistrationStatus, count: Int = 0) {
        mutable.update { it.copy(registration = status, registeredCount = count) }
    }

    fun deliveryFailed() { mutable.update { it.copy(deliveryFailure = true) } }
    fun deliverySucceeded() { mutable.update { it.copy(deliveryFailure = false) } }
}
