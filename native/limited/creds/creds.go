// Package creds is the service-facing part of the limited-network core: given a call identifier it returns the TURN
// endpoints that carry the tunnel and the credentials for them. The transport in the parent package knows nothing
// about it.
//
// The package keeps no state beyond a request throttle: caching belongs to the core, which asks again near the
// credentials' TTL and after a TURN authentication failure.
package creds

import (
	"context"
	"fmt"
	"math/rand"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

// Fetch obtains TURN credentials for one call identifier.
//
// It tries the preferred path first and, if that fails, the alternate one with credential rotation. Requests are
// serialized and spaced (the service rate-limits them). A returned FetchError tells the core how to react.
func Fetch(ctx context.Context, callID, deviceID string) (*Credentials, error) {
	if callID == "" {
		return nil, &FetchError{Kind: IdentifierDead, Code: "empty_call_id", Message: "empty call identifier"}
	}
	if deviceID == "" {
		deviceID = uuid.New().String()
	}

	fetchMu.Lock()
	defer fetchMu.Unlock()

	if !lastFetch.IsZero() {
		wait := time.Duration(minFetchInterval)*time.Second + time.Duration(rand.Intn(3000))*time.Millisecond
		if elapsed := time.Since(lastFetch); elapsed < wait {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(wait - elapsed):
			}
		}
	}
	defer func() { lastFetch = time.Now() }()

	creds, err := preferredPath(ctx, callID, deviceID)
	if err == nil {
		return creds, nil
	}
	// A dead identifier cannot be helped by the other path; other failures are worth one try through it.
	if isDead(err) {
		return nil, err
	}

	alt, altErr := alternatePath(ctx, callID, deviceID)
	if altErr == nil {
		return alt, nil
	}
	return nil, preferError(err, altErr)
}

// The two credential paths are variables so tests can substitute them.
var (
	preferredPath = fetchViaCalls
	alternatePath = fetchViaAlternate

	fetchMu   sync.Mutex
	lastFetch time.Time
)

// -- preferred path -----------------------------------------------------------

// fetchViaCalls is the preferred path: five requests — a token, the call info (user id and secret), a call token,
// a session login, and a join whose answer carries the TURN credentials.
func fetchViaCalls(ctx context.Context, callID, deviceID string) (*Credentials, error) {
	cl, err := newAPIClient()
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "tls_init", Message: "TLS client init failed"}
	}
	link := callsJoinBase + callID
	name := pickName()

	// Step 1: token.
	r1, err := cl.postQuery(ctx, callsTokenURL(link, deviceID, name))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "calls_step1", Message: "token request failed"}
	}
	token, ok := strAt(r1, "response", "token")
	if !ok {
		return nil, classifyCallsError(r1, "calls_step1")
	}

	// Step 2: call info.
	r2, err := cl.postQuery(ctx, callsPreviewURL(token, deviceID, link))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "calls_step2", Message: "call info request failed"}
	}
	if e := apiError(r2); e != nil {
		return nil, e
	}
	userIDf, ok := floatAt(r2, "response", "user_id")
	if !ok {
		return nil, &FetchError{Kind: Transient, Code: "calls_step2_parse", Message: "missing user_id"}
	}
	userID := fmt.Sprintf("%.0f", userIDf)
	secret, ok := strAt(r2, "response", "secret")
	if !ok {
		return nil, &FetchError{Kind: Transient, Code: "calls_step2_parse", Message: "missing secret"}
	}

	// Step 3: call token.
	r3, err := cl.postQuery(ctx, callsCallTokenURL(token, deviceID, link, name, userID, secret))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "calls_step3", Message: "call token request failed"}
	}
	if e := apiError(r3); e != nil {
		return nil, e
	}
	callToken, ok := strAt(r3, "response", "token")
	if !ok {
		return nil, &FetchError{Kind: Transient, Code: "calls_step3_parse", Message: "missing call token"}
	}

	// Step 4: session login.
	sessionData := fmt.Sprintf("{\"version\":2,\"device_id\":\"%s\",\"client_version\":\"1.0.1\"}", uuid.New().String())
	r4, err := cl.postQuery(ctx, cdnSessionURL(sessionData))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "calls_step4", Message: "session login failed"}
	}
	sessionKey, ok := strAt(r4, "session_key")
	if !ok {
		if e := apiError(r4); e != nil {
			return nil, e
		}
		return nil, &FetchError{Kind: Transient, Code: "calls_step4_parse", Message: "missing session_key"}
	}

	// Step 5: join.
	r5, err := cl.postQuery(ctx, cdnJoinURL(callID, callToken, sessionKey))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "calls_step5", Message: "join request failed"}
	}
	return extractTurnCreds(r5, "calls_step5")
}

