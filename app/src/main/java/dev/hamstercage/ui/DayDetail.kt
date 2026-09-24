package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DayDetailScreen(input: AttendanceInput, result: AttendanceResult, date: LocalDate, back: () -> Unit, edit: ((Session) -> Unit)? = null) {
    val detail = remember(input, result, date) { explainDay(input, result, date) }
    val offices = input.offices.associateBy { it.id }
    fun at(time: Instant?) = time?.atZone(input.policy.zoneId)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: "Missing boundary"
    fun office(id: String) = evidenceText(offices[id]?.name ?: "Unknown office")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        OutlinedButton(onClick = back) { Text("Back to daily history") }
        Text("Explain $date", style = MaterialTheme.typography.headlineSmall)
        Text("${input.policy.zoneId.id} · evaluated ${at(input.now)}", style = MaterialTheme.typography.bodyMedium)
        Panel {
            Text("Recorded credit: ${minutesText(detail.summary.creditedMinutes)}", Modifier.testTag("detail_credit"), style = MaterialTheme.typography.titleLarge)
            Text("Required: ${minutesText(detail.summary.requiredMinutes.toDouble())}", Modifier.testTag("detail_required"))
            Text(detail.denominator)
            if (!detail.summary.hasCompleteHistory) Notice("Unknown coverage", "The recorded credit is provisional. Missing history is not proof of zero attendance.")
            Text("These are the same daily engine totals used by Dashboard and History. Overlapping intervals count only once.")
        }
        EvidenceSection("Credited intervals", detail.intervals) { interval ->
            Text("${at(interval.start)} → ${at(interval.end)}")
            Text("${minutesText(interval.minutes)} · sessions ${interval.sessionIds.sorted().joinToString()}")
            if (interval.reconciledGap) Text("Includes a same-office gap reconciled by the ${input.policy.shortGapMinutes}-minute policy.")
            Text("Clipped to this policy-local day after grace, gap reconciliation and overlap union.")
        }
        EvidenceSection("Reconstructed sessions", detail.sessions) { session ->
            Text("${office(session.officeId)} · ${session.id}", style = MaterialTheme.typography.titleMedium)
            val original = detail.originalSessions.find { it.id == session.id }
            Text("Original bounds: ${at(original?.start)} → ${at(original?.end)}")
            if (session.correctionId != null && !session.correctionReverted) original?.reviewReasons?.forEach {
                Text("Original evidence: ${reviewExplanation(it)} The applied correction supplies effective bounds.")
            }
            Text("Effective bounds: ${at(session.start)} → ${at(session.end)}")
            if (session.isOpen) Text("Open: evaluated through ${at(input.now)}; no future EXIT is assumed.")
            offices[session.officeId]?.let { value ->
                Text("Grace: ${value.entryGraceMinutes}m before entry; ${value.exitGraceMinutes}m after a known exit, capped at now.")
                if (!value.enabled || !value.countsTowardAttendance) Text("This office is disabled or excluded from attendance credit.")
            }
            Text("Confidence: ${session.confidence.name}. Source events: ${session.sourceEventIds.sorted().joinToString().ifEmpty { "Manual interval" }}")
            session.correctionId?.let { Text(if (session.correctionReverted) "Reverted to original by audit entry: $it."
                else "Applied correction: $it. Original evidence is retained below.") }
            session.reviewReasons.forEach { Text(reviewExplanation(it)) }
            edit?.let { action -> OutlinedButton(onClick = { action(session) }) { Text("Correct ${session.id}") } }
        }
        EvidenceSection("Raw observations", detail.rawEvents) { event ->
            Text("${event.transition.name} · ${office(event.officeId)} · ${at(event.at)}")
            Text("Source event: ${event.id}", style = MaterialTheme.typography.bodySmall)
        }
        EvidenceSection("Manual source intervals", detail.manualSessions) { manual ->
            Text("${office(manual.officeId)} · ${manual.id}")
            Text("${at(manual.start)} → ${at(manual.end)} · entered ${at(manual.createdAt)}")
            if (manual.note.isNotBlank()) Text(evidenceText(manual.note))
        }
        EvidenceSection("Correction audit", detail.corrections) { correction ->
            Text("${correction.id} → ${correction.sessionId}")
            if (correction.revertToOriginal) Text("Revert to original reconstruction · entered ${at(correction.createdAt)}")
            else Text("${at(correction.start)} → ${at(correction.end)} · entered ${at(correction.createdAt)}")
            Text(if (detail.sessions.any { it.correctionId == correction.id })
                if (correction.revertToOriginal) "Currently restores original reconstruction." else "Applied to effective bounds."
                else "Retained audit entry; not the currently applied correction.")
            if (correction.note.isNotBlank()) Text(evidenceText(correction.note))
        }
        EvidenceSection("Review explanations", detail.reviews) { review ->
            Text(review.reason.name.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
            Text(reviewExplanation(review.reason))
            Text("Session: ${review.sessionId ?: "Unlinked"}; events: ${review.sourceEventIds.sorted().joinToString().ifEmpty { "None" }}")
        }
    }
}

@Composable
private fun <T> EvidenceSection(title: String, entries: List<T>, row: @Composable (T) -> Unit) {
    var offset by rememberSaveable(title) { mutableIntStateOf(0) }
    val page = offset.coerceAtMost(((entries.size - 1).coerceAtLeast(0) / 10) * 10)
    Text("$title (${entries.size})", style = MaterialTheme.typography.titleLarge)
    if (entries.isEmpty()) Text("No entries for this day.", color = CageStyle.Secondary)
    if (page > 0) TextButton(onClick = { offset = page - 10 }) { Text("Previous $title") }
    entries.drop(page).take(10).forEach { entry -> Panel { row(entry) } }
    if (page + 10 < entries.size) TextButton(onClick = { offset = page + 10 }) { Text("More $title") }
}
