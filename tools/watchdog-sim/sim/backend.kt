package com.wgtunnel.backend
interface Tunnel {
    val id: Int
    val name: String
    sealed interface State {
        sealed class Up : State { data object Healthy : Up(); data object HandshakeFailure : Up() }
        data object Down : State
        data object Starting : State
        data object Stopping : State
    }
}