// -- alternate path -----------------------------------------------------------

// fetchViaAlternate is the fallback path: login, call info, token, session login and join, rotating the client
// credentials on failure.
func fetchViaAlternate(ctx context.Context, callID, deviceID string) (*Credentials, error) {
	var lastErr error
	for attempt := 0; attempt < 4; attempt++ {
		cred := alternateCreds[attempt%len(alternateCreds)]
		creds, err := alternateTokenChain(ctx, callID, deviceID, cred)
		if err == nil {
			return creds, nil
		}
		lastErr = err
		if isDead(err) || isFatal(err) {
			return nil, err
		}
		wait := time.Duration(900+rand.Intn(900)) * time.Millisecond
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(wait):
		}
	}
	return nil, lastErr
}

func alternateTokenChain(ctx context.Context, callID, deviceID string, cred appCreds) (*Credentials, error) {
	cl, err := newAPIClient()
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "tls_init", Message: "TLS client init failed"}
	}
	name := pickName()
	link := callsJoinBase + callID

	// Step 1: access token.
	r1, err := cl.postForm(ctx, callsLoginURL, alternateLoginBody(cred))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "alt_step1", Message: "login failed"}
	}
	if e := apiError(r1); e != nil {
		return nil, e
	}
	dataMap, ok := r1["data"].(map[string]any)
	if !ok {
		return nil, &FetchError{Kind: Transient, Code: "alt_step1_parse", Message: "missing data"}
	}
	accessToken, ok := dataMap["access_token"].(string)
	if !ok || accessToken == "" {
		return nil, &FetchError{Kind: Transient, Code: "alt_step1_parse", Message: "missing access_token"}
	}
	sleepJitter(100, 150)

	// Step 2: call info.
	r2, err := cl.postForm(ctx, alternatePreviewURL(cred), alternatePreviewBody(link, accessToken))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "alt_step2", Message: "call info request failed"}
	}
	if e := apiError(r2); e != nil {
		return nil, e
	}
	sleepJitter(200, 400)

	// Step 3: token for the call.
	r3, err := cl.postForm(ctx, alternateTokenURL(cred), alternateTokenBody(link, name, accessToken))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "alt_step3", Message: "token request failed"}
	}
	if e := apiError(r3); e != nil {
		return nil, e
	}
	callToken, ok := strAt(r3, "response", "token")
	if !ok {
		return nil, &FetchError{Kind: Transient, Code: "alt_step3_parse", Message: "missing call token"}
	}
	sleepJitter(100, 150)

	// Step 4: session login.
	sessionData := fmt.Sprintf("{\"version\":2,\"device_id\":\"%s\",\"client_version\":1.1,\"client_type\":\"SDK_JS\"}", uuid.New().String())
	r4, err := cl.postForm(ctx, callsCDNURL, cdnSessionBody(sessionData))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "alt_step4", Message: "session login failed"}
	}
	sessionKey, ok := strAt(r4, "session_key")
	if !ok {
		if e := apiError(r4); e != nil {
			return nil, e
		}
		return nil, &FetchError{Kind: Transient, Code: "alt_step4_parse", Message: "missing session_key"}
	}
	sleepJitter(100, 150)

	// Step 5: join.
	r5, err := cl.postForm(ctx, callsCDNURL, alternateJoinBody(callID, callToken, sessionKey))
	if err != nil {
		return nil, &FetchError{Kind: Transient, Code: "alt_step5", Message: "join request failed"}
	}
	return extractTurnCreds(r5, "alt_step5")
}

// -- request URLs and bodies (pure, unit-tested) ------------------------------

