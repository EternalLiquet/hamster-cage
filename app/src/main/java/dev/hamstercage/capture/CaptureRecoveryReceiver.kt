package dev.hamstercage.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hamstercage.data.RegistrationReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Protected system actions only; no foreground service or background activity launch. */
class CaptureRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
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
}
