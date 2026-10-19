package limited

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/turn/v5"
)

// turnTransport is how the client reaches a TURN server. The calls service may advertise UDP, TCP or TLS
// endpoints; some networks drop UDP, so the core tries the stream transports as a fallback.
type turnTransport string

const (
	turnUDP turnTransport = "udp"
	turnTCP turnTransport = "tcp"
	turnTLS turnTransport = "tls"
)

type turnEndpoint struct {
	Host      string
	Port      string
	Transport turnTransport
}

func (e turnEndpoint) address() string { return net.JoinHostPort(e.Host, e.Port) }

func (e turnEndpoint) label() string { return strings.ToUpper(string(e.Transport)) + " " + e.address() }

func (e turnEndpoint) key() string {
	return string(e.Transport) + "|" + strings.ToLower(e.Host) + "|" + e.Port
}

// parseTurnEndpoint accepts the service's "turn:host:port?transport=udp|tcp", "turns:host:port" and a plain
// "host:port". A missing port gets TURN's default (3478, or 5349 for TLS).
func parseTurnEndpoint(raw string) (turnEndpoint, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return turnEndpoint{}, fmt.Errorf("empty TURN URL")
	}
	scheme, rest := "turn", raw
	lower := strings.ToLower(raw)
	switch {
	case strings.HasPrefix(lower, "turns:"):
		scheme, rest = "turns", raw[len("turns:"):]
	case strings.HasPrefix(lower, "turn:"):
		rest = raw[len("turn:"):]
	}
	query := ""
	if i := strings.IndexByte(rest, '?'); i >= 0 {
		rest, query = rest[:i], rest[i+1:]
	}
	rest = strings.TrimPrefix(rest, "//")
	transport := turnUDP
	if scheme == "turns" {
		transport = turnTLS
	}
	for _, kv := range strings.Split(query, "&") {
		if scheme != "turns" && strings.EqualFold(strings.TrimSpace(kv), "transport=tcp") {
			transport = turnTCP
		}
	}
	host, port, err := splitTurnHostPort(rest, transport)
	if err != nil {
		return turnEndpoint{}, err
	}
	return turnEndpoint{Host: host, Port: port, Transport: transport}, nil
}

func splitTurnHostPort(address string, transport turnTransport) (string, string, error) {
	if host, port, err := net.SplitHostPort(address); err == nil {
		return strings.Trim(host, "[]"), port, nil
	}
	defaultPort := "3478"
	if transport == turnTLS {
		defaultPort = "5349"
	}
	trimmed := strings.Trim(address, "[]")
	if ip := net.ParseIP(trimmed); ip != nil {
		return trimmed, defaultPort, nil
	}
	if strings.Count(address, ":") > 1 {
		return "", "", fmt.Errorf("cannot parse TURN address %q", address)
	}
	if strings.TrimSpace(address) == "" {
		return "", "", fmt.Errorf("empty TURN address")
	}
	return strings.TrimSpace(address), defaultPort, nil
}

// turnCandidates lists the endpoints to try for one stream: UDP first (the historical path), then TCP and TLS.
// A UDP endpoint also gets a TCP candidate on the same address: some TURN listeners accept both, and a network
// that drops UDP may still let the TCP control connection through.
func turnCandidates(urls []string, sessionID int) []turnEndpoint {
	var udp, tcp, tlsList []turnEndpoint
	seen := map[string]bool{}
	add := func(dst *[]turnEndpoint, ep turnEndpoint) {
		if ep.Host == "" || ep.Port == "" {
			return
		}
		k := ep.key()
		if seen[k] {
			return
		}
		seen[k] = true
		*dst = append(*dst, ep)
	}
	for _, raw := range urls {
		ep, err := parseTurnEndpoint(raw)
		if err != nil {
			continue
		}
		switch ep.Transport {
		case turnTLS:
			add(&tlsList, ep)
			add(&tcp, turnEndpoint{Host: ep.Host, Port: ep.Port, Transport: turnTCP})
		case turnTCP:
			add(&tcp, ep)
		default:
			add(&udp, ep)
			add(&tcp, turnEndpoint{Host: ep.Host, Port: ep.Port, Transport: turnTCP})
		}
	}
	if len(udp) > 1 && sessionID > 0 {
		offset := sessionID % len(udp)
		udp = append(append([]turnEndpoint{}, udp[offset:]...), udp[:offset]...)
	}
	out := make([]turnEndpoint, 0, len(udp)+len(tcp)+len(tlsList))
	out = append(out, udp...)
	out = append(out, tlsList...)
	out = append(out, tcp...)
	return out
}

