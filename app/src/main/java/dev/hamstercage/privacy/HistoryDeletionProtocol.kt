package dev.hamstercage.privacy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A journal-first, retryable operation: partial cleanup is never exposed as a finished delete. */
internal class HistoryDeletionProtocol(
    private val journal: PrivacyResetJournal,
    private val gate: Mutex,
    private val deleteFacts: suspend () -> Unit,
    private val resetCoverage: suspend () -> Unit,
    private val resetHealth: suspend () -> Unit,
) {
    suspend fun run(beginIfNeeded: Boolean): Boolean = gate.withLock {
        val current = journal.read()
        val pending = when {
            current is PrivacyResetState.Pending -> current
            beginIfNeeded && current is PrivacyResetState.Idle -> journal.begin()
            current is PrivacyResetState.Idle -> return@withLock false
            else -> throw IllegalStateException("Privacy reset unavailable")
        }
        // An exception leaves the pending journal in place. Room rolls its own facts
        // transaction back; the next process retries both it and metadata cleanup.
        deleteFacts()
        resetCoverage()
        resetHealth()
        journal.complete(pending.generation)
        true
    }
}
