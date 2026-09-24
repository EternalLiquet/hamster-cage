package dev.hamstercage.privacy

import android.app.ActivityManager
import android.content.Context
import dev.hamstercage.capture.CaptureController
import dev.hamstercage.capture.CaptureHealth
import dev.hamstercage.capture.CaptureHealthStore
import dev.hamstercage.capture.CaptureWriteGate
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.capture.CoverageStore
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.data.HamsterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** A pending journal hides old derived state and is retried after process restart. */
class PrivacyController private constructor(context: Context) {
    private val application = context.applicationContext
    private val repository = HamsterRepository.get(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try { recoverPending() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CaptureHealth.registration(RegistrationStatus.FAILED) }
        }
    }

    val state get() = PrivacyResetStore.state(application)

    suspend fun deleteHistory() {
        finishHistoryDeletion(beginIfNeeded = true)
    }

    suspend fun recoverPending() {
        finishHistoryDeletion(beginIfNeeded = false)
    }

    private suspend fun finishHistoryDeletion(beginIfNeeded: Boolean) {
        var completed = false
        CaptureWriteGate.mutex.withLock {
            val current = PrivacyResetStore.read(application)
            val pending = when {
                current is PrivacyResetState.Pending -> current
                beginIfNeeded && current is PrivacyResetState.Idle -> PrivacyResetStore.begin(application)
                current is PrivacyResetState.Idle -> return@withLock
                else -> throw IllegalStateException("Privacy reset unavailable")
            }
            CaptureHealth.registration(RegistrationStatus.REGISTERING)
            // Repeated after interruption: an already-empty Room transaction is harmless.
            repository.deleteAttendanceAndCalendarHistory()
            CoverageStore.change(application) { CoverageLedger() }
            CaptureHealthStore.setDeliveryFailure(application, false)
            PrivacyResetStore.complete(application, pending.generation)
            completed = true
        }
        if (completed) {
            try { CaptureController.get(application).refreshFromSystem() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CaptureHealth.registration(RegistrationStatus.FAILED) }
        }
    }

    /** Android clears every app-private store and terminates this process; reopening starts fresh. */
    fun resetAllAppData(): Boolean = (application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .clearApplicationUserData()

    companion object {
        @Volatile private var instance: PrivacyController? = null
        fun get(context: Context): PrivacyController = instance ?: synchronized(this) {
            instance ?: PrivacyController(context).also { instance = it }
        }
    }
}
