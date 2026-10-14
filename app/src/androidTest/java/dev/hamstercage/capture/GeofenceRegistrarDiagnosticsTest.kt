package dev.hamstercage.capture

import android.Manifest
import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.GeofencingRequest
import dev.hamstercage.data.RegistrationAttempt
import dev.hamstercage.data.RegistrationCycle
import dev.hamstercage.data.RegistrationKind
import dev.hamstercage.data.RegistrationLog
import dev.hamstercage.data.RegistrationOutcome
import dev.hamstercage.data.RegistrationReason
import dev.hamstercage.data.RegistrationStep
import dev.hamstercage.offices.OfficeRegistrationIntent
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Registration diagnostics record what the registrar actually attempted; behaviour is unchanged. */
@RunWith(AndroidJUnit4::class)
class GeofenceRegistrarDiagnosticsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = OfficeRegistrationIntent("a", 0.0, 0.0, 150f)
    private val b = OfficeRegistrationIntent("b", 0.0, 0.0, 150f)
    private var tick = 0L
    private val clock = { Instant.ofEpochSecond(1_700_000_000L + tick++) }

    @Before fun grantLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, it)
        }
    }

    private fun registrar(fences: Fences, log: MemoryLog) =
        GeofenceRegistrar(context, operations = fences, log = log, clock = clock)

    @Test fun eachAddIsPersistedPendingBeforeThePlatformCallThenSucceeds() = runBlocking {
        val log = MemoryLog()
        val fences = Fences(log)
        val registrar = registrar(fences, log)
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(b, a), true,
            reason = RegistrationReason.FOREGROUND_CHECK, versions = mapOf("a" to 3L)).registration)

        assertEquals(listOf("a", "b"), fences.pendingWhenAdded)
        val cycle = log.cycles.values.single()
        assertEquals(RegistrationKind.SYNC, cycle.kind)
        assertEquals(RegistrationReason.FOREGROUND_CHECK, cycle.reason)
        assertEquals(RegistrationOutcome.SUCCEEDED, cycle.outcome)
        assertEquals(RegistrationStep.REMOVED, cycle.removal)
        assertNull(cycle.cleanup)
        assertEquals(2, cycle.plannedCount)
        val attempts = log.attempts.values.toList()
        assertEquals(listOf("a", "b"), attempts.map { it.officeId })
        assertEquals(listOf(3L, null), attempts.map { it.officeVersion })
        assertEquals(listOf(0, 0), attempts.map { it.initialTrigger })
        assertTrue(attempts.all { it.status == RegistrationOutcome.SUCCEEDED && it.cycleId == cycle.id &&
            it.completedAt!! > it.startedAt })

        // A skipped no-op check is not an attempt; a forced check is.
        registrar.synchronize(listOf(a, b), true)
        assertEquals(1, log.cycles.size)
        registrar.synchronize(listOf(a, b), true, force = true)
        assertEquals(2, log.cycles.size)
        assertEquals(RegistrationReason.UNKNOWN, log.cycles.values.last().reason)
        assertEquals(4, log.attempts.size)
    }

    @Test fun rejectedExitResyncIsLoggedWithItsEnterTrigger() = runBlocking {
        val log = MemoryLog()
        val registrar = registrar(Fences(log), log)
        registrar.synchronize(listOf(a, b), true)
        assertTrue(registrar.resyncInside(listOf(a), 0L, mapOf("a" to 7L)))
        val cycle = log.cycles.values.last()
        assertEquals(RegistrationKind.RESYNC, cycle.kind)
        assertEquals(RegistrationReason.REJECTED_EXIT_RESYNC, cycle.reason)
        assertEquals(RegistrationOutcome.SUCCEEDED, cycle.outcome)
        val attempt = log.attempts.values.last()
        assertEquals("a", attempt.officeId)
        assertEquals(7L, attempt.officeVersion)
        assertEquals(GeofencingRequest.INITIAL_TRIGGER_ENTER, attempt.initialTrigger)
        assertEquals(RegistrationOutcome.SUCCEEDED, attempt.status)
    }

    @Test fun partialBatchFailureShowsTheEarlierSuccessAndItsCleanup() = runBlocking {
        val log = MemoryLog()
        val fences = Fences(log).apply { failAddNumber = 2 }
        val registrar = registrar(fences, log)
        assertEquals(RegistrationStatus.FAILED, registrar.synchronize(listOf(a, b), true).registration)
        val attempts = log.attempts.values.toList()
        assertEquals(RegistrationOutcome.SUCCEEDED, attempts[0].status)
        assertEquals(RegistrationOutcome.FAILED, attempts[1].status)
        assertEquals("API_1000", attempts[1].errorCode)
        val cycle = log.cycles.values.single()
        assertEquals(RegistrationOutcome.FAILED, cycle.outcome)
        assertEquals(RegistrationStep.REMOVED, cycle.cleanup)
        assertEquals("API_1000", cycle.errorCode)
        assertTrue(fences.registered.isEmpty())

        // Cleanup that fails too is recorded as such; registration still reports FAILED.
        fences.failAddNumber = fences.adds + 2
        fences.failRemovalCall = fences.removals + 2
        assertEquals(RegistrationStatus.FAILED, registrar.synchronize(listOf(a, b), true).registration)
        assertEquals(RegistrationStep.FAILED, log.cycles.values.last().cleanup)
        assertEquals(RegistrationStep.REMOVED, log.cycles.values.last().removal)
    }

    @Test fun lostCompletionIsInterruptedNeverSuccessAndEarlierPendingRowsAreResolvedOnce() = runBlocking {
        val log = MemoryLog()
        val fences = Fences(log).apply { hang = true }
        val registrar = registrar(fences, log)
        assertNull(withTimeoutOrNull(500) { registrar.synchronize(listOf(a), true) })
        assertEquals(RegistrationOutcome.INTERRUPTED, log.attempts.values.single().status)
        assertEquals(RegistrationOutcome.INTERRUPTED, log.cycles.values.single().outcome)

        // Rows a previous process left PENDING are resolved by the first registrar use, not as success.
        val earlier = RegistrationAttempt("earlier", "earlier-cycle", "a", null, 0, Instant.EPOCH, null,
            RegistrationOutcome.PENDING, null)
        log.attempts[earlier.id] = earlier
        val restarted = registrar(Fences(log), log)
        restarted.synchronize(listOf(a), true)
        restarted.synchronize(listOf(a), true, force = true)
        // One sweep per registrar instance (process), not one per call.
        assertEquals(2, log.sweeps)
        assertEquals(RegistrationOutcome.INTERRUPTED, log.attempts.getValue("earlier").status)
    }

    @Test fun notReadyRemovalIsLoggedOnlyWhenItCanRemoveAppliedFencesOrIsForced() = runBlocking {
        val log = MemoryLog()
        val registrar = registrar(Fences(log), log)
        assertEquals(RegistrationStatus.NEEDS_SETUP, registrar.synchronize(listOf(a), false).registration)
        assertTrue(log.cycles.isEmpty())
        registrar.synchronize(listOf(a), true)
        registrar.synchronize(listOf(a), false, reason = RegistrationReason.MONITORING_SETTING)
        val removal = log.cycles.values.last()
        assertEquals(RegistrationKind.REMOVE, removal.kind)
        assertEquals(RegistrationReason.MONITORING_SETTING, removal.reason)
        assertEquals(RegistrationOutcome.SUCCEEDED, removal.outcome)
        assertEquals(2, log.cycles.size)
        registrar.synchronize(listOf(a), false)
        assertEquals(2, log.cycles.size)
        registrar.synchronize(listOf(a), false, force = true)
        assertEquals(3, log.cycles.size)
    }

    @Test fun failingDiagnosticsNeverChangeRegistration() = runBlocking {
        val log = MemoryLog().apply { failWrites = true }
        val fences = Fences(log, checkPending = false)
        val registrar = registrar(fences, log)
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(a, b), true).registration)
        assertEquals(setOf("a", "b"), fences.registered)
        assertTrue(registrar.resyncInside(listOf(a), 0L))
    }

    private class MemoryLog : RegistrationLog {
        val cycles = linkedMapOf<String, RegistrationCycle>()
        val attempts = linkedMapOf<String, RegistrationAttempt>()
        var sweeps = 0
        var failWrites = false
        private fun write() = check(!failWrites) { "synthetic diagnostic storage failure" }
        override suspend fun markInterrupted() {
            write(); sweeps++
            attempts.replaceAll { _, row -> if (row.status == RegistrationOutcome.PENDING) row.copy(status = RegistrationOutcome.INTERRUPTED) else row }
            cycles.replaceAll { _, row -> if (row.outcome == RegistrationOutcome.PENDING) row.copy(outcome = RegistrationOutcome.INTERRUPTED) else row }
        }
        override suspend fun cycleStarted(cycle: RegistrationCycle) { write(); cycles[cycle.id] = cycle }
        override suspend fun cycleFinished(id: String, completedAt: Instant, outcome: RegistrationOutcome,
            removal: RegistrationStep?, cleanup: RegistrationStep?, errorCode: String?) {
            write()
            cycles[id]?.takeIf { it.outcome == RegistrationOutcome.PENDING }?.let {
                cycles[id] = it.copy(completedAt = completedAt, outcome = outcome, removal = removal,
                    cleanup = cleanup, errorCode = errorCode)
            }
        }
        override suspend fun attemptStarted(attempt: RegistrationAttempt) { write(); attempts[attempt.id] = attempt }
        override suspend fun attemptFinished(id: String, completedAt: Instant, status: RegistrationOutcome, errorCode: String?) {
            write()
            attempts[id]?.takeIf { it.status == RegistrationOutcome.PENDING }?.let {
                attempts[id] = it.copy(completedAt = completedAt, status = status, errorCode = errorCode)
            }
        }
    }

    private class Fences(private val log: MemoryLog, private val checkPending: Boolean = true) : FenceOperations {
        var failAddNumber: Int? = null
        var failRemovalCall: Int? = null
        var hang = false
        var adds = 0
        var removals = 0
        val registered = mutableSetOf<String>()
        val pendingWhenAdded = mutableListOf<String>()

        override suspend fun remove(intent: PendingIntent) {
            removals++
            if (removals == failRemovalCall) error("synthetic cleanup failure")
            registered.clear()
        }

        override suspend fun add(request: GeofencingRequest, intent: PendingIntent) {
            adds++
            val ids = request.geofences.map { it.requestId }
            if (checkPending) {
                // The attempt row must already exist, still PENDING, when Play services is called.
                val pending = log.attempts.values.last()
                assertEquals(ids.single(), pending.officeId)
                assertEquals(RegistrationOutcome.PENDING, pending.status)
                pendingWhenAdded += pending.officeId
            }
            if (hang) awaitCancellation()
            if (adds == failAddNumber) throw ApiException(Status(1000))
            registered += ids
        }
    }
}
