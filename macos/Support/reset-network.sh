#!/bin/sh
# Puts the Mac's network settings back to what they were before Border(less) changed them.
#
#   sudo sh "/Library/PrivilegedHelperTools/app.borderless.service-reset"
#
# Works without the service, the app and their code: only system tools and the undo log the service writes *before*
# each change. Removes only what is ours — routes through our utun listed in the log, the fixed
# State:/Network/Service/app.borderless.tunnel/* keys — and never anything else. Safe to run any number of times.
# The service runs it too (with --keep-service) whenever it stops the tunnel, so this path is used all the time.
# Paths: keep in step with Sources/Shared/Service.swift and Network.swift.
set -u
PATH=/usr/bin:/bin:/usr/sbin:/sbin
LABEL=app.borderless.service
DATA="/Library/Application Support/$LABEL"
UNDO="$DATA/network.undo"
SNAP="$DATA/network.snapshot.json"
KEYS="State:/Network/Service/app.borderless.tunnel"

[ "$(id -u)" = 0 ] || { echo "run as root: sudo sh $0" >&2; exit 1; }

KEEP=0
[ "${1:-}" = "--keep-service" ] && KEEP=1

# 1. Stop the service: its utun closes with it, and the kernel drops every route through it; configd drops its
#    temporary keys. launchd loads it again at the next start (plist untouched), and a fresh start connects nothing.
if [ "$KEEP" = 0 ]; then
  # A reset by hand means "give me my network back": the service won't reconnect by itself at the next start.
  rm -f "$DATA/wanted"
  launchctl bootout "system/$LABEL" 2>/dev/null && echo "service stopped" || true
  sleep 1
fi

# 2. Routes the log names, in case a utun outlived the process (it can't, but nothing here relies on that).
if [ -f "$UNDO" ]; then
  while read -r kind a b c; do
    [ "$kind" = route ] || continue
    # utun + any number of digits — the same bound UndoRecord.isUtun (Sources/Shared/Network.swift) accepts.
    case "$c" in utun[0-9]*) ;; *) continue ;; esac
    case "${c#utun}" in *[!0-9]*) continue ;; esac
    case "$b" in ""|*[!0-9a-fA-F:./]*) continue ;; esac
    case "$a" in
      inet) route -n delete -net "$b" -interface "$c" >/dev/null 2>&1 && echo "removed route $b via $c" ;;
      inet6) route -n delete -inet6 -net "$b" -interface "$c" >/dev/null 2>&1 && echo "removed route $b via $c" ;;
    esac
  done < "$UNDO"
fi

# 3. Our dynamic-store keys (fixed names, so this works even without a log).
for k in DNS IPv4 IPv6; do
  printf 'remove %s/%s\n' "$KEYS" "$k" | scutil >/dev/null 2>&1
done

# 4. Caches that may still hold answers from our resolver.
dscacheutil -flushcache 2>/dev/null
killall -HUP mDNSResponder 2>/dev/null

# 5. Nothing of ours is left: empty the log and drop the kept snapshot — the service reads it for its "differs"
#    compare before it runs this, and a stale one would otherwise make a later restore report differences that
#    are not real. The service starts a new pair next time.
if [ -f "$UNDO" ]; then
  : > "$UNDO"
fi
rm -f "$SNAP"

# 6. Report what macOS uses now.
echo "primary: $(printf 'show State:/Network/Global/IPv4\n' | scutil | sed -n 's/^ *PrimaryInterface : //p')"
echo "DNS: $(printf 'show State:/Network/Global/DNS\n' | scutil | sed -n 's/^ *[0-9]* : //p' | tr '\n' ' ')"
exit 0
