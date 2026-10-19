package creds

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
)

// Request headers sent with every call; the client profile is fixed.
type browserProfile struct {
	UserAgent       string
	SecChUa         string
	SecChUaMobile   string
	SecChUaPlatform string
}

var defaultProfile = browserProfile{
	UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
	SecChUa:         `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`,
	SecChUaMobile:   "?0",
	SecChUaPlatform: `"Windows"`,
}

// apiClient wraps the HTTP client and a shared cookie jar.
// Safe for concurrent use; one instance per Fetch is fine.
type apiClient struct {
	hc     tlsclient.HttpClient
	ua     browserProfile
	origin string
}

func newAPIClient() (*apiClient, error) {
	hc, err := tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),
		tlsclient.WithTimeoutSeconds(20),
		tlsclient.WithClientProfile(profiles.Chrome_146),
		tlsclient.WithCookieJar(tlsclient.NewCookieJar()),
	)
	if err != nil {
		return nil, fmt.Errorf("tls client: %w", err)
	}
	return &apiClient{hc: hc, ua: defaultProfile, origin: callsWebOrigin}, nil
}

// postForm sends an application/x-www-form-urlencoded POST and decodes JSON.
// requestURL may include a query string; it is used verbatim.
func (c *apiClient) postForm(ctx context.Context, requestURL, body string) (map[string]any, error) {
	return c.do(ctx, requestURL, bytes.NewBufferString(body))
}

// postQuery sends a POST with an empty body and the whole request in the
// query string — used by the preferred path.
func (c *apiClient) postQuery(ctx context.Context, requestURL string) (map[string]any, error) {
	return c.do(ctx, requestURL, bytes.NewReader(nil))
}

func (c *apiClient) do(ctx context.Context, requestURL string, body io.Reader) (map[string]any, error) {
	req, err := fhttp.NewRequestWithContext(ctx, "POST", requestURL, body)
	if err != nil {
		return nil, err
	}
	c.applyHeaders(req)
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes))
	if err != nil {
		return nil, err
	}
	return decodeBody(raw)
}

// maxResponseBytes caps an answer (credential answers are small; anything bigger is not a JSON reply).
const maxResponseBytes = 256 * 1024

// decodeBody parses one JSON object. The body is never included in the error — answers carry tokens.
func decodeBody(raw []byte) (map[string]any, error) {
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("decode: %w", err)
	}
	return out, nil
}

func (c *apiClient) applyHeaders(req *fhttp.Request) {
	req.Header.Set("User-Agent", c.ua.UserAgent)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Origin", c.origin)
	req.Header.Set("Referer", c.origin+"/")
	req.Header.Set("Sec-Fetch-Site", "same-site")
	req.Header.Set("Sec-Fetch-Mode", "cors")
	req.Header.Set("Sec-Fetch-Dest", "empty")
	req.Header.Set("Priority", "u=1, i")
	req.Header.Set("sec-ch-ua", c.ua.SecChUa)
	req.Header.Set("sec-ch-ua-mobile", c.ua.SecChUaMobile)
	req.Header.Set("sec-ch-ua-platform", c.ua.SecChUaPlatform)
}
