package limited

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/cbeuw/connutil"
	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
)

const (
	workerSendBuf      = 128
	sessionReadTimeout = 30 * time.Minute
	readBufSize        = 1600
	socketBufSize      = 625 * 1024
	keepaliveByte      = 0xFF
)

// handshakeSem limits concurrent DTLS handshakes across all cores (they are CPU-heavy).
var handshakeSem = make(chan struct{}, 3)

type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }

func isAuthError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "unauthorized") || strings.Contains(s, "401") || strings.Contains(s, "403")
}

// runSession runs one stream: it tries the TURN endpoints the calls service offers (UDP first, then TCP/TLS when a
// network drops UDP), and the first one that carries a DTLS session is used.
func runSession(ctx context.Context, m *Core, sessionID int, creds *TurnCreds, getConfig bool, configCh chan<- string) (bool, error) {
	if len(creds.URLs) == 0 {
		return false, fmt.Errorf("no TURN URL in credentials")
	}
	candidates := turnCandidates(creds.URLs, sessionID)
	if len(candidates) == 0 {
		return false, fmt.Errorf("no usable TURN endpoint")
	}
	var lastErr error
	for _, ep := range candidates {
		if ctx.Err() != nil {
			return false, ctx.Err()
		}
		delivered, err := runStream(ctx, m, sessionID, creds, ep, getConfig, configCh)
		if err == nil {
			return delivered, nil
		}
		lastErr = err
		// Bad credentials are not helped by another transport; neither is a fatal server refusal.
		var ce *CredError
		if errors.As(err, &ce) && (ce.Code == "turn_auth" || ce.Kind == Fatal || ce.Kind == Unavailable) {
			return false, err
		}
		if ctx.Err() != nil {
			return false, ctx.Err()
		}
		m.logf("stream %d: %s failed: %v", sessionID, ep.label(), err)
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no usable TURN endpoint")
	}
	return false, lastErr
}

