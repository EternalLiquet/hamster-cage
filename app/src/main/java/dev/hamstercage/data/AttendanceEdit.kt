package dev.hamstercage.data

import dev.hamstercage.domain.*

sealed interface AttendanceEdit {
    val baseline: AttendanceInput
    fun proposedInput(): AttendanceInput
    data class Correct(override val baseline: AttendanceInput, val value: Correction) : AttendanceEdit {
        override fun proposedInput() = baseline.copy(corrections = baseline.corrections + value)
    }
    data class AddManual(override val baseline: AttendanceInput, val value: ManualSession) : AttendanceEdit {
        override fun proposedInput() = baseline.copy(manualSessions = baseline.manualSessions + value)
    }
}
