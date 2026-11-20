// Command borderless-core runs Xray-core for the Border(less) system service on macOS (mac/, local/macos/PLAN.md §1.0).
//
// The root service starts it inside a sandbox profile with the tunnel's utun descriptor as fd 3 (XRAY_TUN_FD=3: Xray
// then only reads and writes packets and touches no routes), the config on stdin (its length on the first line, then
// the bytes) and a lifeline pipe as fd 4. Before it reads a byte of the config it drops to the unprivileged account
// given by -uid / -gid, so a fault in Xray or in what a server sends never runs as root. When the lifeline closes (the
// service ended, whatever the way) it stops, which closes the utun and with it every route through it. After READY,
// stdin carries commands — "<id> DELAY <url>" (a ping through the running instance) and "<id> STATS" (traffic per
// outbound since the last call) — answered on stdout as "CTL <id> <result>" between Xray's log lines.
//
// `-helper` runs the service's own network work instead (helper.go): no utun, no config of its own.
package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

// maxConfig bounds what is read from stdin (a generated config is a few kilobytes).
const maxConfig = 8 << 20

func main() {
	uid := flag.Int("uid", -1, "drop to this user id before anything else (required when started as root)")
	gid := flag.Int("gid", -1, "drop to this group id")
	lifeline := flag.Int("lifeline", -1, "fd whose end-of-file means the service is gone: stop")
	helperMode := flag.Bool("helper", false, "serve the service's own network work (see helper.go)")
	flag.Parse()

	if err := dropPrivileges(*uid, *gid); err != nil {
		fatal("privileges: %v", err)
	}
	if *helperMode {
		// The helper ends by itself on stdin EOF; the lifeline is the backstop for a service gone
		// without closing that end (the graceful done-channel path below belongs to the main core
		// only — runHelper never reaches it). A bare exit is cleanup enough: the helper holds no
		// routes, addresses or keys that need undoing.
		if *lifeline >= 0 {
			go func() {
				_, _ = io.Copy(io.Discard, os.NewFile(uintptr(*lifeline), "lifeline"))
				os.Exit(0)
			}()
		}
		runHelper()
		return
	}

	in := bufio.NewReaderSize(os.Stdin, 64<<10)
	head, err := in.ReadString('\n')
	if err != nil {
		fatal("config: %v", err)
	}
	size, err := strconv.Atoi(strings.TrimSpace(head))
	if err != nil || size <= 0 || size > maxConfig {
		fatal("config: bad length %q", strings.TrimSpace(head))
	}
	data := make([]byte, size)
	if _, err := io.ReadFull(in, data); err != nil {
		fatal("config: %v", err)
	}
	instance, err := startInstance(data)
	if err != nil {
		fatal("%v", err)
	}
	var outMu sync.Mutex
	say := func(line string) {
		outMu.Lock()
		defer outMu.Unlock()
		fmt.Fprintln(os.Stdout, line)
	}
	say("READY")

	done := make(chan string, 2)
	go func() {
		for {
			line, err := in.ReadString('\n')
			if f := strings.Fields(line); len(f) >= 2 {
				go command(instance, f, say)
			}
			if err != nil {
				done <- "the service closed its end"
				return
			}
		}
	}()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	go func() { done <- (<-signals).String() }()
	if *lifeline >= 0 {
		go func() {
			f := os.NewFile(uintptr(*lifeline), "lifeline")
			_, _ = io.Copy(io.Discard, f)
			done <- "lifeline closed"
		}()
	}
	reason := <-done
	fmt.Fprintf(os.Stderr, "stopping: %s\n", reason)
	_ = instance.Close()
}

// command answers one line from the service: "<id> DELAY <url>" or "<id> STATS".
func command(instance *core.Instance, f []string, say func(string)) {
	id := f[0]
	switch f[1] {
	case "DELAY":
		url := ""
		if len(f) > 2 {
			url = f[2]
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		ms, err := measureDelay(ctx, instance, url)
		if err != nil {
			ms = -1
		}
		say(fmt.Sprintf("CTL %s %d", id, ms))
	case "STATS":
		say(fmt.Sprintf("CTL %s %s", id, takeTraffic(instance)))
	default:
		say(fmt.Sprintf("CTL %s ?", id))
	}
}

// dropPrivileges switches to uid / gid for good: supplementary groups cleared, real, effective and saved ids changed,
// and checked afterwards (setuid(0) must fail). As a user (development) it changes nothing.
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

func fatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
