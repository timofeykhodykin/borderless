// Package limited is Border(less)'s native limited-network core: it carries the tunnel to a server through the
// calls service, and the server hands back a WireGuard config that the app's WireGuard outbound then uses. It is
// built from cmd/limitedd into lib/<abi>/liblimited.so and started by the app as a child process (see core/Limited.kt
// and cmd/limitedd for why). The service-facing piece — obtaining TURN credentials — sits behind CredentialProvider
// (provider.go) and is implemented by the creds package; everything here is transport on permissive libraries.
package limited

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/logging"
)

// Config is how the app starts a core. Simple field types so it crosses the gomobile boundary.
type Config struct {
	Peer          string // server "host:port"
	CallIDs       string // comma-separated call identifiers from the link
	Password      string // server connection password (server-side auth; not a TURN credential)
	DeviceID      string // server-side device id
	Instance      string // stable per-server id passed to the provider (not the hardware id)
	ListenAddr    string // e.g. "127.0.0.1:0"
	MediaMode     string // "audio" (default) or "video"
	MaxStreams    int
	ActiveStreams int
	KeepaliveSec  int
	BindingSec    int
	StatsSec      int
	Verbose       bool
}

// Events is implemented by the app (Kotlin) and receives everything the core reports.
type Events interface {
	OnListen(port int)        // the local UDP port actually bound
	OnConfig(wgConfig string) // wg-quick config (has a private key); the app stores it sealed and never logs it
	OnReady()                 // first stream carrying traffic
	OnStats(active, wanted int, bytesUp, bytesDown int64)
	OnPolicy(maxWorkers int) // the server's stream limit (POLICY:max_workers)
	OnError(code, message string, fatal bool)
	OnLog(line string) // one detailed-log line, English and neutral
}

// Session is the handle the app keeps to control a running core.
type Session interface {
	SetStreams(n int)
	SetKeepalive(keepaliveSec, bindingSec int)
	Stop()
}

type stats struct {
	up     atomic.Int64
	down   atomic.Int64
	active atomic.Int32
}

type credEntry struct {
	mu     sync.Mutex
	creds  *TurnCreds
	expiry time.Time
}

// Core is one running limited-network transport. It implements Session.
type Core struct {
	ev        Events
	wrapKey   []byte
	peer      *net.UDPAddr
	callIDs   []string
	localPort string
	deviceID  string
	password  string
	mediaMode string
	instance  string
	verbose   bool

	localConn net.PacketConn
	disp      *dispatcher
	gate      *gate
	st        *stats

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	configCh   chan string
	configOnce sync.Once
	readyOnce  sync.Once
	fatalOnce  sync.Once

	credMu    sync.Mutex
	credCache map[string]*credEntry
	deadIDs   sync.Map
}

// Start launches a core and returns its Session. Config errors and a missing provider are reported through ev and
// a Session whose methods are no-ops is returned.
func Start(cfg *Config, ev Events) *Core {
	c := &Core{ev: ev, verbose: cfg.Verbose, credCache: map[string]*credEntry{}}
	if provider == nil {
		ev.OnError("no_provider", "no credential provider built in", true)
		return c
	}
	key, err := deriveWrapKey(cfg.Password)
	if err != nil {
		ev.OnError("bad_password", err.Error(), true)
		return c
	}
	peer, err := net.ResolveUDPAddr("udp", cfg.Peer)
	if err != nil {
		ev.OnError("bad_peer", err.Error(), true)
		return c
	}
	for _, id := range strings.Split(cfg.CallIDs, ",") {
		if id = strings.TrimSpace(id); id != "" {
			c.callIDs = append(c.callIDs, id)
		}
	}
	if len(c.callIDs) == 0 {
		ev.OnError("no_calls", "no call identifiers", true)
		return c
	}
	lc, err := net.ListenPacket("udp", cfg.ListenAddr)
	if err != nil {
		ev.OnError("listen", err.Error(), true)
		return c
	}

	c.wrapKey, c.peer = key, peer
	c.deviceID, c.password, c.instance = cfg.DeviceID, cfg.Password, cfg.Instance
	c.mediaMode = cfg.MediaMode
	c.localConn = lc
	c.localPort = portOf(lc.LocalAddr())
	c.ctx, c.cancel = context.WithCancel(context.Background())
	c.st = &stats{}
	c.gate = newGate(cfg.ActiveStreams, maxOf(cfg.MaxStreams, 1), cfg.KeepaliveSec, cfg.BindingSec)
	c.disp = newDispatcher(c.ctx, lc, c.st)
	c.configCh = make(chan string, 1)

	ev.OnListen(portInt(lc.LocalAddr()))
	c.logf("listening on %s, peer %s, %d call id(s)", lc.LocalAddr(), cfg.Peer, len(c.callIDs))

	go c.consumeConfig()
	go c.statsLoop(maxOf(cfg.StatsSec, 1))
	for wid := 1; wid <= c.gate.max; wid++ {
		c.wg.Add(1)
		go c.worker(wid)
	}
	return c
}

