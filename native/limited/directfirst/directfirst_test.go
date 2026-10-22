package directfirst

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// A ClientHello-looking first record and the server's answer to it.
var (
	hello  = []byte{0x16, 3, 1, 0, 4, 'h', 'e', 'l', 'o'}
	answer = []byte("server-hello")
)

// target answers the first read with answer, then echoes; silent: reads and never answers.
func target(t *testing.T, silent bool) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 64)
				if _, err := c.Read(buf); err != nil || silent {
					time.Sleep(2 * time.Second)
					return
				}
				_, _ = c.Write(answer)
				_, _ = io.Copy(c, c)
			}(c)
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return ln
}

// back is a minimal SOCKS5 server with a password, standing in for the core's inbound; it counts connections.
func back(t *testing.T, user, pass string, count *atomic.Int64) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{cfg: Config{User: user, Pass: pass}}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				dest, err := s.handshake(c)
				if err != nil {
					return
				}
				count.Add(1)
				d, err := net.Dial("tcp", dest)
				if err != nil {
					return
				}
				splice(c, d)
			}(c)
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return ln
}

func start(t *testing.T, cfg Config) *Server {
	t.Helper()
	s, err := Listen(cfg)
	if err != nil {
		t.Fatal(err)
	}
	go s.Serve()
	t.Cleanup(func() { s.Close() })
	return s
}

// connect opens target through s like the core's socks outbound does, sends hello and reads the answer.
func connect(t *testing.T, s *Server, user, pass, dest string) ([]byte, error) {
	t.Helper()
	c, err := net.Dial("tcp", s.Addr().String())
	if err != nil {
		return nil, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	c.Write([]byte{5, 1, 2})
	var m [2]byte
	if _, err := io.ReadFull(c, m[:]); err != nil {
		return nil, err
	}
	auth := append([]byte{1, byte(len(user))}, user...)
	auth = append(append(auth, byte(len(pass))), pass...)
	c.Write(auth)
	if _, err := io.ReadFull(c, m[:]); err != nil || m[1] != 0 {
		return nil, errors.New("auth refused")
	}
	req, _ := connectRequest(dest)
	c.Write(req)
	var rep [3]byte
	if _, err := io.ReadFull(c, rep[:]); err != nil || rep[1] != 0 {
		return nil, errors.New("connect refused")
	}
	if _, err := readAddr(c); err != nil {
		return nil, err
	}
	c.Write(hello)
	got := make([]byte, len(answer))
	_, err = io.ReadFull(c, got)
	return got, err
}

func TestDirectWhenItWorks(t *testing.T) {
	tg := target(t, false)
	var viaBack atomic.Int64
	bk := back(t, "bu", "bp", &viaBack)
	s := start(t, Config{User: "u", Pass: "p", Back: bk.Addr().String(), BackUser: "bu", BackPass: "bp"})
	got, err := connect(t, s, "u", "p", tg.Addr().String())
	if err != nil || !bytes.Equal(got, answer) {
		t.Fatalf("got %q, %v", got, err)
	}
	time.Sleep(50 * time.Millisecond)
	if st := s.Stats(); st.Direct != 1 || st.Server != 0 || viaBack.Load() != 0 {
		t.Fatalf("stats %+v, back %d", st, viaBack.Load())
	}
}

func TestServerWhenDirectIsRefusedThenRemembered(t *testing.T) {
	tg := target(t, false)
	var viaBack, dials atomic.Int64
	bk := back(t, "bu", "bp", &viaBack)
	s := start(t, Config{
		User: "u", Pass: "p", Back: bk.Addr().String(), BackUser: "bu", BackPass: "bp",
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dials.Add(1)
			return nil, errors.New("connection refused")
		},
	})
	for i := 0; i < 2; i++ {
		got, err := connect(t, s, "u", "p", tg.Addr().String())
		if err != nil || !bytes.Equal(got, answer) {
			t.Fatalf("try %d: got %q, %v", i, got, err)
		}
	}
	time.Sleep(50 * time.Millisecond)
	// The second connection went to the server without a direct try.
	if dials.Load() != 1 || viaBack.Load() != 2 {
		t.Fatalf("dials %d, back %d", dials.Load(), viaBack.Load())
	}
	if st := s.Stats(); st.Fallbacks != 1 || st.Server != 2 || st.Direct != 0 {
		t.Fatalf("stats %+v", st)
	}
}

