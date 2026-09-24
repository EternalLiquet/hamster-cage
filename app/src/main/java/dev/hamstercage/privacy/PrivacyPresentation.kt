package dev.hamstercage.privacy

import dev.hamstercage.data.StorageState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** A Ready snapshot is always read from a new Room subscription after journal Idle. */
data class PrivacyStorageState(val reset: PrivacyResetState, val storage: StorageState)

@OptIn(ExperimentalCoroutinesApi::class)
fun privacyVisibleStorage(reset: Flow<PrivacyResetState>, freshStorage: () -> Flow<StorageState>): Flow<PrivacyStorageState> =
    reset.distinctUntilChanged().flatMapLatest { state ->
        if (state is PrivacyResetState.Idle) freshStorage()
            .map { PrivacyStorageState(state, it) }
            .onStart { emit(PrivacyStorageState(state, StorageState.Loading)) }
        else flowOf(PrivacyStorageState(state, StorageState.Unavailable))
    }
