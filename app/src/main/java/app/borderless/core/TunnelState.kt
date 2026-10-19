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

    /**
     * The network reaches only the chosen domain zones' own sites ([NetCheck]), no server answers: traffic goes
     * around the proxy (or is paused, [TunnelStatus.paused]), servers are checked less often.
     */
    LIMITED,
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
    /** Without a server, traffic is paused rather than going direct (shown with [Phase.LIMITED]). */
    val paused: Boolean = false,
    /** CONNECTED under the Manual strategy to a server that stopped answering (the engine stays on it). */
    val unanswered: Boolean = false,
    /**
     * The server picked by hand (Manual) that the engine keeps trying. Shown as chosen while it isn't in use
     * either — e.g. a limited-network server whose core can't start leaves traffic direct, but the choice stays
     * visible (and retried) instead of silently disappearing.
     */
    val selectedId: String? = null,
) {
    val active: Boolean get() = phase != Phase.OFF

    /** What the user sees as the chosen server: the one traffic goes through, else the Manual pick. */
    val chosenId: String? get() = serverId ?: selectedId
}

object TunnelState {
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    fun set(s: TunnelStatus) {
        _status.value = s
    }

    fun update(f: (TunnelStatus) -> TunnelStatus) = _status.update(f)
}
