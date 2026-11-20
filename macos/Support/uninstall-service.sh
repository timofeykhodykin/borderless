#!/bin/sh
# Removes the Border(less) system service and the core's account; with "with-data" also its data and lists. Run as
# root by the app (ServiceInstaller.swift). Paths: keep in step with Sources/Shared/Service.swift.
set -eu
LABEL=app.borderless.service
HELPERS=/Library/PrivilegedHelperTools
# Stop it and put the network back first (the reset stops the service itself).
if [ -f "$HELPERS/$LABEL-reset" ]; then
  /bin/sh "$HELPERS/$LABEL-reset" || true
fi
/bin/launchctl bootout "system/$LABEL" 2>/dev/null || true
/bin/rm -f "/Library/LaunchDaemons/$LABEL.plist" "$HELPERS/$LABEL" "$HELPERS"/$LABEL-[0-9a-f]* \
  "$HELPERS"/app.borderless.core-[0-9a-f]* "$HELPERS"/app.borderless.limited-[0-9a-f]* \
  "$HELPERS/$LABEL-reset" "/var/run/$LABEL.sock"
/usr/bin/dscl . -delete /Users/_borderless 2>/dev/null || true
/usr/bin/dscl . -delete /Groups/_borderless 2>/dev/null || true
if [ "${1:-}" = "with-data" ]; then
  /bin/rm -rf "/Library/Application Support/$LABEL" "/Library/Application Support/app.borderless.lists"
else
  /bin/rm -f "/Library/Application Support/$LABEL/client" "/Library/Application Support/$LABEL/core" \
    "/Library/Application Support/$LABEL/limited" "/Library/Application Support/$LABEL/wanted"
fi
