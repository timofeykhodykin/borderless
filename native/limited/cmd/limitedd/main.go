// Command limitedd runs the native limited-network core of this module, in its own process.
//
// Why a process and not a gomobile .aar: Android ships Xray as a gomobile library (libv2ray — one Go runtime built
// into libgojni.so together with the Java classes go.Seq/go.Universe), and an app can hold only one gomobile library:
// a second one adds a duplicate go.Seq and a second libgojni.so, and a second Go runtime in one process. So the core
// runs as a self-contained executable (pure Go, no cgo) started by the app. It is first-party: the transport is ours,
// the credential source (creds package) is part of the same module, nothing is GPL. The app counts the process's CPU
// time into its energy model (core/Limited.kt) and models its radio from STATS.
//
// Protocol (one line per message, UTF-8):
//
//	stdout  __LIMITED_EVENT__|<TYPE>|<json payload>
//	        LISTEN {"port":1234}                 the local UDP port actually bound
//	        CONFIG {"config":"[Interface]…"}     the WireGuard config (has the private key: never logged or written)
//	        READY {}                             the first stream carries traffic
//	        STATS {"active":2,"wanted":2,"bytes_up":…,"bytes_down":…}
//	        POLICY {"max_workers":9}             the server's stream limit
//	        ERROR {"code":"…","message":"…","fatal":true|false}
//	stdin   STREAMS <n>                          how many streams should be connected
//	        KEEPALIVE <seconds> <binding seconds> keepalive / TURN binding refresh intervals
//	        STOP                                 shut down (stdin closing does the same)
//
// `limitedd direct-first …` runs the direct-first dialer instead (see directfirst.go).
//
// Detailed log lines (English, neutral) go to stderr, only with -verbose; the app reads both streams as one.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"app.borderless/limited"
	"app.borderless/limited/creds"
)

var (
	peer       = flag.String("peer", "", "server address, host:port (or the LIMITED_PEER environment variable)")
	calls      = flag.String("calls", "", "call identifiers, comma-separated (or LIMITED_CALLS)")
	password   = flag.String("password", "", "server connection password (or LIMITED_PASSWORD)")
	deviceID   = flag.String("device-id", "", "server-side device id (or LIMITED_DEVICE_ID)")
	instance   = flag.String("instance", "", "stable per-server id passed to the credential provider (or LIMITED_INSTANCE)")
	listenAddr = flag.String("listen", "127.0.0.1:0", "local address to carry UDP on")
	media      = flag.String("media", "audio", "RTP media mode: audio or video")
	maxStreams = flag.Int("n", 9, "upper bound of streams")
	active     = flag.Int("active", 1, "streams connected at start")
	keepalive  = flag.Int("keepalive", 15, "keepalive through each stream, seconds")
	bindingSEC = flag.Int("binding", 10, "TURN binding refresh, seconds")
	statsSEC   = flag.Int("stats", 3, "stats event interval, seconds")
	verbose    = flag.Bool("verbose", false, "write the core's detailed log to stderr")
	watch      = flag.Bool("watch-parent", true, "exit when the parent process is gone")
	dns        = flag.String("dns", "", "resolvers to use, comma-separated IPs (the app passes the network's own)")
	iface      = flag.String("iface", "", "bind every outbound socket to this interface (macOS: the tunnel takes all routes)")
	uid        = flag.Int("uid", -1, "drop to this user id before anything else (required when started as root)")
	gid        = flag.Int("gid", -1, "drop to this group id")
	lifeline   = flag.Int("lifeline", -1, "fd whose end-of-file means the service is gone: stop")
)

// events writes the app protocol on stdout and the detailed log on stderr, and ends the run on a fatal error,
// on STOP, or when stdin closes (the app is gone).
type events struct {
	mu      sync.Mutex
	out     *bufio.Writer
	log     *bufio.Writer
	verbose bool
	done    chan struct{}
	once    sync.Once
}

