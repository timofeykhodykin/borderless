// Package directfirst is a local SOCKS5 server that tries every connection directly first and carries it through
// the tunnel's server only when the direct way doesn't work.
//
// The app runs it on a limited network: Xray sends the connections the zones' lists would let go without the tunnel
// to it (a socks outbound with random credentials), and it dials them from the app's own process, which is outside
// the tunnel. A connection counts as working directly when it connects and — for TLS, where a filtered network often
// lets the connection open but never answers the handshake — when the server answers the client's first bytes in
// time. Otherwise the same bytes are replayed through Back: a SOCKS5 inbound of the running core (random
// credentials too) that the core routes to the server. A host that needed the server goes there straight away for
// [Config.Remember], then is tried directly again.
package directfirst

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Config of a [Server]. Zero durations take the defaults below.
type Config struct {
	Listen   string // address to listen on, e.g. "127.0.0.1:0"
	User     string // credentials clients must present
	Pass     string
	Back     string // SOCKS5 address of the way through the server
	BackUser string
	BackPass string

	DialTimeout   time.Duration // a direct connection must be open by then
	ClientWait    time.Duration // how long to wait for the client's first bytes (TLS sends them at once)
	AnswerTimeout time.Duration // how long the direct server has to answer a TLS client's first bytes
	Remember      time.Duration // how long a host that needed the server goes there straight away

	// Dial opens direct connections (tests replace it).
	Dial func(ctx context.Context, network, addr string) (net.Conn, error)
	// Log gets one line per decision worth knowing (a host moved to the server, a failure).
	Log func(string)
}

const (
	defaultDialTimeout   = 2500 * time.Millisecond
	defaultClientWait    = 250 * time.Millisecond
	defaultAnswerTimeout = 3 * time.Second
	defaultRemember      = 10 * time.Minute
	handshakeTimeout     = 10 * time.Second
	firstChunk           = 16 * 1024
)

// Stats counts connections since the start: carried directly, through the server (Fallbacks of them after a direct
// try that didn't work), and those that worked neither way.
type Stats struct {
	Direct    int64 `json:"direct"`
	Server    int64 `json:"server"`
	Fallbacks int64 `json:"fallbacks"`
	Failed    int64 `json:"failed"`
}

// Server is a running listener; see the package doc.
type Server struct {
	cfg  Config
	ln   net.Listener
	mu   sync.Mutex
	memo map[string]time.Time // host → until when it goes through the server

	direct, server, fallbacks, failed atomic.Int64
}

// Listen starts listening; [Server.Serve] then accepts connections.
func Listen(cfg Config) (*Server, error) {
	if cfg.User == "" || cfg.Pass == "" || cfg.Back == "" {
		return nil, errors.New("directfirst: credentials and the back address are required")
	}
	if cfg.DialTimeout <= 0 {
		cfg.DialTimeout = defaultDialTimeout
	}
	if cfg.ClientWait <= 0 {
		cfg.ClientWait = defaultClientWait
	}
	if cfg.AnswerTimeout <= 0 {
		cfg.AnswerTimeout = defaultAnswerTimeout
	}
	if cfg.Remember <= 0 {
		cfg.Remember = defaultRemember
	}
	if cfg.Dial == nil {
		d := &net.Dialer{}
		cfg.Dial = d.DialContext
	}
	if cfg.Log == nil {
		cfg.Log = func(string) {}
	}
	if cfg.Listen == "" {
		cfg.Listen = "127.0.0.1:0"
	}
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, ln: ln, memo: map[string]time.Time{}}, nil
}

// Addr is the address actually listened on.
func (s *Server) Addr() net.Addr { return s.ln.Addr() }

// Close stops accepting; connections in progress run to their end.
func (s *Server) Close() error { return s.ln.Close() }

// Stats so far.
func (s *Server) Stats() Stats {
	return Stats{Direct: s.direct.Load(), Server: s.server.Load(), Fallbacks: s.fallbacks.Load(), Failed: s.failed.Load()}
}

// Serve accepts connections until [Server.Close].
func (s *Server) Serve() error {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return err
		}
		go s.handle(c)
	}
}