func (c *Core) SetStreams(n int) {
	if c.gate != nil {
		c.gate.setStreams(n)
	}
}

func (c *Core) SetKeepalive(keepaliveSec, bindingSec int) {
	if c.gate != nil {
		c.gate.setIntervals(keepaliveSec, bindingSec)
	}
}

func (c *Core) Stop() {
	if c.cancel != nil {
		c.cancel()
	}
	if c.disp != nil {
		c.disp.shutdown()
	}
	if c.localConn != nil {
		_ = c.localConn.Close()
	}
	done := make(chan struct{})
	go func() { c.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
}

func (c *Core) logf(format string, a ...any) {
	if c.ev != nil {
		c.ev.OnLog(fmt.Sprintf(format, a...))
	}
}

func (c *Core) ready() { c.readyOnce.Do(func() { c.ev.OnReady() }) }

func (c *Core) fatal(code, message string) {
	c.fatalOnce.Do(func() {
		c.ev.OnError(code, message, true)
		if c.cancel != nil {
			c.cancel()
		}
	})
}

// consumeConfig waits for the first WireGuard config, ensures it carries an MTU, and hands it to the app once.
func (c *Core) consumeConfig() {
	select {
	case <-c.ctx.Done():
	case conf, ok := <-c.configCh:
		if ok && conf != "" {
			c.configOnce.Do(func() { c.ev.OnConfig(ensureMTU(conf)) })
		}
	}
}

func (c *Core) statsLoop(sec int) {
	t := time.NewTicker(time.Duration(sec) * time.Second)
	defer t.Stop()
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-t.C:
			wanted, _ := c.gate.get()
			c.ev.OnStats(int(c.st.active.Load()), wanted, c.st.up.Load(), c.st.down.Load())
		}
	}
}

// worker keeps stream wid connected while it is wanted, reconnecting on failure with a growing backoff.
func (c *Core) worker(wid int) {
	defer c.wg.Done()
	backoff := time.Second
	for {
		if !c.gate.waitWanted(c.ctx, wid) {
			return
		}
		callID := c.pickCall(wid)
		if callID == "" {
			c.fatal("no_calls", "no usable call identifiers left")
			return
		}
		creds, err := c.creds(c.ctx, callID)
		if err != nil {
			if !c.handleCredError(callID, err, &backoff) {
				return
			}
			continue
		}

		sctx, scancel := context.WithCancel(c.ctx)
		go c.gate.cancelWhenUnwanted(sctx, wid, scancel)
		getConfig := wid == 1
		_, serr := runSession(sctx, c, wid, creds, getConfig, c.configCh)
		scancel()

		if serr != nil {
			if strings.Contains(serr.Error(), "turn_auth") {
				c.invalidate(callID)
			}
			if !c.handleCredError(callID, serr, &backoff) {
				return
			}
			continue
		}
		backoff = time.Second
		if c.ctx.Err() != nil {
			return
		}
	}
}

// handleCredError applies the error's kind; returns false if the worker should stop.
func (c *Core) handleCredError(callID string, err error, backoff *time.Duration) bool {
	if ce, ok := err.(*CredError); ok {
		switch ce.Kind {
		case Fatal:
			c.fatal(ce.Code, ce.Message)
			return false
		case Unavailable:
			c.fatal("unavailable", ce.Message)
			return false
		case IdentifierDead:
			c.deadIDs.Store(callID, true)
			c.invalidate(callID)
			return c.sleep(time.Second)
		case RateLimited:
			return c.sleep(wait(backoff, 2*time.Second, 30*time.Second))
		default: // Transient
			return c.sleep(wait(backoff, time.Second, 20*time.Second))
		}
	}
	if c.ctx.Err() != nil {
		return false
	}
	c.logf("stream error: %v", err)
	return c.sleep(wait(backoff, time.Second, 20*time.Second))
}

