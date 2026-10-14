package dev.hamstercage.privacy

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
        val previous = RegistrationDiagnostics(emptyList(), emptyList())
        val after = RegistrationDiagnostics(emptyList(), emptyList())
        var subscriptions = 0
        val states = mutableListOf<PrivacyStorageState>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            privacyVisibleStorage(reset, freshStorage = { flow { emit(StorageState.Loading) } },
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
            freshDiagnostics.complete(Unit)
            awaitState { states.lastOrNull()?.registrationDiagnostics === after }
            assertTrue(states.filter { it.reset == PrivacyResetState.Idle(1) }
                .none { it.registrationDiagnostics === previous })
        } finally { collection.cancelAndJoin() }
    }
}