func q(s string) string { return url.QueryEscape(s) }

func callsTokenURL(link, deviceID, name string) string {
	return fmt.Sprintf("https://%s/method/auth.getAnonymToken?v=%s&client_id=%s&link=%s&device_id=%s&anonymName=%s&lang=en",
		callsAPIHost, callsAPIVersion, callsClientID, q(link), q(deviceID), q(name))
}

func callsPreviewURL(token, deviceID, link string) string {
	return fmt.Sprintf("https://%s/method/messages.getCallPreview?v=%s&anonymous_token=%s&device_id=%s"+
		"&extended=1&fields=first_name,last_name,photo_200&lang=en&link=%s",
		callsAPIHost, callsAPIVersion, q(token), q(deviceID), q(link))
}

func callsCallTokenURL(token, deviceID, link, name, userID, secret string) string {
	return fmt.Sprintf("https://%s/method/messages.getAnonymCallToken?v=%s&anonymous_token=%s&device_id=%s"+
		"&link=%s&name=%s&user_id=%s&secret=%s&lang=en",
		callsAPIHost, callsAPIVersion, q(token), q(deviceID), q(link), q(name), q(userID), q(secret))
}

func cdnSessionURL(sessionData string) string {
	return callsCDNURL + "?session_data=" + q(sessionData) + "&method=auth.anonymLogin&format=JSON&application_key=" + callsCDNKey
}

func cdnJoinURL(callID, token, sessionKey string) string {
	return callsCDNURL + "?joinLink=" + q(callID) + "&isVideo=false&protocolVersion=5&anonymToken=" + q(token) +
		"&method=vchat.joinConversationByLink&format=JSON&application_key=" + callsCDNKey + "&session_key=" + q(sessionKey)
}

func alternateLoginBody(c appCreds) string {
	return fmt.Sprintf("client_id=%s&token_type=messages&client_secret=%s&version=1&app_id=%s",
		c.ClientID, c.ClientSecret, c.ClientID)
}

func alternatePreviewURL(c appCreds) string {
	return fmt.Sprintf("%scalls.getCallPreview?v=%s&client_id=%s", callsAPIBase, apiVersion, c.ClientID)
}

func alternatePreviewBody(link, accessToken string) string {
	return "vk_join_link=" + q(link) + "&fields=photo_200&access_token=" + q(accessToken)
}

func alternateTokenURL(c appCreds) string {
	return fmt.Sprintf("%scalls.getAnonymousToken?v=%s&client_id=%s", callsAPIBase, apiVersion, c.ClientID)
}

func alternateTokenBody(link, name, accessToken string) string {
	return "vk_join_link=" + q(link) + "&name=" + q(name) + "&access_token=" + q(accessToken)
}

func cdnSessionBody(sessionData string) string {
	return "session_data=" + q(sessionData) + "&method=auth.anonymLogin&format=JSON&application_key=" + callsCDNKey
}

func alternateJoinBody(callID, token, sessionKey string) string {
	return "joinLink=" + q(callID) + "&isVideo=false&protocolVersion=5&capabilities=2F7F&anonymToken=" + q(token) +
		"&method=vchat.joinConversationByLink&format=JSON&application_key=" + callsCDNKey + "&session_key=" + q(sessionKey)
}

// -- decoding and error classification (pure, unit-tested) --------------------

