package limited

import (
	"testing"
)

func TestParseTurnEndpoint(t *testing.T) {
	cases := []struct {
		raw  string
		want turnEndpoint
	}{
		{"turn:198.51.100.7:3478?transport=udp", turnEndpoint{"198.51.100.7", "3478", turnUDP}},
		{"turn:198.51.100.7:443?transport=tcp", turnEndpoint{"198.51.100.7", "443", turnTCP}},
		{"turns:turn.example:5349?transport=tcp", turnEndpoint{"turn.example", "5349", turnTLS}},
		{"turn:turn.example", turnEndpoint{"turn.example", "3478", turnUDP}},
		{"turns:[2001:db8::1]", turnEndpoint{"2001:db8::1", "5349", turnTLS}},
		{"203.0.113.4:19302", turnEndpoint{"203.0.113.4", "19302", turnUDP}},
	}
	for _, c := range cases {
		got, err := parseTurnEndpoint(c.raw)
		if err != nil || got != c.want {
			t.Errorf("parseTurnEndpoint(%q) = %+v, %v; want %+v", c.raw, got, err, c.want)
		}
	}
	if ep, err := parseTurnEndpoint("turn:turn.example"); err != nil || ep.Port != "3478" {
		t.Errorf("a bare host must get the default port: %+v, %v", ep, err)
	}
}

func TestTurnCandidatesFallbackOrder(t *testing.T) {
	got := turnCandidates([]string{
		"turn:198.51.100.7:3478?transport=udp",
		"turns:turn.example:443?transport=tcp",
	}, 0)
	want := []turnEndpoint{
		{"198.51.100.7", "3478", turnUDP},
		{"turn.example", "443", turnTLS},
		{"198.51.100.7", "3478", turnTCP},
		{"turn.example", "443", turnTCP},
	}
	if len(got) != len(want) {
		t.Fatalf("candidates = %+v, want %+v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("candidate %d = %+v, want %+v", i, got[i], want[i])
		}
	}
	// The same endpoint is not repeated when it appears in two URLs.
	again := turnCandidates([]string{"turn:198.51.100.7:3478", "198.51.100.7:3478"}, 0)
	if len(again) != 2 {
		t.Errorf("dedup = %+v, want UDP and TCP only", again)
	}
}

func TestTurnURLsKeepTransport(t *testing.T) {
	got := turnURLs([]string{
		"turn:198.51.100.7:3478?transport=tcp",
		"turn:198.51.100.7:3478?transport=tcp",
		"turns:turn.example:443?transport=tcp",
		"turn:bad:address:here",
		"",
	})
	want := []string{"turn:198.51.100.7:3478?transport=tcp", "turns:turn.example:443?transport=tcp"}
	if len(got) != len(want) {
		t.Fatalf("turnURLs = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("turnURLs[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestPolicyMax(t *testing.T) {
	for _, c := range []struct {
		resp string
		n    int
		ok   bool
	}{
		{"POLICY:max_workers=9", 9, true},
		{"POLICY:max_workers=36", 36, true},
		{"POLICY:max_workers=0", 0, false},
		{"POLICY:max_workers=none", 0, false},
		{"NOCONF", 0, false},
	} {
		n, ok := policyMax(c.resp)
		if n != c.n || ok != c.ok {
			t.Errorf("policyMax(%q) = %d, %v; want %d, %v", c.resp, n, ok, c.n, c.ok)
		}
	}
}
