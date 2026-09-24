package dev.hamstercage.capture

import android.content.Context
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.offices.registrationIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Process-scoped reconciliation: restart and office edits both reapply desired fences. */
class CaptureController private constructor(context: Context) {
    private data class Prerequisites(val ready: Boolean, val revision: Long)
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readiness = MutableStateFlow<Prerequisites?>(null)
    private val repository = HamsterRepository.get(application)
    private val registrar = GeofenceRegistrar(application)

    init {
        scope.launch {
            CaptureHealthStore.deliveryFailure(application).collect { failed ->
                if (failed) CaptureHealth.deliveryFailed() else CaptureHealth.deliverySucceeded()
            }
        }
        scope.launch {
            var lastRevision = -1L
            combine(repository.state, readiness) { state, prerequisites -> state to prerequisites }
                .collect { (state, prerequisites) ->
                    if (prerequisites == null || state == StorageState.Loading) return@collect
                    val force = prerequisites.revision != lastRevision
                    lastRevision = prerequisites.revision
                    when (state) {
                        is StorageState.Ready -> registrar.synchronize(state.snapshot.registrationIntents(), prerequisites.ready, force)
                        StorageState.Unavailable -> {
                            registrar.synchronize(emptyList(), false)
                            CaptureHealth.registration(RegistrationStatus.FAILED)
                        }
                        StorageState.Loading -> Unit
                    }
                }
        }
    }

    /** Call after each foreground return so platform-cleared fences are restored. */
    fun updatePermissionReady(value: Boolean) {
        readiness.value = Prerequisites(value, (readiness.value?.revision ?: 0L) + 1L)
    }

    companion object {
        @Volatile private var instance: CaptureController? = null
        fun get(context: Context): CaptureController = instance ?: synchronized(this) {
            instance ?: CaptureController(context).also { instance = it }
        }
    }
}
