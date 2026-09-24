package dev.hamstercage.privacy

import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.data.StorageState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** Attendance and confidence metadata both come from fresh subscriptions after journal Idle. */
data class PrivacyStorageState(val reset: PrivacyResetState, val storage: StorageState,
    val coverage: CoverageLedger? = null)

@OptIn(ExperimentalCoroutinesApi::class)
fun privacyVisibleStorage(reset: Flow<PrivacyResetState>, freshStorage: () -> Flow<StorageState>,
    freshCoverage: () -> Flow<CoverageLedger?> = { flowOf(null) }): Flow<PrivacyStorageState> =
    reset.distinctUntilChanged().flatMapLatest { state ->
        if (state is PrivacyResetState.Idle) combine(freshStorage(), freshCoverage()) { storage, coverage ->
            PrivacyStorageState(state, storage, coverage)
        }
            .onStart { emit(PrivacyStorageState(state, StorageState.Loading)) }
        else flowOf(PrivacyStorageState(state, StorageState.Unavailable))
    }
