package dev.hamstercage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import kotlinx.coroutines.delay

/** Fresh immediately on becoming visible, then on minute boundaries. No background timer or service. */
@Composable
internal fun rememberVisibleNow(clock: TimeSource, enabled: Boolean, refreshMillis: Long = 60_000L): Instant {
    var now by remember(clock) { mutableStateOf(clock.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, clock, enabled, refreshMillis) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock.now()
                delay(refreshMillis - Math.floorMod(now.toEpochMilli(), refreshMillis))
            }
        }
    }
    return now
}
