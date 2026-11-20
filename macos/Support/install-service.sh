#!/bin/sh
# Installs (or replaces) the Border(less) system service. The app runs it as root through the system's password
# dialog (ServiceInstaller.swift); not meant to be run by hand. Argument: the path of the app bundle.
# Paths: keep in step with Sources/Shared/Service.swift.
set -eu
APP="$1"
LABEL=app.borderless.service
SRC="$APP/Contents/Library/Service"
RESET="$APP/Contents/Resources/reset-network.sh"
DATA="/Library/Application Support/$LABEL"
LISTS="/Library/Application Support/app.borderless.lists"
HELPERS=/Library/PrivilegedHelperTools
ACCOUNT=_borderless

# The launchd-job dir place() is working in (only ever one at a time); an exit before its own cleanup still
# removes it.
TMPJOB=
trap '[ -z "$TMPJOB" ] || /bin/rm -rf "$TMPJOB"' EXIT

for f in "$SRC/$LABEL" "$SRC/$LABEL.plist" "$SRC/borderless-core" "$SRC/borderless-limited" "$RESET" \
         "$APP/Contents/Resources/regions.json" "$APP/Contents/Resources/countries.json"; do
  [ -f "$f" ] || { echo "missing from the app: $f" >&2; exit 2; }
done

cdhash() { # the code-directory hash of a signed file or bundle, hex
  h=$(/usr/bin/codesign -dv --verbose=4 "$1" 2>&1 | /usr/bin/sed -n 's/^CDHash=//p' | /usr/bin/head -n 1)
  case "$h" in ""|*[!0-9a-f]*) echo "can't read the signature of $1" >&2; exit 2 ;; esac
  printf '%s' "$h"
}

# The one client the service will take commands from: this app.
CLIENT=$(cdhash "$APP")
[ -n "$CLIENT" ] || exit 2

/bin/launchctl bootout "system/$LABEL" 2>/dev/null || true
# bootout is only a request: the job stays registered until the old service's process is gone, and the
# bootstrap at the end fails while it still is ("Bootstrap failed: 5" — every install needed a retry,
# 2026-11-13). Wait for the teardown here; the rest of the script doesn't touch the job again.
i=0
until ! /bin/launchctl print "system/$LABEL" >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -le 400 ] || { echo "the old service wouldn't stop" >&2; exit 3; }
  /bin/sleep 0.2
done
/usr/bin/install -d -o root -g wheel -m 755 "$HELPERS"
# The reset is run by sh, never executed directly, so Gatekeeper doesn't apply to it; it must work even when the
# service binary can't start. Undo what an older service left before anything new runs.
/usr/bin/install -o root -g wheel -m 755 "$RESET" "$HELPERS/$LABEL-reset"
/bin/sh "$HELPERS/$LABEL-reset" --keep-service >/dev/null 2>&1 || true

