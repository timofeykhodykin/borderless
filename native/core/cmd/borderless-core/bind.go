package main

import (
	"context"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Darwin's socket options that tie a socket to one interface (netinet/in.h, netinet6/in6.h).
const (
	ipBoundIf   = 25
	ipv6BoundIf = 125
)

// binding is where the service's own connections leave: the physical interface (our tunnel is the primary service, so
// an unbound socket would loop into it) and that network's resolver (the system's points into the tunnel too).
type binding struct {
	mu    sync.RWMutex
	index int
	dns   string
}

func (b *binding) set(iface, dns string) error {
	index := 0
	if iface != "" {
		i, err := net.InterfaceByName(iface)
		if err != nil {
			return err
		}
		index = i.Index
	}
	b.mu.Lock()
	b.index, b.dns = index, dns
	b.mu.Unlock()
	return nil
}

func (b *binding) get() (int, string) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.index, b.dns
}

// control binds a new socket to the interface — except one to this machine (a local proxy), which only loopback reaches.
func (b *binding) control(network, address string, c syscall.RawConn) error {
	index, _ := b.get()
	if index == 0 || isLoopback(address) {
		return nil
	}
	var err error
	if cerr := c.Control(func(fd uintptr) {
		if strings.HasSuffix(network, "6") {
			err = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IPV6, ipv6BoundIf, index)
		} else {
			err = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, ipBoundIf, index)
		}
	}); cerr != nil {
		return cerr
	}
	return err
}

func isLoopback(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	ip := net.ParseIP(host)
	return host == "localhost" || (ip != nil && ip.IsLoopback())
}

// dialer connects through the interface, names resolved at the network's own resolver (bound as well).
func (b *binding) dialer(timeout time.Duration) *net.Dialer {
	return &net.Dialer{
		Timeout: timeout,
		Control: b.control,
		Resolver: &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				_, dns := b.get()
				if dns != "" {
					address = net.JoinHostPort(dns, "53")
				}
				d := net.Dialer{Timeout: 5 * time.Second, Control: b.control}
				return d.DialContext(ctx, network, address)
			},
		},
	}
}
