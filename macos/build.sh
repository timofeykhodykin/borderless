#!/bin/sh
# Builds out/Border(less).app — the app, the system service inside it (Contents/Library/Service), strings, fonts and
# the install scripts — and signs it ad hoc (no Apple account: local/macos/PLAN.md §1.0). Needs only the Command
# Line Tools. Unit checks: `swift run Checks` (after this script or `./build.sh kotlin` built the shared framework).
set -eu
cd "$(dirname "$0")"
MAC=$(pwd)
ROOT=$(cd .. && pwd)

# The build follows the host's architecture: GOARCH for the two Go binaries, the shared module's macosArm64 /
# macosX64 target, SwiftPM's own product arch (macos/Package.swift picks the framework dir the same way). (The Go
# toolchain has a pinned darwin-amd64 archive too — android/build.gradle.kts `goArchives`.) An x86_64 build also
# needs the macosx64 K/N dependencies' hashes in gradle/verification-metadata.xml — refuse early until they exist.
ARCH=$(uname -m)
case "$ARCH" in
  arm64)  GOARCH=arm64; KARCH=Arm64 ;;
  x86_64) GOARCH=amd64; KARCH=X64 ;;
  *) echo "build.sh: unsupported host architecture $ARCH (arm64, x86_64)" >&2; exit 1 ;;
esac
if [ "$ARCH" != arm64 ] && ! grep -q macosx64 "$ROOT/gradle/verification-metadata.xml" 2>/dev/null; then
  echo "build.sh: the x86_64 build needs the shared module's macosx64 dependencies verified first" \
    "(run ./gradlew --write-verification-metadata sha256 :shared:linkReleaseFrameworkMacosX64 and commit" \
    "gradle/verification-metadata.xml)" >&2
  exit 1
fi

# The Kotlin code shared with Android (shared/): a static framework for the host arch, linked into the app and the
# service. Kotlin/Native asks `xcodebuild` for Xcode's version, which the Command Line Tools lack; a stub in .gradle
# answers it with the CLT package's own version (major.minor, 26.0 when pkgutil says nothing) — nothing else of
# Xcode is used. Its compiler and LLVM live in .gradle/konan (settings.gradle.kts).
SHIM="$ROOT/.gradle/xcode-shim/bin"
XV=$(pkgutil --pkg-info=com.apple.pkg.CLTools_Executables 2>/dev/null | sed -n 's/^version: //p' | cut -d. -f1-2)
mkdir -p "$SHIM"
printf '#!/bin/sh\n# Kotlin/Native asks Xcode for its version; the Command Line Tools have no xcodebuild.\necho "Xcode %s"\necho "Build version 17A324"\n' "${XV:-26.0}" > "$SHIM/xcodebuild"
chmod +x "$SHIM/xcodebuild"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
(cd "$ROOT" && env JAVA_HOME="$JAVA_HOME" PATH="$SHIM:$PATH" ./gradlew -q ":shared:linkReleaseFrameworkMacos$KARCH")
[ "${1:-}" = kotlin ] && exit 0
VERSION=$(sed -n 's/^public let borderlessVersion = "\(.*\)"$/\1/p' Sources/Shared/Version.swift)
[ -n "$VERSION" ] || { echo "no version in Sources/Shared/Version.swift" >&2; exit 1; }

# The Kotlin framework above is a prebuilt binary to SwiftPM: it isn't tracked as an input, so after a
# klib rebuild the products still link the OLD framework unless the stale binaries go first (the daemon
# shipped two builds behind once — the cdhash matched and no update was offered, owner 2026-11-10).
rm -f "$(swift build -c release --show-bin-path)/App" "$(swift build -c release --show-bin-path)/Service"
swift build -c release --product App
swift build -c release --product Service
BIN=$(swift build -c release --show-bin-path)

# The core (Xray, native/core) with the Go toolchain pinned for the Android build (android/build.gradle.kts `goVersion`,
# fetched and checked by `./gradlew :android:downloadGo`); modules verified against go.sum, nothing outside .gradle/go.
GOVER=$(sed -n 's/^val goVersion = "\(.*\)"$/\1/p' "$ROOT/android/build.gradle.kts")
G="$ROOT/.gradle/go"
[ -x "$G/go$GOVER/bin/go" ] || { echo "no Go $GOVER in .gradle/go: run ./gradlew :android:downloadGo" >&2; exit 1; }
mkdir -p out
(cd "$ROOT/native/core" && env GOROOT="$G/go$GOVER" GOPATH="$G/gopath" GOCACHE="$G/cache" GOMODCACHE="$G/mod" \
  HOME="$G/home" XDG_CONFIG_HOME="$G/home/.config" GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=0 \
  GOOS=darwin GOARCH=$GOARCH "$G/go$GOVER/bin/go" build -trimpath -ldflags '-s -w -buildid=' \
  -o "$MAC/out/borderless-core" ./cmd/borderless-core)