func (s *Server) handle(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(handshakeTimeout))
	target, err := s.handshake(c)
	if err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	host, _, _ := net.SplitHostPort(target)

	// The client's first bytes (a TLS ClientHello comes at once; a protocol where the server speaks first sends none).
	first := make([]byte, firstChunk)
	_ = c.SetReadDeadline(time.Now().Add(s.cfg.ClientWait))
	n, rerr := c.Read(first)
	_ = c.SetReadDeadline(time.Time{})
	first = first[:n]
	if rerr != nil && !isTimeout(rerr) && n == 0 {
		return
	}

	if !s.viaServer(host) {
		d, why := s.tryDirect(target, first)
		if d != nil {
			s.direct.Add(1)
			splice(c, d)
			return
		}
		s.fallbacks.Add(1)
		if s.rememberServer(host) {
			s.cfg.Log(fmt.Sprintf("%s: %s directly, through the server for %s", target, why, s.cfg.Remember))
		}
	}
	// Through the server by name when the first bytes carry one (TLS server name, HTTP Host): the address came from
	// a local resolver and may not be the right one — or reachable at all — from the server.
	back := target
	if name := Sniff(first); name != "" {
		_, port, _ := net.SplitHostPort(target)
		back = net.JoinHostPort(name, port)
	}
	b, err := s.dialBack(back)
	if err != nil {
		s.failed.Add(1)
		s.cfg.Log(fmt.Sprintf("%s: through the server: %v", target, err))
		return
	}
	defer b.Close()
	if len(first) > 0 {
		if _, err := b.Write(first); err != nil {
			s.failed.Add(1)
			return
		}
	}
	s.server.Add(1)
	splice(c, b)
}

// tryDirect opens target directly; with TLS first bytes the server must answer them in time. It returns the open
// connection (the answer already passed on to nobody: it is returned in front of the stream) or why it didn't work.
func (s *Server) tryDirect(target string, first []byte) (net.Conn, string) {
	ctx, cancel := context.WithTimeout(context.Background(), s.cfg.DialTimeout)
	defer cancel()
	d, err := s.cfg.Dial(ctx, "tcp", target)
	if err != nil {
		if isTimeout(err) || errors.Is(err, context.DeadlineExceeded) {
			return nil, "no connection"
		}
		return nil, "refused"
	}
	if len(first) == 0 {
		return d, ""
	}
	if _, err := d.Write(first); err != nil {
		d.Close()
		return nil, "cut off"
	}
	if !isTLS(first) {
		// Plain protocols may answer late on purpose (long polling): an open connection is enough.
		return d, ""
	}
	_ = d.SetReadDeadline(time.Now().Add(s.cfg.AnswerTimeout))
	buf := make([]byte, firstChunk)
	n, err := d.Read(buf)
	_ = d.SetReadDeadline(time.Time{})
	if n == 0 {
		d.Close()
		if err != nil && isTimeout(err) {
			return nil, "no answer"
		}
		return nil, "cut off"
	}
	return &prefixed{Conn: d, head: buf[:n]}, ""
}

func (s *Server) viaServer(host string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	until, ok := s.memo[host]
	if !ok {
		return false
	}
	if time.Now().After(until) {
		delete(s.memo, host)
		return false
	}
	return true
}

// rememberServer sends host through the server for a while; true if it wasn't already.
func (s *Server) rememberServer(host string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	until, ok := s.memo[host]
	s.memo[host] = time.Now().Add(s.cfg.Remember)
	return !ok || time.Now().After(until)
}

