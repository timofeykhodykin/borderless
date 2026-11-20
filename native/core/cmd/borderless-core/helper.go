package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/core"
)

// The helper (`-helper`): the service's own network work, in one unprivileged process — HTTP requests (checks,
// subscriptions, routing lists, country lookups), probes of servers (a throwaway Xray instance each) and the local
// proxies through a server (ViaServer). Every connection leaves by the physical interface ([binding]). Requests come
// as JSON lines on stdin and are served at once, each on its own; answers go to fd 3 as JSON lines with the request's
// id (stdout is Xray's log). See shared/src/macosMain …/MacHosts.kt for the other side.

type request struct {
	ID      int64             `json:"id"`
	Op      string            `json:"op"`
	URL     string            `json:"url,omitempty"`
	Method  string            `json:"method,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
	Connect int               `json:"connectMs,omitempty"`
	Read    int               `json:"readMs,omitempty"`
	Follow  bool              `json:"follow,omitempty"`
	Max     int64             `json:"max,omitempty"`
	Status  bool              `json:"statusOnly,omitempty"`
	Socks   *socks            `json:"socks,omitempty"`
	Config  string            `json:"config,omitempty"`
	Handle  int64             `json:"handle,omitempty"`
	Iface   string            `json:"iface,omitempty"`
	DNS     string            `json:"dns,omitempty"`
	Port    int               `json:"port,omitempty"`
	User    string            `json:"user,omitempty"`
	Pass    string            `json:"pass,omitempty"`
}

type socks struct {
	Port int    `json:"port"`
	User string `json:"user"`
	Pass string `json:"pass"`
}

type reply struct {
	ID      int64             `json:"id"`
	Error   string            `json:"error,omitempty"`
	Kind    string            `json:"kind,omitempty"`
	Code    int               `json:"code,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
	Body    string            `json:"body,omitempty"`
	URL     string            `json:"url,omitempty"`
	Ms      int64             `json:"ms,omitempty"`
	Handle  int64             `json:"handle,omitempty"`
	Port    int               `json:"port,omitempty"`
	OK      bool              `json:"ok,omitempty"`
	Version string            `json:"version,omitempty"`
}

type helper struct {
	bind    binding
	out     *os.File
	outMu   sync.Mutex
	mu      sync.Mutex
	proxies map[int64]*core.Instance
	next    int64
}

func runHelper() {
	h := &helper{out: os.NewFile(3, "replies"), proxies: map[int64]*core.Instance{}}
	in := bufio.NewReaderSize(os.Stdin, 1<<20)
	for {
		line, err := in.ReadBytes('\n')
		if len(line) > 0 {
			var r request
			if jerr := json.Unmarshal(line, &r); jerr != nil {
				fmt.Fprintf(os.Stderr, "helper: bad request: %v\n", jerr)
			} else {
				go h.serve(r)
			}
		}
		if err != nil {
			// The service is gone: nothing more to do.
			h.mu.Lock()
			for _, p := range h.proxies {
				_ = p.Close()
			}
			h.mu.Unlock()
			return
		}
	}
}

func (h *helper) send(r reply) {
	data, _ := json.Marshal(r)
	h.outMu.Lock()
	defer h.outMu.Unlock()
	_, _ = h.out.Write(append(data, '\n'))
}

func (h *helper) serve(r request) {
	var out reply
	switch r.Op {
	case "network":
		if err := h.bind.set(r.Iface, r.DNS); err != nil {
			out = failure(err)
		} else {
			out.OK = true
		}
	case "http":
		out = h.request(r)
	case "probe":
		out = h.probe(r)
	case "proxy-start":
		out = h.proxyStart(r)
	case "proxy-stop":
		h.mu.Lock()
		p := h.proxies[r.Handle]
		delete(h.proxies, r.Handle)
		h.mu.Unlock()
		if p != nil {
			_ = p.Close()
		}
		out.OK = true
	case "free-port":
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			out = failure(err)
		} else {
			out.Port = l.Addr().(*net.TCPAddr).Port
			_ = l.Close()
		}
	case "version":
		out.Version = "Xray " + core.Version()
	case "socks-check":
		out.OK = socksAccepts(r.Port, r.User, r.Pass)
	default:
		out = reply{Error: "unknown op " + r.Op, Kind: "other"}
	}
	out.ID = r.ID
	h.send(out)
}

