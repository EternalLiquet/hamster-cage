package dev.hamstercage.offices

import dev.hamstercage.domain.Office

/** Text remains local to the form; validation errors never echo personal coordinates. */
data class OfficeDraft(
    val name: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: String = "150",
    val enabled: Boolean = true,
    val countsTowardAttendance: Boolean = true,
    val entryGraceMinutes: String = "5",
    val exitGraceMinutes: String = "5",
) {
    fun toOffice(id: String): Office {
        val nameValue = name.trim()
        require(nameValue.isNotEmpty() && nameValue.length <= 120) { "Enter a name of at most 120 characters." }
        val latitudeValue = latitude.trim().toDoubleOrNull()
        require(latitudeValue != null && latitudeValue.isFinite() && latitudeValue in -90.0..90.0) {
            "Latitude must be between -90 and 90 degrees."
        }
        val longitudeValue = longitude.trim().toDoubleOrNull()
        require(longitudeValue != null && longitudeValue.isFinite() && longitudeValue in -180.0..180.0) {
            "Longitude must be between -180 and 180 degrees."
        }
        val radiusValue = radiusMeters.trim().toFloatOrNull()
        require(radiusValue != null && radiusValue.isFinite() && radiusValue in 50f..5000f) {
            "Radius must be between 50 and 5000 meters."
        }
        val entryValue = entryGraceMinutes.trim().toIntOrNull()
        require(entryValue != null && entryValue in 0..120) { "Arrival walking grace must be 0 to 120 minutes." }
        val exitValue = exitGraceMinutes.trim().toIntOrNull()
        require(exitValue != null && exitValue in 0..120) { "Exit/departure grace must be 0 to 120 minutes." }
        return Office(id, nameValue, latitudeValue, longitudeValue, radiusValue, enabled,
            countsTowardAttendance, entryValue, exitValue)
    }

    companion object {
        fun from(office: Office) = OfficeDraft(
            office.name, office.latitude.toString(), office.longitude.toString(),
            office.radiusMeters.toString(), office.enabled, office.countsTowardAttendance,
            office.entryGraceMinutes.toString(), office.exitGraceMinutes.toString(),
        )
    }
}
