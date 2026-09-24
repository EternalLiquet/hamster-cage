package dev.hamstercage.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.work.*
import dev.hamstercage.data.HamsterRepository
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class RecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val repository = HamsterRepository(applicationContext)
        repository.recordRegistration(0, null, "Office monitoring restarted. Review attendance around the interruption.")
        repository.requestRegistration(force = true)
        if (LocationPermissions.health(applicationContext).canTrack && repository.registrationState().third != null && runAttemptCount < 3)
            Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    companion object {
        fun enqueue(context: Context) {
            // Called on Dispatchers.IO. Wait for WorkManager's enqueue transaction, not job execution.
            WorkManager.getInstance(context).enqueueUniqueWork("restore-office-geofences", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RecoveryWorker>().build()).result.get(5, TimeUnit.SECONDS)
        }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, LocationManager.PROVIDERS_CHANGED_ACTION)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { RecoveryWorker.enqueue(context) } }
            catch (_: Exception) {
                context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("capture_failed", true).putLong("capture_failed_at", System.currentTimeMillis()).commit()
            }
            finally { pending.finish() }
        }
    }
}
