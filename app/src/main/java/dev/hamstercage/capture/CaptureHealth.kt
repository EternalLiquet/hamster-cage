package dev.hamstercage.capture

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RegistrationStatus { UNKNOWN, REGISTERING, NEEDS_SETUP, NO_OFFICES, ACTIVE, FAILED, DISABLED }

/** Sanitized process-local health, refreshed by registration on each app launch. */
data class CaptureStatus(
    val registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
    val registeredCount: Int = 0,
    val deliveryFailure: Boolean = false,
    val monitoringEnabled: Boolean = true,
    val lastVerifiedAt: Instant? = null,
    val lastCheckResult: String? = null,
)

object CaptureHealth {
    private val mutable = MutableStateFlow(CaptureStatus())
    val state: StateFlow<CaptureStatus> = mutable

    fun registration(status: RegistrationStatus, count: Int = 0) {
        mutable.update { it.copy(registration = status, registeredCount = count) }
    }

    fun deliveryFailed() { mutable.update { it.copy(deliveryFailure = true) } }
    fun deliverySucceeded() { mutable.update { it.copy(deliveryFailure = false) } }
    fun monitoring(value: MonitoringState) { mutable.update { it.copy(monitoringEnabled = value.enabled,
        lastVerifiedAt = value.lastVerifiedAt, lastCheckResult = value.lastResult) } }
}
