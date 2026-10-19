package limited

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

var pktPool = sync.Pool{New: func() any { return make([]byte, 2048) }}

func getPktBuf(size int) []byte {
	b := pktPool.Get().([]byte)
	if cap(b) < size {
		b = make([]byte, size)
	}
	return b[:size]
}

func putPktBuf(b []byte) {
	if cap(b) < 2048 {
		return
	}
	pktPool.Put(b[:cap(b)])
}

const (
	returnChBuf   = 512
	stickyPackets = 12 // packets kept going to one stream before rotating (keeps a flow on one path)
)

// workerSlot is one connected stream the dispatcher can hand upstream packets to.
type workerSlot struct {
	id     int
	sendCh chan []byte
}

// dispatcher moves packets between the local UDP port (the app's WireGuard outbound) and the connected streams:
// upstream packets are spread across streams round-robin (sticky in short runs), downstream packets from any
// stream go back to the last local client address.
type dispatcher struct {
	localConn  net.PacketConn
	clientAddr atomic.Pointer[net.Addr]
	workers    atomic.Pointer[[]*workerSlot]
	mu         sync.Mutex
	rrIndex    atomic.Int32
	rrCount    atomic.Int32
	returnCh   chan []byte
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	stats      *stats
}

func newDispatcher(ctx context.Context, localConn net.PacketConn, st *stats) *dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	d := &dispatcher{localConn: localConn, returnCh: make(chan []byte, returnChBuf), ctx: dctx, cancel: dcancel, stats: st}
	empty := make([]*workerSlot, 0)
	d.workers.Store(&empty)
	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d
}

func (d *dispatcher) shutdown() {
	d.cancel()
	// readLoop sits in ReadFrom until a packet comes; with no traffic that is forever (Stop hung on it).
	_ = d.localConn.SetReadDeadline(time.Now())
	d.wg.Wait()
}

func (d *dispatcher) register(w *workerSlot) {
	d.mu.Lock()
	defer d.mu.Unlock()
	old := d.workers.Load()
	nw := make([]*workerSlot, len(*old)+1)
	copy(nw, *old)
	nw[len(*old)] = w
	d.workers.Store(&nw)
}

func (d *dispatcher) unregister(w *workerSlot) {
	d.mu.Lock()
	defer d.mu.Unlock()
	old := d.workers.Load()
	nw := make([]*workerSlot, 0, len(*old))
	for _, s := range *old {
		if s != w {
			nw = append(nw, s)
		}
	}
	d.workers.Store(&nw)
}

func (d *dispatcher) readLoop() {
	defer d.wg.Done()
	for {
		if d.ctx.Err() != nil {
			return
		}
		pkt := getPktBuf(2048)
		n, addr, err := d.localConn.ReadFrom(pkt)
		if err != nil {
			putPktBuf(pkt)
			if d.ctx.Err() != nil {
				return
			}
			time.Sleep(10 * time.Millisecond)
			continue
		}
		pkt = pkt[:n]
		d.clientAddr.Store(&addr)
		d.stats.up.Add(int64(n))

		wp := d.workers.Load()
		if wp == nil || len(*wp) == 0 {
			putPktBuf(pkt)
			continue
		}
		ws := *wp
		nwk := len(ws)
		idx := int(d.rrIndex.Load()) % nwk
		sent := false
		select {
		case ws[idx].sendCh <- pkt:
			sent = true
			if d.rrCount.Add(1) >= int32(stickyPackets) {
				d.rrIndex.Store(int32((idx + 1) % nwk))
				d.rrCount.Store(0)
			}
		default:
			for i := 1; i < nwk; i++ {
				alt := (idx + i) % nwk
				select {
				case ws[alt].sendCh <- pkt:
					sent = true
					d.rrIndex.Store(int32(alt))
					d.rrCount.Store(1)
				default:
				}
				if sent {
					break
				}
			}
		}
		if !sent {
			d.rrIndex.Store(int32((idx + 1) % nwk))
			d.rrCount.Store(0)
			putPktBuf(pkt)
		}
	}
}

func (d *dispatcher) writeLoop() {
	defer d.wg.Done()
	for {
		select {
		case <-d.ctx.Done():
			return
		case pkt := <-d.returnCh:
			ap := d.clientAddr.Load()
			if ap == nil {
				putPktBuf(pkt)
				continue
			}
			if _, err := d.localConn.WriteTo(pkt, *ap); err != nil && d.ctx.Err() != nil {
				putPktBuf(pkt)
				return
			}
			d.stats.down.Add(int64(len(pkt)))
			putPktBuf(pkt)
		}
	}
}
