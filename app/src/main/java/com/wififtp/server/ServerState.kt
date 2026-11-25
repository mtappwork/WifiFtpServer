package com.wififtp.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int) : ServerState
    data class Error(val message: String) : ServerState
}

/** Process-wide holder so the Activity can observe what the Service is doing. */
object FtpServerState {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun set(newState: ServerState) {
        _state.value = newState
    }
}