func TestServerWhenTLSGetsNoAnswer(t *testing.T) {
	silent := target(t, true)
	working := target(t, false)
	var viaBack atomic.Int64
	bk := back(t, "bu", "bp", &viaBack)
	// Directly the handshake goes to a server that never answers; the back way reaches a working one.
	s := start(t, Config{
		User: "u", Pass: "p", Back: bk.Addr().String(), BackUser: "bu", BackPass: "bp", AnswerTimeout: 200 * time.Millisecond,
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, silent.Addr().String())
		},
	})
	got, err := connect(t, s, "u", "p", working.Addr().String())
	if err != nil || !bytes.Equal(got, answer) {
		t.Fatalf("got %q, %v", got, err)
	}
	if viaBack.Load() != 1 {
		t.Fatalf("back %d", viaBack.Load())
	}
}

func TestWrongCredentialsRefused(t *testing.T) {
	tg := target(t, false)
	var viaBack atomic.Int64
	bk := back(t, "bu", "bp", &viaBack)
	s := start(t, Config{User: "u", Pass: "p", Back: bk.Addr().String(), BackUser: "bu", BackPass: "bp"})
	if _, err := connect(t, s, "u", "wrong", tg.Addr().String()); err == nil {
		t.Fatal("wrong password accepted")
	}
}

func TestRememberExpires(t *testing.T) {
	s := &Server{cfg: Config{Remember: 20 * time.Millisecond}, memo: map[string]time.Time{}}
	if !s.rememberServer("a") || s.rememberServer("a") {
		t.Fatal("first remember should be new, the second not")
	}
	if !s.viaServer("a") {
		t.Fatal("remembered host not through the server")
	}
	time.Sleep(30 * time.Millisecond)
	if s.viaServer("a") {
		t.Fatal("expired host still through the server")
	}
}

func TestSniff(t *testing.T) {
	// A minimal ClientHello with a server_name extension for example.com.
	name := "example.com"
	sni := []byte{0, 0, 0, 0, 0, 0, 0, 0, byte(len(name))} // type, length, list length, name type, name length
	sni = append(sni, name...)
	binary.BigEndian.PutUint16(sni[2:], uint16(len(sni)-4)) // extension length
	binary.BigEndian.PutUint16(sni[4:], uint16(len(sni)-6)) // server name list length
	body := append([]byte{3, 3}, make([]byte, 32)...)        // version, random
	body = append(body, 0, 0, 2, 0x13, 1, 1, 0)              // session id, one cipher suite, one compression
	body = append(body, byte(len(sni)>>8), byte(len(sni)))   // extensions length
	body = append(body, sni...)
	hs := append([]byte{1, 0, byte(len(body) >> 8), byte(len(body))}, body...)
	rec := append([]byte{0x16, 3, 1, byte(len(hs) >> 8), byte(len(hs))}, hs...)
	if got := Sniff(rec); got != name {
		t.Fatalf("tls: got %q", got)
	}
	if got := Sniff([]byte("GET / HTTP/1.1\r\nUser-Agent: x\r\nHost: Example.org:8080\r\n\r\n")); got != "Example.org" {
		t.Fatalf("http: got %q", got)
	}
	for _, b := range [][]byte{hello, []byte("SSH-2.0-x\r\n"), []byte("GET / HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n"), rec[:20]} {
		if got := Sniff(b); got != "" {
			t.Fatalf("%q: got %q", b, got)
		}
	}
}
