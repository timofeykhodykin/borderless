package main

import (
	"context"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

// Fallback resolvers, used only when the app passed none and the system has no resolv.conf.
var fallbackDNS = []string{"1.1.1.1", "8.8.8.8"}

// dnsServers turns "-dns" (comma-separated addresses, the network's own resolvers from the app) into host:port
// entries. Empty → the system's resolv.conf if there is one, else the fallbacks: a pure Go binary on Android finds
// no resolv.conf and would ask 127.0.0.1:53, where nothing answers, so no host name would ever resolve.
func dnsServers(flagValue string, haveResolvConf bool) []string {
	var out []string
	for _, s := range strings.Split(flagValue, ",") {
		s = strings.TrimSpace(strings.Trim(strings.TrimSpace(s), "[]"))
		if net.ParseIP(s) == nil {
			continue
		}
		out = append(out, net.JoinHostPort(s, "53"))
	}
	if len(out) > 0 || haveResolvConf {
		return out
	}
	for _, s := range fallbackDNS {
		out = append(out, net.JoinHostPort(s, "53"))
	}
	return out
}

func hasResolvConf() bool {
	_, err := os.Stat("/etc/resolv.conf")
	return err == nil
}

// useDNS points Go's own resolver (used by every dial in the process) at servers, rotating among them on each
// attempt so a dead one costs one attempt, not the lookup.
func useDNS(servers []string) {
	if len(servers) == 0 {
		return
	}
	var next atomic.Uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			s := servers[int(next.Add(1)-1)%len(servers)]
			d := net.Dialer{Timeout: 3 * time.Second}
			return d.DialContext(ctx, network, s)
		},
	}
}
