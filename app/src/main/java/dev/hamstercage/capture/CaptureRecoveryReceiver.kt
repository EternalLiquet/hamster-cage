package dev.hamstercage.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hamstercage.data.RegistrationReason
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Protected system actions only (boot, app update, shutdown); no foreground service or background activity launch. */
class CaptureRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SHUTDOWN) return recordShutdown(context)
        val reason = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> RegistrationReason.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> RegistrationReason.PACKAGE_REPLACED
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { CaptureController.get(context).refreshFromSystem(reason = reason) } }
            catch (_: Exception) { CaptureHealth.registration(RegistrationStatus.FAILED) }
            finally { pending.finish() }
        }
    }

    /**
     * Records when healthy monitoring ended, so the next boot's gap covers only the time the phone
     * was off. Android allows only a few seconds here; a missed write keeps the older bound.
     */
    private fun recordShutdown(context: Context) {
        val pending = goAsync()
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(2_000) { CoverageStore.change(context) { it.shutdown(now) } } }
            catch (_: Exception) { Unit }
            finally { pending.finish() }
        }
    }
}
