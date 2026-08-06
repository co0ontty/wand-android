package com.wand.app

import com.wand.app.data.SessionSnapshot
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns a submitted create independently of any Activity/Compose lifecycle. A replacement Home can
 * observe and claim the result, so configuration changes and "Don't keep activities" cannot turn a
 * successful server-side create into an orphaned session.
 */
object SessionCreationCoordinator {
    sealed interface State {
        data object Idle : State

        data class Running(
            val requestId: String,
            val hostServerId: String,
            val targetServerId: String,
        ) : State

        data class Completed(
            val requestId: String,
            val hostServerId: String,
            val targetServerId: String,
            val outcome: Outcome,
        ) : State
    }

    sealed interface Outcome {
        data class Success(val snapshot: SessionSnapshot) : Outcome
        data class Failure(val message: String) : Outcome
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun start(
        hostServerId: String,
        targetServerId: String,
        create: suspend () -> SessionSnapshot,
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        synchronized(lock) {
            if (mutableState.value !is State.Idle) return false
            mutableState.value = State.Running(requestId, hostServerId, targetServerId)
        }
        scope.launch {
            val outcome = try {
                Outcome.Success(create())
            } catch (error: Exception) {
                Outcome.Failure(error.message ?: "创建失败")
            }
            synchronized(lock) {
                val current = mutableState.value
                if (current is State.Running && current.requestId == requestId) {
                    mutableState.value = State.Completed(
                        requestId = requestId,
                        hostServerId = hostServerId,
                        targetServerId = targetServerId,
                        outcome = outcome,
                    )
                }
            }
        }
        return true
    }

    /** Exactly one live Home instance may consume a completion. */
    fun takeCompleted(requestId: String): State.Completed? = synchronized(lock) {
        val current = mutableState.value as? State.Completed ?: return@synchronized null
        if (current.requestId != requestId) return@synchronized null
        mutableState.value = State.Idle
        current
    }

    @JvmStatic
    fun isBusy(): Boolean = synchronized(lock) { mutableState.value !is State.Idle }

    @JvmStatic
    fun busyHostServerId(): String? = synchronized(lock) {
        when (val current = mutableState.value) {
            is State.Running -> current.hostServerId
            is State.Completed -> current.hostServerId
            State.Idle -> null
        }
    }
}
