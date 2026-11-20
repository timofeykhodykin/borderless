//go:build darwin

package limited

import (
	"fmt"
	"net"
	"strings"
	"sync"
	"syscall"
)

// Darwin's socket options that tie a socket to one interface (netinet/in.h, netinet6/in6.h); the same numbers
// native/core/cmd/borderless-core/bind.go uses.
const (
	ipBoundIf   = 25
	ipv6BoundIf = 125
)

var (
	boundMu    sync.Mutex
	boundName  string
	boundIndex int
)

// boundInterfaceIndex resolves the interface name once (the command sets BindInterface before anything dials).
// An interface that can't be resolved fails the dial: an unbound one would loop into our own tunnel.
func boundInterfaceIndex(name string) (int, error) {
	boundMu.Lock()
	defer boundMu.Unlock()
	if name == boundName {
		return boundIndex, nil
	}
	i, err := net.InterfaceByName(name)
	if err != nil {
		return 0, fmt.Errorf("interface %s: %w", name, err)
	}
	boundName, boundIndex = name, i.Index
	return boundIndex, nil
}

// outboundControl binds a new socket to BindInterface — except one to this machine (a local hop such as the
// WireGuard endpoint on 127.0.0.1), which only loopback reaches. The logic of native/core/…/bind.go.
func outboundControl(network, address string, c syscall.RawConn) error {
	name := BindInterface
	if name == "" || isLoopback(address) {
		return nil
	}
	index, err := boundInterfaceIndex(name)
	if err != nil {
		return err
	}
	var sockErr error
	if cerr := c.Control(func(fd uintptr) {
		if strings.HasSuffix(network, "6") {
			sockErr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IPV6, ipv6BoundIf, index)
		} else {
			sockErr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, ipBoundIf, index)
		}
	}); cerr != nil {
		return cerr
	}
	return sockErr
}

func isLoopback(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	ip := net.ParseIP(host)
	return host == "localhost" || (ip != nil && ip.IsLoopback())
}
