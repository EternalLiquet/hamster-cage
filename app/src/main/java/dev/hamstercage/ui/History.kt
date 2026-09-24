package dev.hamstercage.ui

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hamstercage.domain.*
import dev.hamstercage.data.EventEvidence
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun HistoryScreen(input: AttendanceInput,result: AttendanceResult,manualIds: Set<String>,evidence: List<EventEvidence>,monitoringActive: Boolean,onCorrect: (Session?,LocalDate) -> Unit,onReviewed: (LocalDate)->Unit) {
    val today=input.now.atZone(input.policy.zoneId).toLocalDate()
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var dayCount by remember { mutableIntStateOf(14) }
    val date=selected
    BackHandler(enabled=date!=null) { selected=null }
    if(date != null) {
        DayDetail(input,result,date,manualIds,evidence,monitoringActive,{selected=null},{onCorrect(it,date)},{onReviewed(date)})
        return
    }
    ScreenColumn {
        PageHeading("History","Every total has a trail.")
        Button(onClick={onCorrect(null,today)},enabled=input.offices.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("Add missing session") }
        if(input.offices.isEmpty()) Text("Add an office before recording a missing session.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        if(input.events.isEmpty() && input.manualSessions.isEmpty()) Notice("A clean slate","No attendance events yet. Automatic visits appear here after office setup. If detection misses a visit, add it manually and keep its source visible.")
        repeat(dayCount) { offset ->
            val day=today.minusDays(offset.toLong())
            val summary=AttendanceEngine.daily(input,result,day)
            val sessions=sessionsForDay(input,result,day)
            Panel {
                Text(day.format(DateTimeFormatter.ofPattern("EEE, MMM d")),style=MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(input.policy.excludedDates.any { it.date == day }) Tag("EXCLUDED")
                    if(day in input.policy.wfhDates) Tag("WFH")
                    if(sessions.any { it.confidence == Confidence.LOW || it.reviewReasons.any { reason -> reason != ReviewReason.OPEN_SESSION } }) Tag("REVIEW",true)
                    if(sessions.any { it.manualSessionId != null || it.correctionId != null || it.sourceEventIds.any(manualIds::contains) }) Tag("MANUAL")
                }
                MetricRow("Recorded credit",minutesText(summary.creditedMinutes))
                MetricRow("Required",minutesText(summary.requiredMinutes.toDouble()))
                MetricRow("Balance",if(summary.hasCompleteHistory) balanceText(summary.balanceMinutes) else "Unknown coverage",true)
                OutlinedButton(onClick={selected=day}) { Text("View day") }
            }
        }
        if(dayCount < 90) TextButton(onClick={dayCount=(dayCount+14).coerceAtMost(90)},modifier=Modifier.fillMaxWidth()) { Text("Show earlier days") }
    }
}

private fun sessionsForDay(input: AttendanceInput,result: AttendanceResult,date: LocalDate): List<Session> {
    val a=date.atStartOfDay(input.policy.zoneId).toInstant()
    val b=date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
    return result.sessions.filter { s ->
        val start=s.start ?: s.end
        start != null && start.isBefore(b) && (s.end ?: input.now).isAfter(a)
    }
}

@Composable
private fun DayDetail(input: AttendanceInput,result: AttendanceResult,date: LocalDate,manualIds: Set<String>,evidence: List<EventEvidence>,monitoringActive: Boolean,onBack: ()->Unit,onCorrect: (Session?)->Unit,onReviewed: ()->Unit) {
    var confirmReview by remember { mutableStateOf(false) }
    val summary=AttendanceEngine.daily(input,result,date)
    val sessions=sessionsForDay(input,result,date)
    val dateEvents=input.events.filter { it.at.atZone(input.policy.zoneId).toLocalDate() == date }.sortedBy { it.at }
    val a=date.atStartOfDay(input.policy.zoneId).toInstant()
    val b=date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
    ScreenColumn {
        TextButton(onClick=onBack) { Text("‹ All history") }
        PageHeading(date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),input.policy.zoneId.id)
        Panel(warm=true) {
            Text(minutesText(summary.creditedMinutes),style=MaterialTheme.typography.displaySmall)
            Text("Final credited total",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            MetricRow("Required",minutesText(summary.requiredMinutes.toDouble()))
            val exclusion=input.policy.excludedDates.find { it.date == date }
            Text(when {
                exclusion!=null -> "Excluded from required days: ${readable(exclusion.reason.name)}${if(exclusion.note.isBlank()) "." else ". ${exclusion.note}"}"
                date.dayOfWeek !in input.policy.expectedWeekdays -> "Not an expected weekday; this date adds no requirement."
                date in input.policy.wfhDates -> "WFH is a label. This expected workday still counts toward the requirement."
                else -> "Expected workday; included in the attendance denominator."
            },style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            if(!summary.hasCompleteHistory) {
                Text("Coverage is incomplete. Recorded credit is a lower-bound record, not proof of absence.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Amber)
                val today=input.now.atZone(input.policy.zoneId).toLocalDate()
                val canReview=date.isBefore(today) || (date==today && monitoringActive)
                OutlinedButton(onClick={confirmReview=true},enabled=canReview) { Text(if(date==today) "Review today through now" else "Mark whole day reviewed") }
                if(!canReview) Text("Today is still in progress. Enable automatic detection to review coverage through now, or review the whole day after it ends.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            }
        }
        Text("RECONSTRUCTED SESSIONS",style=MaterialTheme.typography.labelMedium,color=CageStyle.Secondary)
        if(sessions.isEmpty()) Notice("No reconstructed sessions","Missing events cannot establish a visit. Add a manual session if you know the observed start and end.")
        sessions.forEach { session ->
            val office=input.offices.find { it.id == session.officeId }
            Panel {
                Text(office?.name ?: "Unknown office",style=MaterialTheme.typography.titleMedium)
                Tag(if(session.manualSessionId!=null || session.sourceEventIds.any(manualIds::contains)) "MANUAL ENTRY" else readable(session.confidence.name).uppercase(),session.confidence==Confidence.LOW)
                MetricRow("Observed start",session.start?.let { dateTime(it,input.policy.zoneId) } ?: "Missing enter")
                MetricRow("Observed end",session.end?.let { dateTime(it,input.policy.zoneId) } ?: "Open · through now")
                MetricRow("Office grace","${office?.entryGraceMinutes ?: 0}m before / ${office?.exitGraceMinutes ?: 0}m after exit")
                if(office!=null && (!office.enabled || !office.countsTowardAttendance)) Text("This office is currently ineligible; its saved session contributes no attendance credit.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                if(session.reviewReasons.isNotEmpty()) Text(session.reviewReasons.joinToString(" · ") { readable(it.name) },style=MaterialTheme.typography.bodyMedium,color=CageStyle.Amber)
                if(session.correctionId!=null) Text("A manual correction is active. Original events are retained below.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                OutlinedButton(onClick={onCorrect(session)}) { Text("Correct this session") }
                input.manualSessions.find { it.id==session.manualSessionId }?.let { original ->
                    HorizontalDivider(color=CageStyle.Outline)
                    Text("Original manual entry",style=MaterialTheme.typography.labelLarge)
                    Text("${dateTime(original.start,input.policy.zoneId)} → ${original.end?.let { dateTime(it,input.policy.zoneId) } ?: "Open"}",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                    if(original.note.isNotBlank()) Text(original.note,style=MaterialTheme.typography.bodyMedium)
                    Text("Entered ${dateTime(original.createdAt,input.policy.zoneId)}",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                }
                input.corrections.filter { it.sessionId==session.id }.sortedByDescending { it.createdAt }.forEach { c ->
                    HorizontalDivider(color=CageStyle.Outline)
                    Text("Correction · ${dateTime(c.createdAt,input.policy.zoneId)}",style=MaterialTheme.typography.labelLarge)
                    Text("${dateTime(c.start,input.policy.zoneId)} → ${c.end?.let { dateTime(it,input.policy.zoneId) } ?: "Open"}",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                    if(c.note.isNotBlank()) Text(c.note,style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Button(onClick={onCorrect(null)},enabled=input.offices.isNotEmpty()) { Text("Add missing session") }
        Panel {
            Text("Credited intervals",style=MaterialTheme.typography.titleMedium)
            Text("Grace is applied, then overlapping intervals are united. Short same-office gaps may be merged. Each minute counts once.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            val intervals=result.intervals.filter { it.start.isBefore(b) && it.end.isAfter(a) }
            if(intervals.isEmpty()) Text("No credited intervals for this date.",style=MaterialTheme.typography.bodyMedium)
            intervals.forEach { interval ->
                val start=interval.start.coerceAtLeast(a);val end=interval.end.coerceAtMost(b)
                MetricRow("${instantText(start,input.policy.zoneId)} – ${instantText(end,input.policy.zoneId)}",minutesText(Duration.between(start,end).toMillis()/60000.0))
                if(interval.reconciledGap) Tag("SHORT GAP MERGED")
            }
        }
        Panel {
            Text("Original event trail",style=MaterialTheme.typography.titleMedium)
            Text("Corrections never overwrite these facts. Geofence time is receipt time; delivery can lag the actual crossing.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            if(dateEvents.isEmpty()) Text("No original events on this date.",style=MaterialTheme.typography.bodyMedium)
            dateEvents.forEach { event ->
                HorizontalDivider(color=CageStyle.Outline)
                Text("${event.transition.name} · ${input.offices.find { it.id==event.officeId }?.name ?: "Unknown office"}",style=MaterialTheme.typography.labelLarge)
                Text(dateTime(event.at,input.policy.zoneId),style=MaterialTheme.typography.bodyMedium)
                Text(if(event.id in manualIds) "Source: manual entry" else "Source: device geofence",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                evidence.find { it.id==event.id }?.let { fact ->
                    Text("Received: ${dateTime(fact.receivedAt,input.policy.zoneId)}",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                    fact.observedLocationAt?.let { Text("Location fix: ${dateTime(it,input.policy.zoneId)}",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary) }
                }
            }
        }
    }
    if(confirmReview) AlertDialog(
        onDismissRequest={confirmReview=false},
        title={Text(if(date==input.now.atZone(input.policy.zoneId).toLocalDate()) "Confirm coverage through now?" else "Confirm the whole day?")},
        text={Text("Confirm that all visits and gaps on $date${if(date==input.now.atZone(input.policy.zoneId).toLocalDate()) " through now" else ""} have been checked and any missing sessions added. This clears the coverage warning for this date. Original events and corrections remain unchanged.")},
        confirmButton={TextButton(onClick={onReviewed();confirmReview=false}) {Text("Confirm reviewed coverage")}},
        dismissButton={TextButton(onClick={confirmReview=false}) {Text("Cancel")}}
    )
}

private fun dateTime(at: Instant,zone: ZoneId): String = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(zone).format(at)
private fun editTime(at: Instant,zone: ZoneId): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(at)

@Composable
fun SessionDialog(session: Session?,date: LocalDate,input: AttendanceInput,onDismiss: ()->Unit,saving: Boolean=false,onSave: (String,Instant,Instant?,String)->Unit) {
    var officeId by remember { mutableStateOf(session?.officeId ?: input.offices.firstOrNull()?.id ?: "") }
    var start by remember { mutableStateOf(session?.start?.let { editTime(it,input.policy.zoneId) } ?: "$date ") }
    var end by remember { mutableStateOf(session?.end?.let { editTime(it,input.policy.zoneId) } ?: "") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(if(session==null) "Add missing session" else "Correct session",onDismiss) {
        Text("Enter observed arrival and departure. Office grace is applied afterward. Times use ${input.policy.zoneId.id}.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        if(session==null) input.offices.forEach { office ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected=officeId==office.id,onClick={officeId=office.id})
                Text(office.name,style=MaterialTheme.typography.bodyLarge)
            }
        }
        Field("Start · YYYY-MM-DD HH:mm",start,{start=it})
        Field("End · blank if still in office",end,{end=it})
        Field("Reason or note",note,{note=it},singleLine=false)
        Text(if(session==null) "This creates a separate manual record. It does not verify other gaps in the day." else "Saves a new correction; original events and earlier corrections remain intact.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        FormError(error)
        FormActions(onDismiss,{
            try {
                require(officeId.isNotBlank()) { "Choose an office first." }
                val a=parseTime(start,input.policy.zoneId)
                val b=if(end.isBlank()) null else parseTime(end,input.policy.zoneId)
                require(b==null || b.isAfter(a)) { "End must be later than start." }
                require(!a.isAfter(Instant.now()) && (b==null || !b.isAfter(Instant.now()))) { "Attendance cannot be in the future." }
                require(note.length<=2000) { "Keep the note under 2000 characters." }
                onSave(officeId,a,b,note.trim())
            } catch(e: IllegalArgumentException) { error=e.message ?: "Check the session times." }
              catch(e: java.time.DateTimeException) { error="Use a valid date and time, such as 2026-09-23 09:00." }
        },saveText=if(saving) "Saving…" else if(session==null) "Add session" else "Save correction",enabled=!saving)
    }
}

private fun parseTime(text: String,zone: ZoneId): Instant {
    val normalized=text.trim().replace(' ','T')
    runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()?.let { return it }
    val local=LocalDateTime.parse(normalized)
    val offsets=zone.rules.getValidOffsets(local)
    require(offsets.size==1) { "This time is skipped or repeated by daylight saving. Include an offset, e.g. 2026-11-01T01:30-04:00." }
    return local.toInstant(offsets.first())
}
