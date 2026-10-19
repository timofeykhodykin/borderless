package creds

import (
	"context"
	"errors"
	"net/url"
	"strings"
	"testing"
)

func TestFetchRejectsEmptyCallID(t *testing.T) {
	if _, err := Fetch(context.Background(), "", "dev"); !isDead(err) {
		t.Fatalf("empty call id → %v, want IdentifierDead", err)
	}
}

func TestClassifyError(t *testing.T) {
	cases := []struct {
		code int
		kind ErrorKind
	}{
		{5, Fatal},
		{6, RateLimited}, {9, RateLimited}, {29, RateLimited},
		{14, RateLimited},
		{951, IdentifierDead}, {954, IdentifierDead}, {9001, IdentifierDead}, {9999, IdentifierDead},
		{3, Transient}, {0, Transient},
	}
	for _, c := range cases {
		err := classifyError(c.code, "ignored")
		var fe *FetchError
		if !errors.As(err, &fe) {
			t.Fatalf("code %d: %T is not *FetchError", c.code, err)
		}
		if fe.Kind != c.kind {
			t.Fatalf("code %d → kind %d, want %d", c.code, fe.Kind, c.kind)
		}
		if fe.Message == "" || fe.Code == "" {
			t.Fatalf("code %d: empty code/message: %#v", c.code, fe)
		}
	}
}

func TestAPIErrorShapes(t *testing.T) {
	if err := apiError(nil); err != nil {
		t.Fatalf("nil response → %v", err)
	}
	if err := apiError(map[string]any{"response": map[string]any{}}); err != nil {
		t.Fatalf("no error → %v", err)
	}
	nested := map[string]any{"error": map[string]any{"error_code": float64(14), "error_msg": "verification"}}
	var fe *FetchError
	if err := apiError(nested); !errors.As(err, &fe) || fe.Kind != RateLimited || fe.Code != "verification_required" {
		t.Fatalf("nested 14 → %v", err)
	}
	if err := apiError(map[string]any{"error_code": float64(951)}); !isDead(err) {
		t.Fatalf("top-level 951 → %v, want IdentifierDead", err)
	}
}

func TestExtractTurnCreds(t *testing.T) {
	resp := map[string]any{
		"turn_server": map[string]any{
			"username":   "user",
			"credential": "pass",
			"ttl":        float64(600),
			"urls": []any{
				"turn:198.51.100.7:3478?transport=udp",
				"turn:198.51.100.7:3478?transport=udp",
				"turns:198.51.100.8:5349",
				"203.0.113.4:19302",
			},
		},
	}
	c, err := extractTurnCreds(resp, "t")
	if err != nil {
		t.Fatal(err)
	}
	if c.Username != "user" || c.Password != "pass" || c.TTL != 600 {
		t.Fatalf("credentials = %#v", c)
	}
	if len(c.TurnURLs) != 3 || c.TurnURLs[0] != "198.51.100.7:3478" || c.TurnURLs[2] != "203.0.113.4:19302" {
		t.Fatalf("URLs = %v", c.TurnURLs)
	}

	// A single URL as a string is accepted too; a missing TTL is 0 (unknown).
	single := map[string]any{"turn_server": map[string]any{
		"username": "u", "credential": "p", "urls": "turn:203.0.113.9:3478",
	}}
	if c, err := extractTurnCreds(single, "t"); err != nil || len(c.TurnURLs) != 1 || c.TurnURLs[0] != "203.0.113.9:3478" || c.TTL != 0 {
		t.Fatalf("single URL → %#v, %v", c, err)
	}

	for name, bad := range map[string]map[string]any{
		"no turn_server": {},
		"no username":    {"turn_server": map[string]any{"credential": "p", "urls": []any{"turn:a:1"}}},
		"no password":    {"turn_server": map[string]any{"username": "u", "urls": []any{"turn:a:1"}}},
		"no urls":        {"turn_server": map[string]any{"username": "u", "credential": "p"}},
		"empty urls":     {"turn_server": map[string]any{"username": "u", "credential": "p", "urls": []any{"", "turn:"}}},
	} {
		if _, err := extractTurnCreds(bad, "t"); err == nil {
			t.Fatalf("%s: expected an error", name)
		}
	}
}

