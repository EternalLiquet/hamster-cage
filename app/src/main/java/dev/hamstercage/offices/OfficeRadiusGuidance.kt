package dev.hamstercage.offices

import dev.hamstercage.domain.Office
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Warnings only: existing and user-selected radii are never rewritten. */
object OfficeRadiusGuidance {
    fun isUnusuallySmall(radiusMeters: Float) = radiusMeters < 150f

    fun touching(office: Office, offices: List<Office>): List<Office> =
        if (!office.enabled || !office.countsTowardAttendance) emptyList() else offices.filter { other ->
            other.id != office.id && other.enabled && other.countsTowardAttendance &&
                metersBetween(office.latitude, office.longitude, other.latitude, other.longitude) <=
                office.radiusMeters + other.radiusMeters
        }

    private fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val arc = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) *
            cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 12_742_000.0 * asin(sqrt(min(1.0, arc)))
    }
}