func (c *Core) sleep(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-c.ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

func (c *Core) pickCall(wid int) string {
	live := make([]string, 0, len(c.callIDs))
	for _, id := range c.callIDs {
		if _, dead := c.deadIDs.Load(id); !dead {
			live = append(live, id)
		}
	}
	if len(live) == 0 {
		return ""
	}
	return live[(wid-1)%len(live)]
}

// creds returns cached TURN credentials for callID, fetching through the provider when none are cached or they are
// near expiry. Calls for the same callID are serialised; different callIDs may fetch concurrently.
func (c *Core) creds(ctx context.Context, callID string) (*TurnCreds, error) {
	c.credMu.Lock()
	e := c.credCache[callID]
	if e == nil {
		e = &credEntry{}
		c.credCache[callID] = e
	}
	c.credMu.Unlock()

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.creds != nil && (e.expiry.IsZero() || time.Now().Before(e.expiry)) {
		return e.creds, nil
	}
	fctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	tc, err := provider.Fetch(fctx, callID, c.instance)
	if err != nil {
		return nil, err
	}
	if tc != nil {
		tc = &TurnCreds{URLs: turnURLs(tc.URLs), Username: tc.Username, Password: tc.Password, TTL: tc.TTL}
	}
	if tc == nil || len(tc.URLs) == 0 {
		return nil, &CredError{Kind: Transient, Code: "no_creds", Message: "provider returned no TURN servers"}
	}
	e.creds = tc
	if tc.TTL > 0 {
		// Refresh a little before the real expiry.
		e.expiry = time.Now().Add(time.Duration(tc.TTL)*time.Second - 30*time.Second)
	} else {
		e.expiry = time.Time{}
	}
	return tc, nil
}

func (c *Core) invalidate(callID string) {
	c.credMu.Lock()
	if e := c.credCache[callID]; e != nil {
		e.mu.Lock()
		e.creds = nil
		e.mu.Unlock()
	}
	c.credMu.Unlock()
}

// turnAddrs turns the provider's TURN URLs ("turn:host:port?transport=udp", "turns:…" or plain "host:port") into
// the "host:port" the transport dials over UDP: duplicates dropped, entries that say UDP (or say nothing) first,
// the rest after in their order (a server listed for TCP usually answers UDP on the same port too).
func turnAddrs(urls []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, wantUDP := range []bool{true, false} {
		for _, u := range urls {
			u = strings.TrimSpace(u)
			query := ""
			if i := strings.IndexByte(u, '?'); i >= 0 {
				u, query = u[:i], strings.ToLower(u[i+1:])
			}
			udp := !strings.HasPrefix(u, "turns:") && !strings.Contains(query, "transport=tcp")
			u = strings.TrimPrefix(strings.TrimPrefix(u, "turns:"), "turn:")
			if udp != wantUDP || seen[u] {
				continue
			}
			if _, _, err := net.SplitHostPort(u); err != nil {
				continue
			}
			seen[u] = true
			out = append(out, u)
		}
	}
	return out
}

// ensureMTU inserts "MTU = 1280" after [Interface] if the config does not set one (the core path wraps packets
// once more, so the effective MTU must stay low).
func ensureMTU(conf string) string {
	if strings.Contains(conf, "MTU =") || strings.Contains(conf, "MTU=") {
		return conf
	}
	var out []string
	for _, line := range strings.Split(conf, "\n") {
		out = append(out, line)
		if strings.TrimSpace(line) == "[Interface]" {
			out = append(out, "MTU = 1280")
		}
	}
	return strings.Join(out, "\n")
}

func wait(backoff *time.Duration, min, max time.Duration) time.Duration {
	d := *backoff
	if d < min {
		d = min
	}
	next := d * 2
	if next > max {
		next = max
	}
	*backoff = next
	return d
}

func portOf(a net.Addr) string {
	if _, p, err := net.SplitHostPort(a.String()); err == nil {
		return p
	}
	return "0"
}

func portInt(a net.Addr) int {
	if ua, ok := a.(*net.UDPAddr); ok {
		return ua.Port
	}
	return 0
}

func maxOf(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// nullLoggerFactory silences pion's internal logging (the core logs through Events instead).
type nullLoggerFactory struct{}

func (nullLoggerFactory) NewLogger(string) logging.LeveledLogger { return nullLogger{} }

type nullLogger struct{}

func (nullLogger) Trace(string)          {}
func (nullLogger) Tracef(string, ...any) {}
func (nullLogger) Debug(string)          {}
func (nullLogger) Debugf(string, ...any) {}
func (nullLogger) Info(string)           {}
func (nullLogger) Infof(string, ...any)  {}
func (nullLogger) Warn(string)           {}
func (nullLogger) Warnf(string, ...any)  {}
func (nullLogger) Error(string)          {}
func (nullLogger) Errorf(string, ...any) {}
