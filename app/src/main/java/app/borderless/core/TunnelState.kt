package app.borderless.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
    OFF,

    /** The tunnel is coming up or looking for a server; traffic may be held. */
    SEARCHING,

    /** Traffic goes through [TunnelStatus.serverId]. */
    CONNECTED,

    /** No server works; traffic goes around the proxy while the engine keeps retrying. */
    DIRECT,

    /** No server works and the user chose to pause traffic (nothing leaves the phone) instead of going direct. */
    PAUSED,

    /** The phone has no network at all. */
    NO_NETWORK,
}

data class TunnelStatus(
    val phase: Phase = Phase.OFF,
    val serverId: String? = null,
    /** Latest latency of the current server measured through the running core. */
    val ping: Int? = null,
    val nextRetryAt: Long = 0,
    val error: String? = null,
    /** There is no server to use at all: traffic goes direct until one is added (no retries meanwhile). */
    val noServers: Boolean = false,
) {
    val active: Boolean get() = phase != Phase.OFF
}

object TunnelState {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    fun set(s: TunnelStatus) {
        _status.value = s
    }

    fun update(f: (TunnelStatus) -> TunnelStatus) = _status.update(f)
}
