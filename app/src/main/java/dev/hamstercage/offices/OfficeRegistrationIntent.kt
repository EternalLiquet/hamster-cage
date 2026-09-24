package dev.hamstercage.offices

import dev.hamstercage.data.AppSnapshot

/** Desired boundaries only. Platform registration and its health belong to capture integration. */
data class OfficeRegistrationIntent(
    val officeId: String, val latitude: Double, val longitude: Double, val radiusMeters: Float,
)

fun AppSnapshot.registrationIntents(): List<OfficeRegistrationIntent> = offices.asSequence()
    .filter { it.enabled }
    .map { OfficeRegistrationIntent(it.id, it.latitude, it.longitude, it.radiusMeters) }
    .sortedBy { it.officeId }
    .toList()