# Binaries are placed by a one-shot launchd job, not by this script. Every file this script creates or touches gets
# the app's com.apple.provenance tag (the script runs under the app), and Gatekeeper refuses to start a root daemon
# whose ad-hoc signed binary carries it ("Security policy would not allow process", seen 2026-10-22). launchd's own
# children are not under the app, so what they write stays untagged. Gatekeeper also remembers a path it rejected,
# so every build goes to a path of its own, named by its code-directory hash. Paths go in as arguments, never into
# the command text.
place() { # source, destination
  job=$(/usr/bin/mktemp -d /tmp/borderless-install.XXXXXX)
  TMPJOB=$job
  label="$LABEL.install"
  /usr/bin/plutil -create xml1 "$job/job.plist"
  /usr/bin/plutil -insert Label -string "$label" "$job/job.plist"
  /usr/bin/plutil -insert ProgramArguments -array "$job/job.plist"
  for arg in /bin/sh -c \
    '/bin/cp -X "$1" "$2.new" && /usr/sbin/chown root:wheel "$2.new" && /bin/chmod 755 "$2.new" && /bin/mv -f "$2.new" "$2"' \
    sh "$1" "$2"; do
    /usr/bin/plutil -insert ProgramArguments -string "$arg" -append "$job/job.plist"
  done
  /usr/bin/plutil -insert RunAtLoad -bool true "$job/job.plist"
  /bin/launchctl bootout "system/$label" 2>/dev/null || true
  # "$2" is not removed first: the job copies to "$2.new" and renames it over "$2" (mv -f is atomic), so there is
  # no moment without a binary if the placement fails. A file already at "$2" is a complete copy of this same
  # build — the name carries the cdhash — and is simply replaced.
  /bin/launchctl bootstrap system "$job/job.plist"
  i=0
  until [ -f "$2" ] && ! /bin/launchctl print "system/$label" 2>/dev/null | /usr/bin/grep -q 'state = running'; do
    i=$((i + 1))
    [ "$i" -le 100 ] || { /bin/launchctl bootout "system/$label" 2>/dev/null || true; echo "couldn't place $2" >&2; exit 3; }
    /bin/sleep 0.2
  done
  /bin/launchctl bootout "system/$label" 2>/dev/null || true
  /bin/rm -f "$2.new"
  /bin/rm -rf "$job"
  TMPJOB=
}

SVC_HASH=$(cdhash "$SRC/$LABEL")
CORE_HASH=$(cdhash "$SRC/borderless-core")
LIM_HASH=$(cdhash "$SRC/borderless-limited")
[ -n "$SVC_HASH" ] && [ -n "$CORE_HASH" ] && [ -n "$LIM_HASH" ] || exit 2
BIN="$HELPERS/$LABEL-$(printf '%s' "$SVC_HASH" | /usr/bin/cut -c1-16)"
CORE="$HELPERS/app.borderless.core-$(printf '%s' "$CORE_HASH" | /usr/bin/cut -c1-16)"
LIMITED="$HELPERS/app.borderless.limited-$(printf '%s' "$LIM_HASH" | /usr/bin/cut -c1-16)"
place "$SRC/$LABEL" "$BIN"
place "$SRC/borderless-core" "$CORE"
place "$SRC/borderless-limited" "$LIMITED"

# Older builds (and the fixed name of the first one); the reset script's name doesn't match the hex
# pattern. Removed only once the placements succeeded: before them a failed place() would leave the
# still-running service's recorded paths pointing at binaries this just deleted.
for old in "$HELPERS/$LABEL" "$HELPERS"/$LABEL-[0-9a-f]* "$HELPERS"/app.borderless.core-[0-9a-f]* \
           "$HELPERS"/app.borderless.limited-[0-9a-f]*; do
  [ "$old" = "$BIN" ] || [ "$old" = "$CORE" ] || [ "$old" = "$LIMITED" ] || /bin/rm -f "$old"
done

# The core's own account: no shell, no home, hidden; the core drops to it before reading its config. A
# half-created one — the user without the group, or the other way round (an interrupted older install) — fails
# here with a clear message instead of later at spawn.
USER_OK=0; GROUP_OK=0
/usr/bin/dscl . -read "/Users/$ACCOUNT" >/dev/null 2>&1 && USER_OK=1
/usr/bin/dscl . -read "/Groups/$ACCOUNT" >/dev/null 2>&1 && GROUP_OK=1
if [ "$USER_OK" != "$GROUP_OK" ]; then
  echo "the $ACCOUNT account is half-created (user present: $USER_OK, group present: $GROUP_OK)" >&2
  echo "remove it and install again: dscl . -delete /Users/$ACCOUNT; dscl . -delete /Groups/$ACCOUNT" >&2
  exit 4
