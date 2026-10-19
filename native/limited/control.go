package limited

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

// gate holds, for one running core, how many streams should be connected and how often idle ones send keepalives
// and TURN binding refreshes. The app changes these while the tunnel runs (SetStreams / SetKeepalive); workers
// learn of a change through a channel that is closed and replaced on every change, so idle workers cost no timers.
//
// It is per-core, not global: two cores can run at once (the connected server and a throwaway probe), each with
// its own gate.
type gate struct {
	mu           sync.Mutex
	wanted       int
	max          int
	ch           chan struct{}
	keepaliveSec atomic.Int64
	bindingSec   atomic.Int64
}

func newGate(active, max, keepaliveSec, bindingSec int) *gate {
	if max < 1 {
		max = 1
	}
	g := &gate{wanted: clampInt(active, 1, max), max: max, ch: make(chan struct{})}
	g.setIntervals(keepaliveSec, bindingSec)
	return g
}

func (g *gate) get() (int, <-chan struct{}) {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.wanted, g.ch
}

func (g *gate) setStreams(n int) {
	n = clampInt(n, 1, g.max)
	g.mu.Lock()
	defer g.mu.Unlock()
	if n == g.wanted {
		return
	}
	g.wanted = n
	close(g.ch)
	g.ch = make(chan struct{})
}

// setMax lowers (or raises) the hard ceiling; a wanted count above it is cut at once so its workers stop.
func (g *gate) setMax(n int) {
	n = clampInt(n, 1, 1024)
	g.mu.Lock()
	defer g.mu.Unlock()
	if n == g.max {
		return
	}
	g.max = n
	if g.wanted > n {
		g.wanted = n
		close(g.ch)
		g.ch = make(chan struct{})
	}
}

func (g *gate) setIntervals(keepaliveSec, bindingSec int) {
	g.keepaliveSec.Store(int64(clampSeconds(keepaliveSec)))
	g.bindingSec.Store(int64(clampSeconds(bindingSec)))
}

// waitWanted blocks until worker wid (1-based) is within the wanted count, or ctx ends (returns false).
func (g *gate) waitWanted(ctx context.Context, wid int) bool {
	for {
		n, changed := g.get()
		if wid <= n {
			return true
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return false
		}
	}
}

// cancelWhenUnwanted cancels as soon as worker wid is no longer wanted; returns when done ends.
func (g *gate) cancelWhenUnwanted(done context.Context, wid int, cancel context.CancelFunc) {
	for {
		n, changed := g.get()
		if wid > n {
			cancel()
			return
		}
		select {
		case <-changed:
		case <-done.Done():
			return
		}
	}
}

// every runs f each time the interval read from d passes (re-read after every run) until ctx ends or f returns
// false. d is an atomic so the interval can change while running.
func every(ctx context.Context, d *atomic.Int64, f func() bool) {
	t := time.NewTimer(time.Duration(d.Load()))
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if !f() {
				return
			}
			t.Reset(time.Duration(d.Load()))
		}
	}
}

func clampSeconds(s int) time.Duration {
	return time.Duration(clampInt(s, 5, 300)) * time.Second
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
