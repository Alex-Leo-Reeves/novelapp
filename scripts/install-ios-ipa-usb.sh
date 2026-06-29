#!/usr/bin/env bash
set -euo pipefail

IPA_PATH="${1:-site/downloads/novelapp-ios.ipa}"

if [ ! -f "$IPA_PATH" ]; then
  echo "IPA not found: $IPA_PATH" >&2
  echo "Download the GitHub Actions artifact or wait for it to publish into site/downloads first." >&2
  exit 1
fi

if command -v ideviceinstaller >/dev/null 2>&1; then
  ideviceinstaller -i "$IPA_PATH"
  exit 0
fi

if command -v xcrun >/dev/null 2>&1; then
  DEVICE_ID="${IOS_DEVICE_ID:-}"
  if [ -z "$DEVICE_ID" ]; then
    echo "Set IOS_DEVICE_ID to the iPhone identifier from: xcrun devicectl list devices" >&2
    exit 1
  fi

  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  unzip -q "$IPA_PATH" -d "$TMP_DIR"
  APP_PATH="$(find "$TMP_DIR/Payload" -maxdepth 1 -name '*.app' -print -quit)"
  if [ -z "$APP_PATH" ]; then
    echo "No .app bundle found inside $IPA_PATH" >&2
    exit 1
  fi

  xcrun devicectl device install app --device "$DEVICE_ID" "$APP_PATH"
  exit 0
fi

cat >&2 <<'EOF'
No supported USB installer found.

Install one of these on the computer that has the iPhone plugged in:
- libimobiledevice/ideviceinstaller, then run this script again.
- Xcode 15+, set IOS_DEVICE_ID, then run this script again.

GitHub Actions cannot install to your local USB iPhone because the runner is remote.
EOF
exit 1
