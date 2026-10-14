package dev.hamstercage.privacy

import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RegistrationAttempt
import dev.hamstercage.data.RegistrationOutcome
import dev.hamstercage.domain.Policy
import java.time.Instant
import dev.hamstercage.data.RegistrationDiagnostics
import dev.hamstercage.data.StorageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PrivacyDiagnosticsPresentationTest {
    @Test fun newGenerationWaitsForFreshDiagnosticsEvenWhenRoomAndCoverageAreReady() = runBlocking {
        val reset = MutableStateFlow<PrivacyResetState>(PrivacyResetState.Idle(0))
        val freshDiagnostics = CompletableDeferred<Unit>()
        val previous = RegistrationDiagnostics(emptyList(), listOf(RegistrationAttempt(
            "deleted-attempt", "deleted-cycle", "synthetic-office", 1, 0, Instant.EPOCH,
            null, RegistrationOutcome.PENDING, null)))
        val ready = StorageState.Ready(AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy()))
        val after = RegistrationDiagnostics(emptyList(), emptyList())
        var subscriptions = 0
        val states = mutableListOf<PrivacyStorageState>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            privacyVisibleStorage(reset, freshStorage = { flow { emit(ready) } },
                freshDiagnostics = { flow {
                    subscriptions++
                    if (subscriptions == 1) emit(previous)
                    else { freshDiagnostics.await(); emit(after) }
                } }).collect { states += it }
        }
        suspend fun awaitState(predicate: () -> Boolean) = withTimeout(5_000) {
            while (!predicate()) yield()
        }
        try {
            awaitState { states.lastOrNull()?.registrationDiagnostics === previous }
            reset.value = PrivacyResetState.Pending(1)
            awaitState { states.lastOrNull()?.reset == PrivacyResetState.Pending(1) }
            assertNull(states.last().registrationDiagnostics)
            reset.value = PrivacyResetState.Idle(1)
            awaitState { subscriptions == 2 }
            assertEquals(PrivacyResetState.Idle(1), states.last().reset)
            assertNull(states.last().registrationDiagnostics)
            assertEquals(StorageState.Loading, states.last().storage)
            freshDiagnostics.complete(Unit)
            awaitState { states.lastOrNull()?.registrationDiagnostics === after }
            assertEquals(ready, states.last().storage)
            assertTrue(states.filter { it.reset == PrivacyResetState.Idle(1) }
                .none { it.registrationDiagnostics === previous })
        } finally { collection.cancelAndJoin() }
    }
}