// handshake runs the SOCKS5 greeting, the username/password check (RFC 1929) and reads a CONNECT request; it
// answers success before any data (the target is opened afterwards, see handle).
func (s *Server) handshake(c net.Conn) (string, error) {
	var head [2]byte
	if _, err := io.ReadFull(c, head[:]); err != nil || head[0] != 5 {
		return "", errors.New("not socks5")
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", err
	}
	hasPass := false
	for _, m := range methods {
		if m == 2 {
			hasPass = true
		}
	}
	if !hasPass {
		_, _ = c.Write([]byte{5, 0xff})
		return "", errors.New("no password method")
	}
	if _, err := c.Write([]byte{5, 2}); err != nil {
		return "", err
	}
	user, pass, err := readAuth(c)
	if err != nil {
		return "", err
	}
	ok := subtle.ConstantTimeCompare([]byte(user), []byte(s.cfg.User)) == 1
	ok = subtle.ConstantTimeCompare([]byte(pass), []byte(s.cfg.Pass)) == 1 && ok
	if !ok {
		_, _ = c.Write([]byte{1, 1})
		return "", errors.New("wrong credentials")
	}
	if _, err := c.Write([]byte{1, 0}); err != nil {
		return "", err
	}
	var req [3]byte
	if _, err := io.ReadFull(c, req[:]); err != nil || req[0] != 5 {
		return "", errors.New("bad request")
	}
	target, err := readAddr(c)
	if err != nil {
		return "", err
	}
	if req[1] != 1 {
		_, _ = c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", errors.New("only CONNECT")
	}
	_, err = c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	return target, err
}

func readAuth(c net.Conn) (string, string, error) {
	var v [2]byte
	if _, err := io.ReadFull(c, v[:]); err != nil || v[0] != 1 {
		return "", "", errors.New("bad auth")
	}
	user := make([]byte, v[1])
	if _, err := io.ReadFull(c, user); err != nil {
		return "", "", err
	}
	var pl [1]byte
	if _, err := io.ReadFull(c, pl[:]); err != nil {
		return "", "", err
	}
	pass := make([]byte, pl[0])
	if _, err := io.ReadFull(c, pass); err != nil {
		return "", "", err
	}
	return string(user), string(pass), nil
}

// readAddr reads ATYP, the address and the port of a SOCKS5 request or reply.
func readAddr(r io.Reader) (string, error) {
	var atyp [1]byte
	if _, err := io.ReadFull(r, atyp[:]); err != nil {
		return "", err
	}
	var host string
	switch atyp[0] {
	case 1, 4:
		ip := make([]byte, map[byte]int{1: 4, 4: 16}[atyp[0]])
		if _, err := io.ReadFull(r, ip); err != nil {
			return "", err
		}
		host = net.IP(ip).String()
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return "", err
		}
		name := make([]byte, l[0])
		if _, err := io.ReadFull(r, name); err != nil {
			return "", err
		}
		host = string(name)
	default:
		return "", errors.New("bad address type")
	}
	var p [2]byte
	if _, err := io.ReadFull(r, p[:]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(p[:])))), nil
}

// dialBack opens target through the core's SOCKS5 inbound (and so through the server).
func (s *Server) dialBack(target string) (net.Conn, error) {
	b, err := net.DialTimeout("tcp", s.cfg.Back, s.cfg.DialTimeout)
	if err != nil {
		return nil, err
	}
	_ = b.SetDeadline(time.Now().Add(handshakeTimeout))
	fail := func(err error) (net.Conn, error) { b.Close(); return nil, err }
	if _, err := b.Write([]byte{5, 1, 2}); err != nil {
		return fail(err)
	}
	var m [2]byte
	if _, err := io.ReadFull(b, m[:]); err != nil || m[1] != 2 {
		return fail(errors.New("back: no password method"))
	}
	auth := []byte{1, byte(len(s.cfg.BackUser))}
	auth = append(auth, s.cfg.BackUser...)
	auth = append(append(auth, byte(len(s.cfg.BackPass))), s.cfg.BackPass...)
	if _, err := b.Write(auth); err != nil {
		return fail(err)
	}
	if _, err := io.ReadFull(b, m[:]); err != nil || m[1] != 0 {
		return fail(errors.New("back: credentials refused"))
	}
	req, err := connectRequest(target)
	if err != nil {
		return fail(err)
	}
	if _, err := b.Write(req); err != nil {
		return fail(err)
	}
	var rep [3]byte
	if _, err := io.ReadFull(b, rep[:]); err != nil {
		return fail(err)
	}
	if rep[1] != 0 {
		return fail(fmt.Errorf("back: refused (%d)", rep[1]))
	}
	if _, err := readAddr(b); err != nil {
		return fail(err)
	}
	_ = b.SetDeadline(time.Time{})
	return b, nil
}