func (e *events) emit(t string, payload any) {
	var body []byte
	if payload != nil {
		var err error
		if body, err = json.Marshal(payload); err != nil {
			e.debugf("event %s: %v", t, err)
			return
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	fmt.Fprintf(e.out, "__LIMITED_EVENT__|%s|%s\n", t, body)
	_ = e.out.Flush()
}

func (e *events) debugf(format string, a ...any) {
	if !e.verbose {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	fmt.Fprintf(e.log, format+"\n", a...)
	_ = e.log.Flush()
}

func (e *events) OnListen(port int) { e.emit("LISTEN", map[string]any{"port": port}) }

func (e *events) OnConfig(conf string) {
	// The config carries the private key: it goes out as an event only, never to the log.
	e.emit("CONFIG", map[string]any{"config": conf})
	e.debugf("config received (%d bytes)", len(conf))
}

func (e *events) OnReady() { e.emit("READY", nil) }

func (e *events) OnStats(activeN, wanted int, bytesUp, bytesDown int64) {
	e.emit("STATS", map[string]any{
		"active":     activeN,
		"wanted":     wanted,
		"bytes_up":   bytesUp,
		"bytes_down": bytesDown,
		"cpu_ms":     cpuMillis(),
	})
}

func (e *events) OnPolicy(maxWorkers int) {
	e.emit("POLICY", map[string]any{"max_workers": maxWorkers})
}

func (e *events) OnError(code, message string, fatal bool) {
	e.emit("ERROR", map[string]any{"code": code, "message": message, "fatal": fatal})
	e.debugf("error %s: %s (fatal=%v)", code, message, fatal)
	if fatal {
		e.stop()
	}
}

func (e *events) OnLog(line string) { e.debugf("%s", line) }

func (e *events) stop() { e.once.Do(func() { close(e.done) }) }

func main() {
	if len(os.Args) > 1 && os.Args[1] == "direct-first" {
		runDirectFirst(os.Args[2:])
		return
	}
	flag.Parse()
	// Secrets may come from the environment instead of argv: on macOS any local user reads another
	// process's command line (ps auxww), another account's environment not. A flag always wins over
	// its variable, so passing both keeps working exactly as the flag alone did.
	*peer = envDefault(*peer, "LIMITED_PEER")
	*calls = envDefault(*calls, "LIMITED_CALLS")
	*password = envDefault(*password, "LIMITED_PASSWORD")
	*deviceID = envDefault(*deviceID, "LIMITED_DEVICE_ID")
	*instance = envDefault(*instance, "LIMITED_INSTANCE")
	if *peer == "" || *calls == "" {
		fmt.Fprintln(os.Stderr, "limitedd: -peer and -calls are required")
		os.Exit(2)
	}
	if *password == "" {
		fmt.Fprintln(os.Stderr, "limitedd: -password is required")
		os.Exit(2)
	}
	if err := dropPrivileges(*uid, *gid); err != nil {
		fmt.Fprintf(os.Stderr, "limitedd: privileges: %v\n", err)
		os.Exit(2)
	}
	limited.BindInterface = *iface
	creds.DialContext = limited.BoundDialContext
	resolvers := dnsServers(*dns, hasResolvConf())
	useDNS(resolvers)

	ev := &events{
		out:     bufio.NewWriter(os.Stdout),
		log:     bufio.NewWriter(os.Stderr),
		verbose: *verbose,
		done:    make(chan struct{}),
	}
	if len(resolvers) > 0 {
		ev.debugf("resolvers: %s", strings.Join(resolvers, ", "))
	}
	core := limited.Start(&limited.Config{
		Peer:          *peer,
		CallIDs:       *calls,
		Password:      *password,
		DeviceID:      *deviceID,
		Instance:      *instance,
		ListenAddr:    *listenAddr,
		MediaMode:     *media,
		MaxStreams:    *maxStreams,
		ActiveStreams: *active,
		KeepaliveSec:  *keepalive,
		BindingSec:    *bindingSEC,
		StatsSec:      *statsSEC,
		Verbose:       *verbose,
	}, ev)

	go commands(core, ev)
	if *watch {
		go watchParent(ev)
	}
	watchLifeline(*lifeline, ev)
	<-ev.done
	core.Stop()
}

// commands applies the app's control lines; a closed stdin ends the run like STOP.
func commands(core *limited.Core, ev *events) {
	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) == 0 {
			continue
		}
		switch strings.ToUpper(f[0]) {
		case "STREAMS":
			if n, err := strconv.Atoi(arg(f, 1)); err == nil {
				core.SetStreams(n)
				ev.debugf("streams: %d", n)
			}
		case "KEEPALIVE":
			k, kerr := strconv.Atoi(arg(f, 1))
			b, berr := strconv.Atoi(arg(f, 2))
			if kerr == nil && berr == nil {
				core.SetKeepalive(k, b)
				ev.debugf("keepalive: %d s, binding: %d s", k, b)
			}
		case "STOP":
			ev.stop()
			return
		}
	}
	ev.stop()
}

func arg(f []string, i int) string {
	if i < len(f) {
		return f[i]
	}
	return ""
}

// envDefault is value, or the environment variable when the flag stayed empty: the caller chooses which
// channel a secret travels by, and the environment is the one other local users cannot read.
func envDefault(value, name string) string {
	if value != "" {
		return value
	}
	return os.Getenv(name)
}

// watchParent ends the core when the app's process is replaced or killed and stops reacting to stdin.
func watchParent(ev *events) {
	ppid := os.Getppid()
	for {
		time.Sleep(2 * time.Second)
		if os.Getppid() != ppid {
			ev.stop()
			return
		}
	}
}

// watchLifeline ends the run when the descriptor the service holds open for us closes — the service ended,
// whatever the way (the same lifeline borderless-core gets as its fd 4).
func watchLifeline(fd int, ev *events) {
	if fd < 0 {
		return
	}
	go func() {
		f := os.NewFile(uintptr(fd), "lifeline")
		_, _ = io.Copy(io.Discard, f)
		ev.stop()
	}()
}

// dropPrivileges switches to uid / gid for good: supplementary groups cleared, real, effective and saved ids
// changed, and checked afterwards (setuid(0) must fail). As a user (development, Android) it changes nothing.
// The same as native/core/cmd/borderless-core/main.go.
func dropPrivileges(uid, gid int) error {
	if os.Geteuid() != 0 {
		return nil
	}
	if uid <= 0 || gid <= 0 {
		return fmt.Errorf("started as root without -uid / -gid")
	}
	if err := syscall.Setgroups([]int{gid}); err != nil {
		return err
	}
	if err := syscall.Setgid(gid); err != nil {
		return err
	}
	if err := syscall.Setuid(uid); err != nil {
		return err
	}
	if os.Getuid() != uid || os.Geteuid() != uid || os.Getgid() != gid || os.Getegid() != gid {
		return fmt.Errorf("ids did not change")
	}
	if syscall.Setuid(0) == nil {
		return fmt.Errorf("could regain root")
	}
	return nil
}

// cpuMillis is the process's CPU time so far (user + system): the app adds its deltas to its own count — the
// cores run outside its process, which is how Android's /proc read and the Mac's getrusage see them at all.
func cpuMillis() int64 {
	var ru syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &ru); err != nil {
		return 0
	}
	return ru.Utime.Nano()/1e6 + ru.Stime.Nano()/1e6
}