fi
# Both present is still not enough: the user's primary group must be this very group (a hand-made or an
# older interrupted install can leave the user on another one, and the core would drop to the wrong gid).
if [ "$USER_OK" = 1 ]; then
  USER_GID=$(/usr/bin/dscl . -read "/Users/$ACCOUNT" PrimaryGroupID 2>/dev/null | /usr/bin/awk '$1 ~ /:$/ {print $2}')
  # The group's gid attribute is `PrimaryGroupID`, the same as the user's — `PrimaryGID` doesn't exist on
  # current macOS (a read there answers "No such key", and a perfectly linked pair read as "mislinked").
  GROUP_GID=$(/usr/bin/dscl . -read "/Groups/$ACCOUNT" PrimaryGroupID 2>/dev/null | /usr/bin/awk '$1 ~ /:$/ {print $2}')
  if [ -z "$USER_GID" ] || [ -z "$GROUP_GID" ] || [ "$USER_GID" != "$GROUP_GID" ]; then
    echo "the $ACCOUNT account is mislinked (user's PrimaryGroupID: ${USER_GID:-none}, group's PrimaryGID: ${GROUP_GID:-none})" >&2
    echo "remove it and install again: dscl . -delete /Users/$ACCOUNT; dscl . -delete /Groups/$ACCOUNT" >&2
    exit 4
  fi
fi
if [ "$USER_OK" = 0 ]; then
  ID=""
  for n in $(/usr/bin/jot - 400 499); do
    if [ -z "$(/usr/bin/dscl . -search /Users UniqueID "$n")" ] && [ -z "$(/usr/bin/dscl . -search /Groups PrimaryGroupID "$n")" ]; then
      ID=$n
      break
    fi
  done
  [ -n "$ID" ] || { echo "no free id for $ACCOUNT" >&2; exit 4; }
  /usr/bin/dscl . -create "/Groups/$ACCOUNT"
  /usr/bin/dscl . -create "/Groups/$ACCOUNT" PrimaryGroupID "$ID"
  /usr/bin/dscl . -create "/Groups/$ACCOUNT" Password '*'
  /usr/bin/dscl . -create "/Groups/$ACCOUNT" RealName "Border(less) core"
  /usr/bin/dscl . -create "/Users/$ACCOUNT"
  /usr/bin/dscl . -create "/Users/$ACCOUNT" UniqueID "$ID"
  /usr/bin/dscl . -create "/Users/$ACCOUNT" PrimaryGroupID "$ID"
  /usr/bin/dscl . -create "/Users/$ACCOUNT" UserShell /usr/bin/false
  /usr/bin/dscl . -create "/Users/$ACCOUNT" NFSHomeDirectory /var/empty
  /usr/bin/dscl . -create "/Users/$ACCOUNT" Password '*'
  /usr/bin/dscl . -create "/Users/$ACCOUNT" RealName "Border(less) core"
  /usr/bin/dscl . -create "/Users/$ACCOUNT" IsHidden 1
fi

/usr/bin/install -o root -g wheel -m 644 "$SRC/$LABEL.plist" "/Library/LaunchDaemons/$LABEL.plist"
# BIN is "<helpers>/<label>-<hex>": no character that needs JSON escaping.
/usr/bin/plutil -replace ProgramArguments -json "[\"$BIN\"]" "/Library/LaunchDaemons/$LABEL.plist"
/usr/bin/install -d -o root -g wheel -m 700 "$DATA"
/usr/bin/install -d -o root -g wheel -m 755 "$LISTS"
# The shared code's bundled files (the zones, country names), read by the engine inside the service.
/usr/bin/install -d -o root -g wheel -m 700 "$DATA/assets"
for f in regions.json countries.json; do
  /usr/bin/install -o root -g wheel -m 600 "$APP/Contents/Resources/$f" "$DATA/assets/$f"
done
umask 077
printf '%s\n' "$CLIENT" > "$DATA/client"
printf '%s\n' "$CORE" > "$DATA/core"
printf '%s\n' "$LIMITED" > "$DATA/limited"
/bin/launchctl bootstrap system "/Library/LaunchDaemons/$LABEL.plist"
