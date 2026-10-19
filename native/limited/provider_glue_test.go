package limited

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"app.borderless/limited/creds"
)

func TestTurnAddrs(t *testing.T) {
	got := turnAddrs([]string{
		"turn:198.51.100.7:3478?transport=tcp",
		" turn:198.51.100.7:3478?transport=udp ",
		"turns:198.51.100.8:5349?transport=tcp",
		"203.0.113.4:19302",
		"turn:203.0.113.4:19302",
		"turn:no-port",
		"",
	})
	want := []string{"198.51.100.7:3478", "203.0.113.4:19302", "198.51.100.8:5349"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("turnAddrs = %v, want %v", got, want)
	}
}

func TestProviderRegistered(t *testing.T) {
	if _, ok := provider.(*credentialProvider); !ok {
		t.Fatalf("registered provider = %T, want *credentialProvider", provider)
	}
}

func TestFetchErrorMapping(t *testing.T) {
	kinds := map[creds.ErrorKind]CredErrorKind{
		creds.Transient:      Transient,
		creds.RateLimited:    RateLimited,
		creds.IdentifierDead: IdentifierDead,
		creds.Unavailable:    Unavailable,
		creds.Fatal:          Fatal,
	}
	for in, want := range kinds {
		err := mapFetchError(&creds.FetchError{Kind: in, Code: "c", Message: "m"})
		var ce *CredError
		if !errors.As(err, &ce) || ce.Kind != want || ce.Code != "c" || ce.Message != "m" {
			t.Fatalf("kind %d → %#v, want kind %d", in, err, want)
		}
	}
	plain := errors.New("plain")
	if mapFetchError(plain) != plain {
		t.Fatal("a plain error must pass through unchanged")
	}
}

type stubProvider struct {
	calls int
	urls  []string
}

func (s *stubProvider) Fetch(_ context.Context, _, _ string) (*TurnCreds, error) {
	s.calls++
	return &TurnCreds{URLs: s.urls, Username: "u", Password: "p", TTL: 600}, nil
}

// The core caches per call id, hands the transport plain host:port and asks again after invalidate (TURN auth failure).
func TestCoreCredsUsesProviderOutput(t *testing.T) {
	saved := provider
	defer func() { provider = saved }()
	stub := &stubProvider{urls: []string{"turn:198.51.100.7:3478?transport=udp"}}
	provider = stub

	c := &Core{credCache: map[string]*credEntry{}, instance: "i"}
	for i := 0; i < 2; i++ {
		tc, err := c.creds(context.Background(), "call")
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(tc.URLs, []string{"turn:198.51.100.7:3478?transport=udp"}) {
			t.Fatalf("URLs = %v", tc.URLs)
		}
	}
	if stub.calls != 1 {
		t.Fatalf("provider called %d times, want 1 (cached)", stub.calls)
	}
	c.invalidate("call")
	if _, err := c.creds(context.Background(), "call"); err != nil || stub.calls != 2 {
		t.Fatalf("after invalidate: calls %d, err %v", stub.calls, err)
	}

	stub.urls = []string{"turn:bad:address:here"}
	c.invalidate("call")
	_, err := c.creds(context.Background(), "call")
	var ce *CredError
	if !errors.As(err, &ce) || ce.Code != "no_creds" {
		t.Fatalf("unusable URLs → %v, want no_creds", err)
	}
}