// runStream carries one stream over one TURN endpoint: allocate a relay, open DTLS to the server through it, frame
// packets as RTP, optionally fetch the WireGuard config, then forward UDP between the local port and DTLS.
func runStream(ctx context.Context, m *Core, sessionID int, creds *TurnCreds, ep turnEndpoint, getConfig bool, configCh chan<- string) (bool, error) {
	delivered := false
	tc, relay, turnConn, err := openTURNAllocation(ctx, m, creds, ep)
	if err != nil {
		return false, err
	}
	defer turnConn.Close()
	defer tc.Close()
	defer relay.Close()
	m.logf("stream %d: relay %s via %s", sessionID, relay.LocalAddr(), ep.label())

	pipeA, pipeB := connutil.AsyncPacketPipe()
	sessCtx, sessCancel := context.WithCancel(ctx)
	defer sessCancel()

	var sessionWg sync.WaitGroup
	sessionWg.Add(1)
	go func() {
		defer sessionWg.Done()
		every(sessCtx, &m.gate.bindingSec, func() bool { tc.SendBindingRequest(); return true })
	}()

	mediaCfg := newMediaConfig(m.mediaMode)
	mediaWriteState := newMediaState()

	stopRelay := context.AfterFunc(sessCtx, func() {
		_ = relay.SetDeadline(time.Now())
		_ = pipeA.SetDeadline(time.Now())
	})
	defer stopRelay()

	var relayWg sync.WaitGroup
	relayWg.Add(2)
	go func() { // relay -> unwrap -> dtls pipe
		defer relayWg.Done()
		defer sessCancel()
		buf := make([]byte, readBufSize+80)
		plain := make([]byte, readBufSize)
		for {
			n, _, readErr := relay.ReadFrom(buf)
			if readErr != nil {
				return
			}
			payload := buf[:n]
			if !isRTP(payload) {
				continue
			}
			mlen, werr := mediaUnwrap(m.wrapKey, payload, plain)
			if werr != nil {
				continue
			}
			if _, werr := pipeA.WriteTo(plain[:mlen], m.peer); werr != nil {
				return
			}
		}
	}()
	go func() { // dtls pipe -> wrap -> relay
		defer relayWg.Done()
		defer sessCancel()
		b := make([]byte, readBufSize)
		for {
			n, _, readErr := pipeA.ReadFrom(b)
			if readErr != nil {
				return
			}
			wrapped, werr := mediaWrap(m.wrapKey, b[:n], mediaCfg, mediaWriteState)
			if werr != nil {
				return
			}
			if _, werr := relay.WriteTo(wrapped, m.peer); werr != nil {
				return
			}
		}
	}()

	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		return false, fmt.Errorf("certificate: %w", err)
	}
	select {
	case handshakeSem <- struct{}{}:
	case <-sessCtx.Done():
		return false, sessCtx.Err()
	}
	dtlsConn, err := dtls.Client(pipeB, m.peer, &dtls.Config{
		Certificates:          []tls.Certificate{cert},
		InsecureSkipVerify:    true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
	})
	if err != nil {
		<-handshakeSem
		return false, fmt.Errorf("DTLS client: %w", err)
	}
	defer dtlsConn.Close()

	hctx, hcancel := context.WithTimeout(sessCtx, 20*time.Second)
	err = dtlsConn.HandshakeContext(hctx)
	hcancel()
	<-handshakeSem
	if err != nil {
		if es := strings.ToLower(err.Error()); strings.Contains(es, "deadline") || strings.Contains(es, "timeout") {
			return false, fmt.Errorf("handshake timeout (password/key not confirmed): %w", err)
		}
		return false, fmt.Errorf("DTLS handshake: %w", err)
	}
	m.logf("stream %d: connected", sessionID)

	m.st.active.Add(1)
	defer m.st.active.Add(-1)

	if getConfig && configCh != nil {
		conf, limit, cerr := requestConfig(dtlsConn, m.localPort, m.deviceID, m.password)
		if limit > 0 {
			// The server is at its worker quota: keep within the limit it named and ask once more.
			m.gate.setMax(limit)
			if m.ev != nil {
				m.ev.OnPolicy(limit)
			}
			m.logf("stream %d: server limit %d stream(s)", sessionID, limit)
			select {
			case <-time.After(time.Second):
			case <-sessCtx.Done():
				return false, sessCtx.Err()
			}
			conf, limit, cerr = requestConfig(dtlsConn, m.localPort, m.deviceID, m.password)
			if limit > 0 {
				return false, fmt.Errorf("server stream limit: max_workers=%d", limit)
			}
		}
		if cerr != nil {
			if strings.Contains(cerr.Error(), "FATAL_AUTH") {
				return false, &CredError{Kind: Fatal, Code: "server_auth", Message: cerr.Error()}
			}
			m.logf("stream %d: config error: %v", sessionID, cerr)
		} else if conf != "" {
			select {
			case configCh <- conf:
				delivered = true
			default:
				delivered = true
			}
		}
	}

	slot := &workerSlot{id: sessionID, sendCh: make(chan []byte, workerSendBuf)}
	m.disp.register(slot)
	defer m.disp.unregister(slot)
	m.ready()

	var proxyWg sync.WaitGroup
	proxyWg.Add(3)

	stopDTLS := context.AfterFunc(sessCtx, func() { _ = dtlsConn.SetDeadline(time.Now()) })
	defer stopDTLS()

	go func() { // keepalive
		defer proxyWg.Done()
		ping := []byte{keepaliveByte}
		every(sessCtx, &m.gate.keepaliveSec, func() bool {
			_ = dtlsConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			_, werr := dtlsConn.Write(ping)
			return werr == nil
		})
	}()
	go func() { // local -> dtls
		defer proxyWg.Done()
		defer sessCancel()
		for {
			select {
			case <-sessCtx.Done():
				return
			case pkt, ok := <-slot.sendCh:
				if !ok {
					return
				}
				_ = dtlsConn.SetWriteDeadline(time.Now().Add(sessionReadTimeout))
				_, werr := dtlsConn.Write(pkt)
				putPktBuf(pkt)
				if werr != nil {
					return
				}
			}
		}
	}()
	go func() { // dtls -> local
		defer proxyWg.Done()
		defer sessCancel()
		for {
			pkt := getPktBuf(2048)
			_ = dtlsConn.SetReadDeadline(time.Now().Add(sessionReadTimeout))
			n, readErr := dtlsConn.Read(pkt)
			if readErr != nil {
				putPktBuf(pkt)
				if sessCtx.Err() != nil {
					return
				}
				if ne, ok := readErr.(net.Error); ok && ne.Timeout() {
					continue
				}
				return
			}
			if n == 1 && pkt[0] == keepaliveByte {
				putPktBuf(pkt)
				continue
			}
			pkt = pkt[:n]
			select {
			case m.disp.returnCh <- pkt:
			case <-sessCtx.Done():
				putPktBuf(pkt)
				return
			}
		}
	}()

	proxyWg.Wait()
	sessCancel()
	relayWg.Wait()
	sessionWg.Wait()
	_ = pipeA.Close()
	_ = pipeB.Close()
	return delivered, nil
}

// requestConfig asks the server for the WireGuard config over the DTLS session. A server at its worker quota answers
// "POLICY:max_workers=N" instead: the caller lowers the stream count and asks again.
func requestConfig(conn net.Conn, localPort, deviceID, password string) (string, int, error) {
	payload := fmt.Sprintf("GETCONF:%s|%s|%s", localPort, deviceID, password)
	if _, err := conn.Write([]byte(payload)); err != nil {
		return "", 0, fmt.Errorf("send GETCONF: %w", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	b := make([]byte, 4096)
	n, err := conn.Read(b)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return "", 0, fmt.Errorf("read config: %w", err)
	}
	resp := string(b[:n])
	if resp == "NOCONF" {
		return "", 0, nil
	}
	if max, ok := policyMax(resp); ok {
		return "", max, nil
	}
	if strings.HasPrefix(resp, "POLICY:") {
		return "", 0, fmt.Errorf("unsupported server policy: %s", resp)
	}
	if strings.HasPrefix(resp, "DENIED:") {
		// The server's reason travels as a code; the app maps it to its own text.
		return "", 0, fmt.Errorf("FATAL_AUTH[%s]", strings.TrimPrefix(resp, "DENIED:"))
	}
	return resp, 0, nil
}
