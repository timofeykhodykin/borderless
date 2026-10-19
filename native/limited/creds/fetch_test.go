package creds

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestPreferErrorKeepsTheMoreInformativeOne(t *testing.T) {
	transient := &FetchError{Kind: Transient, Code: "t", Message: "m"}
	limited := &FetchError{Kind: RateLimited, Code: "r", Message: "m"}
	fatal := &FetchError{Kind: Fatal, Code: "f", Message: "m"}
	if got := preferError(transient, limited); got != limited {
		t.Fatalf("transient+rate-limited → %v, want the rate-limited one", got)
	}
	if got := preferError(fatal, transient); got != fatal {
		t.Fatalf("fatal+transient → %v, want the fatal one", got)
	}
	if got := preferError(nil, transient); got != transient {
		t.Fatalf("nil+transient → %v", got)
	}
	if got := preferError(transient, nil); got != transient {
		t.Fatalf("transient+nil → %v", got)
	}
}

func TestFetchUsesBothPaths(t *testing.T) {
	pref, alt, savedLast := preferredPath, alternatePath, lastFetch
	defer func() { preferredPath, alternatePath, lastFetch = pref, alt, savedLast }()

	// Each call starts without a throttle wait (Fetch records the time of every attempt).
	fetch := func() (*Credentials, error) {
		lastFetch = time.Time{}
		return Fetch(context.Background(), "call", "dev")
	}

	var altCalls int
	alternatePath = func(context.Context, string, string) (*Credentials, error) {
		altCalls++
		return nil, &FetchError{Kind: RateLimited, Code: "alt", Message: "m"}
	}
	preferredPath = func(context.Context, string, string) (*Credentials, error) {
		return nil, &FetchError{Kind: Transient, Code: "pref", Message: "m"}
	}

	// Both paths fail: the more informative error wins (rate-limited over transient).
	var fe *FetchError
	if _, err := fetch(); !errors.As(err, &fe) || fe.Code != "alt" {
		t.Fatalf("both fail → %v, want the alternate path's error", err)
	}
	if altCalls != 1 {
		t.Fatalf("alternate path called %d times, want 1", altCalls)
	}

	// The alternate path is used when the preferred one fails transiently, and its result is returned.
	alternatePath = func(context.Context, string, string) (*Credentials, error) {
		return &Credentials{TurnURLs: []string{"203.0.113.1:3478"}, Username: "u", Password: "p"}, nil
	}
	if c, err := fetch(); err != nil || len(c.TurnURLs) != 1 {
		t.Fatalf("fallback → %#v, %v", c, err)
	}

	// A dead identifier is returned at once: the other path cannot revive it.
	altCalls = 0
	preferredPath = func(context.Context, string, string) (*Credentials, error) {
		return nil, &FetchError{Kind: IdentifierDead, Code: "dead", Message: "m"}
	}
	if _, err := fetch(); !isDead(err) || altCalls != 0 {
		t.Fatalf("dead → %v, alternate calls %d; want no fallback", err, altCalls)
	}

	// A rejected client credential still gets one try through the alternate path, whose credentials differ; the fatal
	// error wins if both fail.
	altCalls = 0
	alternatePath = func(context.Context, string, string) (*Credentials, error) {
		altCalls++
		return nil, &FetchError{Kind: Transient, Code: "alt", Message: "m"}
	}
	preferredPath = func(context.Context, string, string) (*Credentials, error) {
		return nil, &FetchError{Kind: Fatal, Code: "fatal", Message: "m"}
	}
	if _, err := fetch(); !isFatal(err) || altCalls != 1 {
		t.Fatalf("fatal → %v, alternate calls %d; want one fallback and a fatal error", err, altCalls)
	}
}