func TestDecodeBodyNeverEchoesTheAnswer(t *testing.T) {
	got, err := decodeBody([]byte(`{"a":1}`))
	if err != nil || got["a"].(float64) != 1 {
		t.Fatalf("decode → %#v, %v", got, err)
	}
	secret := "token-SECRET"
	_, err = decodeBody([]byte("<html>" + secret + "</html>"))
	if err == nil {
		t.Fatal("invalid JSON must fail")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("error leaks the body: %v", err)
	}
}

func TestEncodedDataDecodes(t *testing.T) {
	if callsAPIHost == "" || callsClientID == "" || callsCDNKey == "" {
		t.Fatal("encoded endpoint values did not decode")
	}
	for name, s := range map[string]string{
		"cdn": callsCDNURL, "login": callsLoginURL, "api": callsAPIBase, "origin": callsWebOrigin, "join": callsJoinBase,
	} {
		if !strings.HasPrefix(s, "https://") || strings.Contains(s, "\x00") {
			t.Fatalf("%s endpoint did not decode: %q", name, s)
		}
	}
	if len(maleFirstNames) < 10 || len(femaleFirstNames) < 10 || len(lastNames) < 10 {
		t.Fatalf("name data did not decode: %d/%d/%d", len(maleFirstNames), len(femaleFirstNames), len(lastNames))
	}
	for i := 0; i < 50; i++ {
		if n := pickName(); n == "" {
			t.Fatal("empty name")
		}
	}
}

func TestDecodeRejectsGarbage(t *testing.T) {
	if decode("not base64!!!") != "" {
		t.Fatal("garbage must decode to an empty string")
	}
	if decodeList("") != nil {
		t.Fatal("empty data must decode to no names")
	}
}

func TestPickName(t *testing.T) {
	for i := 0; i < 200; i++ {
		n := pickName()
		if n == "" || strings.TrimSpace(n) != n || len(n) > 40 {
			t.Fatalf("name %q is not a clean display name", n)
		}
	}
}

func TestRequestBuildersEscapeEverything(t *testing.T) {
	link := "https://example.test/call/join/a&b?c=d"
	name := "Ann Lee"
	token := "t+/="
	session := "s+/= &x"

	got := callsTokenURL(link, "dev-1", name)
	for _, raw := range []string{link, name} {
		if strings.Contains(got, raw) {
			t.Fatalf("callsTokenURL left %q unescaped: %s", raw, got)
		}
	}
	if !strings.Contains(got, "link="+url.QueryEscape(link)) || !strings.Contains(got, "anonymName="+url.QueryEscape(name)) {
		t.Fatalf("callsTokenURL = %s", got)
	}

	for label, s := range map[string]string{
		"cdnJoinURL":        cdnJoinURL("a&b", token, session),
		"alternateJoinBody": alternateJoinBody("a&b", token, session),
	} {
		for _, want := range []string{"joinLink=a%26b", "anonymToken=" + url.QueryEscape(token), "session_key=" + url.QueryEscape(session)} {
			if !strings.Contains(s, want) {
				t.Fatalf("%s missing %q: %s", label, want, s)
			}
		}
	}

	if preview := alternatePreviewBody(link, token); !strings.Contains(preview, "vk_join_link="+url.QueryEscape(link)) {
		t.Fatalf("alternatePreviewBody = %s", preview)
	}
	if body := cdnSessionBody(session); !strings.Contains(body, "session_data="+url.QueryEscape(session)) {
		t.Fatalf("cdnSessionBody = %s", body)
	}
	if u := cdnSessionURL(session); !strings.Contains(u, "session_data="+url.QueryEscape(session)) {
		t.Fatalf("cdnSessionURL = %s", u)
	}
}
