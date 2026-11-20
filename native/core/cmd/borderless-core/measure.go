package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
)

// measureDelay asks [url] twice through [inst] and keeps the faster answer: the second reuses the connection, so it is
// the round trip through the server alone. The instance's start is not timed. As AndroidLibXrayLite's measureInstDelay,
// so pings on the Mac mean what they mean on Android.
func measureDelay(ctx context.Context, inst *core.Instance, url string) (int64, error) {
	tr := &http.Transport{
		TLSHandshakeTimeout: 6 * time.Second,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dest, err := xnet.ParseDestination(fmt.Sprintf("%s:%s", network, addr))
			if err != nil {
				return nil, err
			}
			return core.Dial(ctx, inst, dest)
		},
	}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: 12 * time.Second}
	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}
	best := int64(-1)
	var last error
	for i := 0; i < 2; i++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return -1, err
		}
		start := time.Now()
		resp, err := client.Do(req)
		if err != nil {
			last = err
			continue
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusNoContent {
			last = fmt.Errorf("status != 204: %s", resp.Status)
			continue
		}
		if d := time.Since(start).Milliseconds(); best < 0 || d < best {
			best = d
		}
	}
	if best < 0 {
		return -1, last
	}
	return best, nil
}

// takeTraffic is the outbounds' traffic since the last call, "tag,uplink|downlink,bytes;…" (counters reset), as
// AndroidLibXrayLite's queryAllOutboundTrafficStats.
func takeTraffic(inst *core.Instance) string {
	manager, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager)
	if !ok {
		return ""
	}
	var b strings.Builder
	manager.VisitCounters(func(name string, c stats.Counter) bool {
		// "outbound>>>tag>>>traffic>>>uplink"
		parts := strings.Split(name, ">>>")
		if len(parts) == 4 && parts[0] == "outbound" && parts[2] == "traffic" {
			if v := c.Set(0); v > 0 {
				fmt.Fprintf(&b, "%s,%s,%d;", parts[1], parts[3], v)
			}
		}
		return true
	})
	return b.String()
}

// startInstance loads and starts a JSON config.
func startInstance(config []byte) (*core.Instance, error) {
	c, err := core.LoadConfig("json", strings.NewReader(string(config)))
	if err != nil {
		return nil, fmt.Errorf("config: %w", err)
	}
	inst, err := core.New(c)
	if err != nil {
		return nil, fmt.Errorf("core: %w", err)
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return nil, fmt.Errorf("start: %w", err)
	}
	return inst, nil
}