// turnURLs validates and deduplicates the provider's TURN URLs without losing their transport or query.
func turnURLs(urls []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, raw := range urls {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		if _, err := parseTurnEndpoint(raw); err != nil {
			continue
		}
		k := strings.ToLower(raw)
		if seen[k] {
			continue
		}
		seen[k] = true
		out = append(out, raw)
	}
	return out
}

// openTURNAllocation dials one endpoint and returns a TURN client with an allocation. The caller closes all three.
func openTURNAllocation(ctx context.Context, m *Core, creds *TurnCreds, ep turnEndpoint) (*turn.Client, net.PacketConn, net.PacketConn, error) {
	addr := ep.address()
	var turnConn net.PacketConn
	switch ep.Transport {
	case turnTCP:
		dialCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
		defer cancel()
		d := &net.Dialer{Timeout: 6 * time.Second, KeepAlive: 30 * time.Second}
		raw, err := d.DialContext(dialCtx, "tcp", addr)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("TURN TCP connect %s: %w", addr, err)
		}
		turnConn = turn.NewSTUNConn(raw)
	case turnTLS:
		dialCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
		d := &tls.Dialer{
			NetDialer: &net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second},
			Config:    &tls.Config{ServerName: ep.Host, MinVersion: tls.VersionTLS12},
		}
		raw, err := d.DialContext(dialCtx, "tcp", addr)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("TURN TLS connect %s: %w", addr, err)
		}
		turnConn = turn.NewSTUNConn(raw)
	default:
		dialCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
		defer cancel()
		d := &net.Dialer{Timeout: 6 * time.Second}
		raw, err := d.DialContext(dialCtx, "udp", addr)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("TURN UDP connect %s: %w", addr, err)
		}
		c := raw.(*net.UDPConn)
		_ = c.SetReadBuffer(socketBufSize)
		_ = c.SetWriteBuffer(socketBufSize)
		turnConn = &connectedUDPConn{c}
	}

	var family turn.RequestedAddressFamily
	if m.peer.IP.To4() != nil {
		family = turn.RequestedAddressFamilyIPv4
	} else {
		family = turn.RequestedAddressFamilyIPv6
	}
	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr:         addr,
		TURNServerAddr:         addr,
		Conn:                   turnConn,
		Net:                    new(stdnet.Net),
		Username:               creds.Username,
		Password:               creds.Password,
		RequestedAddressFamily: family,
		LoggerFactory:          nullLoggerFactory{},
	})
	if err != nil {
		_ = turnConn.Close()
		return nil, nil, nil, fmt.Errorf("TURN %s client: %w", ep.Transport, err)
	}
	if err = tc.Listen(); err != nil {
		tc.Close()
		_ = turnConn.Close()
		return nil, nil, nil, fmt.Errorf("TURN %s listen: %w", ep.Transport, err)
	}
	relay, err := tc.Allocate()
	if err != nil {
		tc.Close()
		_ = turnConn.Close()
		if isAuthError(err) {
			return nil, nil, nil, &CredError{Kind: Transient, Code: "turn_auth", Message: "TURN authentication failed"}
		}
		if strings.Contains(err.Error(), "Quota") || strings.Contains(err.Error(), "486") {
			return nil, nil, nil, fmt.Errorf("TURN quota: %w", err)
		}
		return nil, nil, nil, fmt.Errorf("TURN %s allocate %s: %w", ep.Transport, addr, err)
	}
	return tc, relay, turnConn, nil
}

// policyMax parses "POLICY:max_workers=N".
func policyMax(resp string) (int, bool) {
	const prefix = "POLICY:max_workers="
	if !strings.HasPrefix(resp, prefix) {
		return 0, false
	}
	n, err := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(resp, prefix)))
	if err != nil || n < 1 || n > 128 {
		return 0, false
	}
	return n, true
}
