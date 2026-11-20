package limited

import (
	"context"
	"net"
	"time"
)

// BindInterface names the interface every socket the core opens is bound to. The command sets it from -iface
// before Start on macOS, where our tunnel's routes take everything and an unbound socket would loop into it.
// Empty — Android, where the process runs outside the tunnel — binds nothing. It is honoured on darwin only
// (outbound_darwin.go); elsewhere it is a no-op.
var BindInterface string

// BoundDialer returns a dialer whose sockets are bound to BindInterface (a plain dialer when it is unset or the
// platform cannot bind).
func BoundDialer(timeout time.Duration) *net.Dialer {
	return &net.Dialer{Timeout: timeout, Control: outboundControl}
}

// BoundDialContext opens a connection bound like the rest of the core's; the direct-first dialer and the
// credential provider's HTTP client use it (creds.DialContext).
func BoundDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return BoundDialer(0).DialContext(ctx, network, address)
}