# The limited-network core (native/limited/cmd/limitedd): a separate binary spawned like the core for "limited"
# servers and "directly first" — not a mode of borderless-core, so tls-client/fhttp/quic/pion stay out of the
# process holding the utun. Same toolchain and module rules; -checklinkname=0 as for the Android build.
(cd "$ROOT/native/limited" && env GOROOT="$G/go$GOVER" GOPATH="$G/gopath" GOCACHE="$G/cache" GOMODCACHE="$G/mod" \
  HOME="$G/home" XDG_CONFIG_HOME="$G/home/.config" GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=0 \
  GOOS=darwin GOARCH=$GOARCH "$G/go$GOVER/bin/go" build -trimpath -buildvcs=false \
  -ldflags '-checklinkname=0 -s -w -buildid=' -o "$MAC/out/borderless-limited" ./cmd/limitedd)

APP="out/Border(less).app"
# `./build.sh icon`: draws the icon (AppIcon.swift) with the app just built and makes Resources/AppIcon.icns of it —
# run when the icon's design changes; the .icns is kept in the repository.
if [ "${1:-}" = icon ]; then
  rm -rf out/AppIcon.iconset
  BORDERLESS_ICON="$MAC/out/AppIcon.iconset" "$BIN/App"
  iconutil -c icns -o Resources/AppIcon.icns out/AppIcon.iconset
  echo Resources/AppIcon.icns
  exit 0
fi
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/Fonts" "$APP/Contents/Library/Service"
cp "$BIN/App" "$APP/Contents/MacOS/Border(less)"
cp "$BIN/Service" "$APP/Contents/Library/Service/app.borderless.service"
cp out/borderless-core "$APP/Contents/Library/Service/borderless-core"
cp out/borderless-limited "$APP/Contents/Library/Service/borderless-limited"
cp Support/app.borderless.service.plist "$APP/Contents/Library/Service/"
cp Support/install-service.sh Support/uninstall-service.sh Support/reset-network.sh "$APP/Contents/Resources/"
cp -R Resources/*.lproj Resources/AppIcon.icns "$APP/Contents/Resources/"
# The Android app's texts (one source for both platforms) and flags.
python3 -I "$ROOT/scripts/mac_strings.py" out/strings >/dev/null
for d in out/strings/*.lproj; do mkdir -p "$APP/Contents/Resources/$(basename "$d")"; cp "$d"/* "$APP/Contents/Resources/$(basename "$d")/"; done
mkdir -p "$APP/Contents/Resources/Flags"
for f in "$ROOT"/android/src/main/res/drawable-nodpi/flag_*.webp; do
  b=$(basename "$f"); cp "$f" "$APP/Contents/Resources/Flags/${b#flag_}"
done
# The shared code's bundled files (country names, the zones).
cp "$ROOT"/android/src/main/assets/countries.json "$ROOT"/android/src/main/assets/regions.json "$APP/Contents/Resources/"
cp ../android/src/main/res/font/pt_*.ttf ../docs/licenses/OFL-1.1.txt "$APP/Contents/Resources/Fonts/"
sed "s/@VERSION@/$VERSION/g;s/@ARCH@/$ARCH/g" Support/Info.plist > "$APP/Contents/Info.plist"

# Inner code first, then the bundle that seals it.
codesign --force --options runtime --timestamp=none -s - --identifier app.borderless.service \
  "$APP/Contents/Library/Service/app.borderless.service"
codesign --force --options runtime --timestamp=none -s - --identifier app.borderless.core \
  "$APP/Contents/Library/Service/borderless-core"
codesign --force --options runtime --timestamp=none -s - --identifier app.borderless.limited \
  "$APP/Contents/Library/Service/borderless-limited"
# The app alone carries entitlements — the camera one, without which the hardened runtime makes
# requestAccess answer "denied" regardless (QRCamera). The service/core/limited open no devices.
codesign --force --options runtime --timestamp=none -s - --entitlements Support/Entitlements.plist "$APP"
codesign --verify --strict --deep "$APP"

# A rebuild keeps the installed service's trust without a reinstall: the owner's key (`lists-signing-key.pem` —
# the one that signs the routing lists) signs this build's cdhash into the bundle's `app.borderless.client-sig`
# xattr, which the seal doesn't cover, so the signature above stays valid. The service takes a signed hash like
# the one the install recorded (PeerCheck.signed). Without the key the app is trusted the old way — reinstall.
KEY="$HOME/.borderless/lists-signing-key.pem"
if [ -f "$KEY" ]; then
  CH=$(/usr/bin/codesign -dv --verbose=4 "$APP" 2>&1 | /usr/bin/sed -n 's/^CDHash=//p' | /usr/bin/head -n 1)
  case "$CH" in ""|*[!0-9a-f]*) echo "can't read the signature of $APP" >&2; exit 2 ;; esac
  SIG=$(printf '%s%s' "borderless-client " "$CH" | openssl dgst -sha256 -sign "$KEY" | openssl base64 -A)
  /usr/bin/xattr -w app.borderless.client-sig "$SIG" "$APP"
fi
echo "$APP $VERSION"