// extractTurnCreds reads the TURN endpoints, username, password and TTL out of a join answer. Endpoints may come as
// "turn:"/"turns:" URIs or plain host:port; duplicates are dropped here, the transport reduces them further.
func extractTurnCreds(resp map[string]any, tag string) (*Credentials, error) {
	if e := apiError(resp); e != nil {
		return nil, e
	}
	ts, ok := resp["turn_server"].(map[string]any)
	if !ok {
		return nil, &FetchError{Kind: Transient, Code: tag + "_parse", Message: "missing turn_server"}
	}
	user, _ := ts["username"].(string)
	pass, _ := ts["credential"].(string)
	var urls []string
	switch u := ts["urls"].(type) {
	case []any:
		for _, v := range u {
			if s, ok := v.(string); ok {
				urls = append(urls, s)
			}
		}
	case string:
		urls = append(urls, u)
	}
	if user == "" || pass == "" || len(urls) == 0 {
		return nil, &FetchError{Kind: Unavailable, Code: "no_turn_urls", Message: "incomplete TURN credentials"}
	}
	ttl := 0
	if v, ok := ts["ttl"].(float64); ok && v > 0 {
		ttl = int(v)
	}
	var addrs []string
	seen := map[string]bool{}
	for _, s := range urls {
		clean := strings.TrimSpace(strings.Split(s, "?")[0])
		clean = strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
		if clean == "" || seen[clean] {
			continue
		}
		seen[clean] = true
		addrs = append(addrs, clean)
	}
	if len(addrs) == 0 {
		return nil, &FetchError{Kind: Unavailable, Code: "no_turn_urls", Message: "no usable TURN addresses"}
	}
	return &Credentials{TurnURLs: addrs, Username: user, Password: pass, TTL: ttl}, nil
}

func strAt(m map[string]any, keys ...string) (string, bool) {
	var cur any = m
	for _, k := range keys {
		mm, ok := cur.(map[string]any)
		if !ok {
			return "", false
		}
		cur = mm[k]
	}
	s, ok := cur.(string)
	return s, ok
}

func floatAt(m map[string]any, keys ...string) (float64, bool) {
	var cur any = m
	for _, k := range keys {
		mm, ok := cur.(map[string]any)
		if !ok {
			return 0, false
		}
		cur = mm[k]
	}
	f, ok := cur.(float64)
	return f, ok
}

// apiError turns the service's error object (or a top-level error code) into a FetchError.
func apiError(resp map[string]any) error {
	if resp == nil {
		return nil
	}
	if errObj, ok := resp["error"].(map[string]any); ok {
		code, _ := errObj["error_code"].(float64)
		msg, _ := errObj["error_msg"].(string)
		return classifyError(int(code), msg)
	}
	if code, ok := resp["error_code"].(float64); ok && code != 0 {
		return classifyError(int(code), "")
	}
	return nil
}

func classifyCallsError(resp map[string]any, step string) error {
	if e := apiError(resp); e != nil {
		return e
	}
	return &FetchError{Kind: Transient, Code: step + "_parse", Message: "malformed response"}
}

// classifyError maps the service's numeric error codes to the kinds the transport knows how to react to.
func classifyError(code int, msg string) error {
	_ = msg
	switch {
	case code == 5:
		return &FetchError{Kind: Fatal, Code: "auth_failed", Message: "authentication rejected"}
	case code == 6 || code == 9 || code == 29:
		return &FetchError{Kind: RateLimited, Code: "rate_limited", Message: "service rate limit"}
	case code == 14:
		return &FetchError{Kind: RateLimited, Code: "verification_required", Message: "verification required"}
	case code == 951 || code == 954 || (code >= 9000 && code <= 9999):
		return &FetchError{Kind: IdentifierDead, Code: "call_unavailable", Message: "call is no longer available"}
	}
	return &FetchError{
		Kind:    Transient,
		Code:    fmt.Sprintf("api_error_%d", code),
		Message: "service returned an error",
	}
}

// preferError keeps the more informative failure when both credential paths fail.
func preferError(a, b error) error {
	if a == nil {
		return b
	}
	if b == nil {
		return a
	}
	if errorSeverity(b) > errorSeverity(a) {
		return b
	}
	return a
}

func errorSeverity(err error) int {
	fe, ok := err.(*FetchError)
	if !ok {
		return 0
	}
	switch fe.Kind {
	case Fatal:
		return 5
	case IdentifierDead:
		return 4
	case Unavailable:
		return 3
	case RateLimited:
		return 2
	default:
		return 1
	}
}

func isDead(err error) bool {
	fe, ok := err.(*FetchError)
	return ok && fe.Kind == IdentifierDead
}

func isFatal(err error) bool {
	fe, ok := err.(*FetchError)
	return ok && fe.Kind == Fatal
}

func sleepJitter(minMs, maxMs int) {
	ms := minMs + rand.Intn(maxMs-minMs+1)
	time.Sleep(time.Duration(ms) * time.Millisecond)
}