func (h *helper) request(r request) reply {
	connect := time.Duration(max(r.Connect, 1000)) * time.Millisecond
	read := time.Duration(max(r.Read, 1000)) * time.Millisecond
	tr := &http.Transport{
		DialContext:           h.bind.dialer(connect).DialContext,
		TLSHandshakeTimeout:   connect,
		ResponseHeaderTimeout: read,
		ForceAttemptHTTP2:     true,
	}
	if r.Socks != nil {
		tr.Proxy = http.ProxyURL(&url.URL{Scheme: "socks5", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(r.Socks.Port)),
			User: url.UserPassword(r.Socks.User, r.Socks.Pass)})
	}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr}
	if !r.Follow {
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	}
	ctx, cancel := context.WithTimeout(context.Background(), connect+read+read)
	defer cancel()
	method := r.Method
	if method == "" {
		method = http.MethodGet
	}
	req, err := http.NewRequestWithContext(ctx, method, r.URL, nil)
	if err != nil {
		return reply{Error: err.Error(), Kind: "other"}
	}
	for k, v := range r.Headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return failure(err)
	}
	defer resp.Body.Close()
	out := reply{Code: resp.StatusCode, Headers: map[string]string{}, URL: resp.Request.URL.String()}
	for k, v := range resp.Header {
		if len(v) > 0 {
			out.Headers[strings.ToLower(k)] = v[0]
		}
	}
	if r.Status || method == http.MethodHead {
		return out
	}
	limit := r.Max
	if limit <= 0 {
		limit = 1 << 20
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return failure(err)
	}
	if int64(len(body)) > limit {
		return reply{Error: fmt.Sprintf("response larger than %d bytes", limit), Kind: "toobig"}
	}
	out.Body = base64.StdEncoding.EncodeToString(body)
	return out
}

func (h *helper) probe(r request) reply {
	inst, err := startInstance([]byte(r.Config))
	if err != nil {
		return reply{Error: err.Error(), Kind: "other"}
	}
	defer inst.Close()
	ms, err := measureDelay(context.Background(), inst, r.URL)
	if err != nil {
		return reply{Error: err.Error(), Kind: "other"}
	}
	return reply{Ms: ms}
}

func (h *helper) proxyStart(r request) reply {
	inst, err := startInstance([]byte(r.Config))
	if err != nil {
		return reply{Error: err.Error(), Kind: "other"}
	}
	h.mu.Lock()
	h.next++
	id := h.next
	h.proxies[id] = inst
	h.mu.Unlock()
	return reply{Handle: id}
}

// failure tells what kind of failure a request met, as the app's error texts need it (NetError.Kind).
func failure(err error) reply {
	kind := "other"
	var dns *net.DNSError
	var cert *tls.CertificateVerificationError
	var unknown x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var timeout interface{ Timeout() bool }
	var op *net.OpError
	switch {
	case errors.As(err, &dns):
		kind = "dns"
	case errors.As(err, &cert), errors.As(err, &unknown), errors.As(err, &hostname), strings.Contains(err.Error(), "tls:"):
		kind = "tls"
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &timeout) && timeout.Timeout():
		kind = "unreachable"
	case errors.As(err, &op) && op.Op == "dial":
		kind = "unreachable"
	}
	return reply{Error: err.Error(), Kind: kind}
}

// socksAccepts: does the SOCKS5 server on 127.0.0.1:port take user / pass (a stranger on the port must not pass).
func socksAccepts(port int, user, pass string) bool {
	c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 2*time.Second)
	if err != nil {
		return false
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := c.Write([]byte{5, 1, 2}); err != nil {
		return false
	}
	b := make([]byte, 2)
	if _, err := io.ReadFull(c, b); err != nil || b[0] != 5 || b[1] != 2 {
		return false
	}
	msg := append([]byte{1, byte(len(user))}, user...)
	msg = append(msg, byte(len(pass)))
	msg = append(msg, pass...)
	if _, err := c.Write(msg); err != nil {
		return false
	}
	if _, err := io.ReadFull(c, b); err != nil {
		return false
	}
	return b[0] == 1 && b[1] == 0
}