func connectRequest(target string) ([]byte, error) {
	host, port, err := net.SplitHostPort(target)
	if err != nil {
		return nil, err
	}
	p, err := strconv.Atoi(port)
	if err != nil {
		return nil, err
	}
	req := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			req = append(append(req, 1), v4...)
		} else {
			req = append(append(req, 4), ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return nil, errors.New("host name too long")
		}
		req = append(append(req, 3, byte(len(host))), host...)
	}
	return binary.BigEndian.AppendUint16(req, uint16(p)), nil
}

// Sniff returns the host name the client's first bytes ask for: the server name of a TLS ClientHello or the Host
// header of an HTTP request; "" when there is none (or it doesn't look like a host name).
func Sniff(b []byte) string {
	name := ""
	if isTLS(b) {
		name = sniName(b)
	} else {
		name = httpHost(b)
	}
	if name == "" || len(name) > 253 || net.ParseIP(name) != nil {
		return ""
	}
	for _, c := range name {
		if !(c == '.' || c == '-' || c == '_' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
			return ""
		}
	}
	return name
}

// sniName reads the server_name extension of a ClientHello in the first TLS record.
func sniName(b []byte) string {
	// Record header (5), handshake type 1 (ClientHello) and length (3), version (2), random (32).
	p := 5
	if len(b) < p+4 || b[p] != 1 {
		return ""
	}
	p += 4 + 2 + 32
	skip := func(lenBytes int) bool {
		if p+lenBytes > len(b) {
			return false
		}
		n := 0
		for i := 0; i < lenBytes; i++ {
			n = n<<8 | int(b[p+i])
		}
		p += lenBytes + n
		return p <= len(b)
	}
	// Session id, cipher suites, compression methods.
	if !skip(1) || !skip(2) || !skip(1) || p+2 > len(b) {
		return ""
	}
	end := p + 2 + int(binary.BigEndian.Uint16(b[p:]))
	p += 2
	if end > len(b) {
		end = len(b)
	}
	for p+4 <= end {
		typ := binary.BigEndian.Uint16(b[p:])
		n := int(binary.BigEndian.Uint16(b[p+2:]))
		p += 4
		if p+n > end {
			return ""
		}
		if typ == 0 && n >= 5 && b[p+2] == 0 {
			l := int(binary.BigEndian.Uint16(b[p+3:]))
			if p+5+l <= end {
				return string(b[p+5 : p+5+l])
			}
			return ""
		}
		p += n
	}
	return ""
}

// httpHost reads the Host header of an HTTP/1 request (without the port).
func httpHost(b []byte) string {
	s := string(b)
	head, _, found := strings.Cut(s, "\r\n\r\n")
	if !found && len(s) < firstChunk {
		head = s
	}
	lines := strings.Split(head, "\r\n")
	if len(lines) < 2 || !strings.Contains(lines[0], " HTTP/1.") {
		return ""
	}
	for _, l := range lines[1:] {
		k, v, ok := strings.Cut(l, ":")
		if ok && strings.EqualFold(strings.TrimSpace(k), "host") {
			v = strings.TrimSpace(v)
			if h, _, err := net.SplitHostPort(v); err == nil {
				return h
			}
			return v
		}
	}
	return ""
}

// isTLS: a TLS record carrying a handshake (a ClientHello).
func isTLS(b []byte) bool { return len(b) >= 3 && b[0] == 0x16 && b[1] == 3 }

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// prefixed returns head first, then the connection.
type prefixed struct {
	net.Conn
	head []byte
}

func (p *prefixed) Read(b []byte) (int, error) {
	if len(p.head) > 0 {
		n := copy(b, p.head)
		p.head = p.head[n:]
		return n, nil
	}
	return p.Conn.Read(b)
}

func (p *prefixed) CloseWrite() error {
	if cw, ok := p.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

// splice copies both ways; one side finishing half-closes the other, both done closes both.
func splice(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	pipe := func(dst, src net.Conn) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
	}
	go pipe(a, b)
	go pipe(b, a)
	wg.Wait()
	_ = a.Close()
	_ = b.Close()
}
