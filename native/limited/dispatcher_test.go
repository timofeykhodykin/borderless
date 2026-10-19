package limited

import (
	"context"
	"net"
	"testing"
	"time"
)

// With no packet ever arriving, shutdown must not wait on the blocked read of the local port.
func TestDispatcherShutdownWithoutTraffic(t *testing.T) {
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	d := newDispatcher(context.Background(), conn, &stats{})
	done := make(chan struct{})
	go func() { d.shutdown(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("shutdown hung on an idle local port")
	}
}
