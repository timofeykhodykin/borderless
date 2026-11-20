package limited

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

// captureEvents records what a core reports: the detailed-log lines and the last error event.
type captureEvents struct {
	mu    sync.Mutex
	lines []string
	code  string
	fatal bool
}

func (e *captureEvents) OnListen(int)                   {}
func (e *captureEvents) OnConfig(string)                {}
func (e *captureEvents) OnReady()                       {}
func (e *captureEvents) OnStats(int, int, int64, int64) {}
func (e *captureEvents) OnPolicy(int)                   {}

func (e *captureEvents) OnError(code, _ string, fatal bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.code, e.fatal = code, fatal
}

func (e *captureEvents) OnLog(line string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.lines = append(e.lines, line)
}

func (e *captureEvents) logged(sub string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, l := range e.lines {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

// stoppedCore returns a Core whose context is already cancelled: its sleep returns at once, so
// handleCredError answers without waiting out the retry backoff. The cancel func is kept because fatal()
// calls it (cancelling twice is harmless).
func stoppedCore(ev Events) (*Core, context.CancelFunc) {
	c := &Core{ev: ev}
	c.ctx, c.cancel = context.WithCancel(context.Background())
	c.cancel()
	return c, c.cancel
}

// A rate-limited credential failure is logged with the stage code before the retry sleep
// (F-TRANSPORT-0061-1: it used to be silent until a fatal outcome).
func TestCredErrorRateLimitedLogged(t *testing.T) {
	ev := &captureEvents{}
	c, cancel := stoppedCore(ev)
	backoff := time.Second
	if c.handleCredError("call-a", &CredError{Kind: RateLimited, Code: "rate_limited", Message: "service rate limit"}, &backoff) {
		t.Fatal("RateLimited with a cancelled context should stop the worker")
	}
	cancel()
	if !ev.logged("rate_limited") || !ev.logged("retrying") {
		t.Fatalf("no rate-limit line in the log: %v", ev.lines)
	}
}

// The same for a per-step transient failure: the stage code (calls_stepN / alt_stepN) reaches the log.
func TestCredErrorTransientLogged(t *testing.T) {
	ev := &captureEvents{}
	c, cancel := stoppedCore(ev)
	backoff := time.Second
	if c.handleCredError("call-a", &CredError{Kind: Transient, Code: "calls_step3", Message: "call token request failed"}, &backoff) {
		t.Fatal("Transient with a cancelled context should stop the worker")
	}
	cancel()
	if !ev.logged("calls_step3") {
		t.Fatalf("no transient line with the stage code in the log: %v", ev.lines)
	}
}

// A dead identifier is marked so pickCall skips it, and its loss is logged.
func TestCredErrorIdentifierDead(t *testing.T) {
	ev := &captureEvents{}
	c, cancel := stoppedCore(ev)
	backoff := time.Second
	c.handleCredError("call-b", &CredError{Kind: IdentifierDead, Code: "call_unavailable", Message: "call is no longer available"}, &backoff)
	cancel()
	if _, dead := c.deadIDs.Load("call-b"); !dead {
		t.Fatal("call-b not marked dead")
	}
	if !ev.logged("call_unavailable") {
		t.Fatalf("no identifier-dead line in the log: %v", ev.lines)
	}
}

// A plain (non-CredError) stream failure keeps its "stream error" line.
func TestStreamErrorLogged(t *testing.T) {
	ev := &captureEvents{}
	c, cancel := stoppedCore(ev)
	ctx, ctxCancel := context.WithCancel(context.Background())
	c.ctx = ctx
	// Cut the retry sleep short rather than wait the backoff out; the line is written before the sleep.
	time.AfterFunc(50*time.Millisecond, ctxCancel)
	backoff := time.Second
	c.handleCredError("call-a", errors.New("dial timeout"), &backoff)
	cancel()
	ctxCancel()
	if !ev.logged("stream error") {
		t.Fatalf("no stream-error line in the log: %v", ev.lines)
	}
}

// Fatal and Unavailable end the server through a fatal error event, not a retry.
func TestCredErrorFatalEnds(t *testing.T) {
	for _, ce := range []*CredError{
		{Kind: Fatal, Code: "auth_failed", Message: "authentication rejected"},
		{Kind: Unavailable, Code: "no_turn_urls", Message: "no usable TURN addresses"},
	} {
		ev := &captureEvents{}
		c, cancel := stoppedCore(ev)
		backoff := time.Second
		if c.handleCredError("call-a", ce, &backoff) {
			t.Fatalf("%v should stop the worker", ce.Kind)
		}
		cancel()
		if !ev.fatal {
			t.Fatalf("%v produced no fatal error event", ce.Kind)
		}
	}
}
