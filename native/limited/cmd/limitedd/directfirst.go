package main

import (
	"bufio"
	"flag"
	"fmt"
	"net"
	"os"
	"time"

	"app.borderless/limited"
	"app.borderless/limited/directfirst"
)

// runDirectFirst is `limitedd direct-first …`: instead of the core, the direct-first dialer of package directfirst
// (the app runs it on a limited network, see core/DirectFirst.kt). Same line protocol on stdout:
//
//	LISTEN {"port":1234}                                         the SOCKS5 port actually bound
//	STATS  {"direct":…,"server":…,"fallbacks":…,"failed":…}      connections so far, when they changed
//
// With -verbose each host moved to the server (and each failure) is a line on stderr. A closed stdin ends the run.
func runDirectFirst(args []string) {
	fs := flag.NewFlagSet("direct-first", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:0", "address to listen on")
	user := fs.String("user", "", "credentials clients must present (or LIMITED_DF_USER)")
	pass := fs.String("pass", "", "(or LIMITED_DF_PASS)")
	backAddr := fs.String("back", "", "SOCKS5 address of the way through the server")
	backUser := fs.String("back-user", "", "(or LIMITED_DF_BACK_USER)")
	backPass := fs.String("back-pass", "", "(or LIMITED_DF_BACK_PASS)")
	statsSec := fs.Int("stats", 60, "stats event interval, seconds")
	verboseLog := fs.Bool("verbose", false, "a line per host moved to the server on stderr")
	watchP := fs.Bool("watch-parent", true, "exit when the parent process is gone")
	dnsF := fs.String("dns", "", "resolvers to use, comma-separated IPs (the app passes the network's own)")
	iface := fs.String("iface", "", "bind every outbound socket to this interface (macOS: the tunnel takes all routes)")
	uid := fs.Int("uid", -1, "drop to this user id before anything else (required when started as root)")
	gid := fs.Int("gid", -1, "drop to this group id")
	lifeline := fs.Int("lifeline", -1, "fd whose end-of-file means the service is gone: stop")
	_ = fs.Parse(args)

	// The SOCKS credentials may come from the environment instead of argv (see envDefault in main.go:
	// argv is readable by every local user, another account's environment is not). A flag still wins.
	*user = envDefault(*user, "LIMITED_DF_USER")
	*pass = envDefault(*pass, "LIMITED_DF_PASS")
	*backUser = envDefault(*backUser, "LIMITED_DF_BACK_USER")
	*backPass = envDefault(*backPass, "LIMITED_DF_BACK_PASS")

	if err := dropPrivileges(*uid, *gid); err != nil {
		fmt.Fprintf(os.Stderr, "limitedd direct-first: privileges: %v\n", err)
		os.Exit(2)
	}
	limited.BindInterface = *iface
	useDNS(dnsServers(*dnsF, hasResolvConf()))

	ev := &events{
		out:     bufio.NewWriter(os.Stdout),
		log:     bufio.NewWriter(os.Stderr),
		verbose: *verboseLog,
		done:    make(chan struct{}),
	}
	s, err := directfirst.Listen(directfirst.Config{
		Listen: *listen, User: *user, Pass: *pass,
		Back: *backAddr, BackUser: *backUser, BackPass: *backPass,
		Dial: limited.BoundDialContext,
		Log:  func(line string) { ev.debugf("%s", line) },
	})
	if err != nil {
		ev.emit("ERROR", map[string]any{"code": "listen", "message": err.Error(), "fatal": true})
		fmt.Fprintln(os.Stderr, "direct-first:", err)
		os.Exit(1)
	}
	go func() { _ = s.Serve() }()
	ev.emit("LISTEN", map[string]any{"port": s.Addr().(*net.TCPAddr).Port})
	go func() {
		last := directfirst.Stats{}
		t := time.NewTicker(time.Duration(*statsSec) * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ev.done:
				return
			case <-t.C:
				if st := s.Stats(); st != last {
					last = st
					ev.emit("STATS", st)
				}
			}
		}
	}()
	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
		}
		ev.stop()
	}()
	if *watchP {
		go watchParent(ev)
	}
	watchLifeline(*lifeline, ev)
	<-ev.done
	_ = s.Close()
}
