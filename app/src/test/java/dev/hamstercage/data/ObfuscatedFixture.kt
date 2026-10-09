package dev.hamstercage.data

import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.Correction
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Adapts the transformed #133 diagnostic export into domain inputs. Pseudonym IDs are kept;
 * coordinates are synthetic. Exported sessions and flags are observed baseline outputs only and
 * are deliberately not used as inputs. `beforeSpan` history becomes a date before the evidence.
 */
internal object ObfuscatedFixture {
    private const val PATH = "/fixtures/attendance-review-projection-obfuscated.json"

    @Suppress("UNCHECKED_CAST")
    private val json: Map<String, Any?> by lazy {
        val text = requireNotNull(ObfuscatedFixture::class.java.getResource(PATH)).readText()
        JsonReader(text).value() as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = json[key] as List<Map<String, Any?>>
    private fun Map<String, Any?>.instant(key: String) = (this[key] as String?)?.let(Instant::parse)

    val zone: ZoneId get() = ZoneId.of(json["policyZone"] as String)
    val selectedDay: LocalDate get() = LocalDate.parse(json["selectedLocalDay"] as String)
    val evaluatedAt: Instant get() = Instant.parse(json["evaluatedAt"] as String)
    val exportedCreditedMinutes: Double get() = json["creditedMinutes"] as Double

    /** Correction targets are pseudonyms; each maps to its exported session's earliest fact alias. */
    private val targetSessions: Map<String, String> by lazy {
        val at = list("observations").associate { it["fact"] as String to it.instant("eventAt")!! }
        list("sessions").flatMap { session ->
            @Suppress("UNCHECKED_CAST")
            val facts = session["facts"] as List<String>
            val first = facts.minWith(compareBy<String> { at.getValue(it) }.thenBy { it })
            @Suppress("UNCHECKED_CAST")
            (session["correctionTargets"] as List<String>).map { it to "session:$first" }
        }.toMap()
    }

    fun snapshot(): AppSnapshot {
        @Suppress("UNCHECKED_CAST")
        val policy = json["policy"] as Map<String, Any?>
        val offices = list("offices").map {
            Office(it["alias"] as String, it["alias"] as String, 0.0, 0.0,
                (it["radiusMeters"] as Double).toFloat(), it["enabled"] as Boolean,
                it["countsTowardAttendance"] as Boolean, (it["entryGraceMinutes"] as Double).toInt(),
                (it["exitGraceMinutes"] as Double).toInt())
        }
        val events = list("observations").map {
            RecordedEvent(RawEvent(it["fact"] as String, it["office"] as String,
                Transition.valueOf(it["transition"] as String), it.instant("eventAt")!!),
                it.instant("receivedAt")!!, it.instant("observedLocationAt"), it["source"] as String,
                (it["accuracyMeters"] as Double?)?.toFloat())
        }
        val corrections = list("corrections").map {
            Correction(it["edit"] as String, targetSessions.getValue(it["target"] as String),
                it.instant("start")!!, it.instant("end"), it.instant("createdAt")!!,
                revertToOriginal = it["revertToOriginal"] as Boolean,
                appendSequence = (it["appendSequence"] as Double).toLong())
        }
        @Suppress("UNCHECKED_CAST")
        return AppSnapshot(offices, events, corrections, emptyList(), Policy(zone,
            (policy["targetMinutesPerDay"] as Double).toInt(),
            (policy["expectedWeekdays"] as List<String>).map(DayOfWeek::valueOf).toSet(),
            shortGapMinutes = (policy["shortGapMinutes"] as Double).toInt(),
            maxOpenSessionHours = (policy["maxOpenSessionHours"] as Double).toInt()))
    }

    /** The app's evaluation path: repository verification state plus the coverage ledger's dates. */
    fun input(snapshot: AppSnapshot = snapshot(), now: Instant = evaluatedAt): AttendanceInput {
        @Suppress("UNCHECKED_CAST")
        val capture = json["capture"] as Map<String, Any?>
        return snapshot.input(now).copy(historyStartDate = selectedDay.minusDays(60),
            unknownDates = if (capture["selectedDayUnknown"] == true) setOf(selectedDay) else emptySet())
    }
}

/** Just enough JSON for a checked-in fixture: objects, arrays, strings, numbers, booleans, null. */
private class JsonReader(private val text: String) {
    private var index = 0
    fun value(): Any? {
        skip()
        return when (val c = text[index]) {
            '{' -> { index++; buildMap { skip(); if (text[index] == '}') { index++; return@buildMap }
                while (true) { val key = value() as String; expect(':'); put(key, value()); skip()
                    if (text[index++] == '}') break } } }
            '[' -> { index++; buildList { skip(); if (text[index] == ']') { index++; return@buildList }
                while (true) { add(value()); skip(); if (text[index++] == ']') break } } }
            '"' -> { val end = text.indexOf('"', index + 1); text.substring(index + 1, end).also { index = end + 1 } }
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> { require(c == '-' || c.isDigit()) { "Unexpected '$c' at $index" }
                val start = index; while (index < text.length && text[index] in "+-0123456789.eE") index++
                text.substring(start, index).toDouble() }
        }
    }
    private fun literal(word: String, result: Any?): Any? {
        require(text.startsWith(word, index)); index += word.length; return result
    }
    private fun expect(c: Char) { skip(); require(text[index++] == c) }
    private fun skip() { while (index < text.length && text[index].isWhitespace()) index++ }
}
