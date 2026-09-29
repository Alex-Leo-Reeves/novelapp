#!/usr/bin/env bash
#
# update-apk-hashes.sh — auto-find the newest phone + TV APK builds and write
# their sha256/bytes into site/app-version.json.
#
# Search order per module (first hit wins):
#   1. <module>/build/outputs/apk/release/*.apk   (newest by mtime)
#   2. <module>/build/outputs/**/*.apk            (any variant, newest)
#   3. repo-wide <name>.apk                       (e.g. copied/built elsewhere)
#
# Usage:
#   scripts/update-apk-hashes.sh
#
# After running, the JSON holds the hashes of whatever APKs were found — make
# sure those are the files you are about to upload behind the permanent
# release-channel URLs (see HOW-TO-BUMP.md).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JSON="$ROOT/site/app-version.json"

PHONE_NAME="novelapp-android.apk"
TV_NAME="novelapp-androidtv.apk"

find_apk() {
    local module="$1" fallback_name="$2" found=""
    # 1. release variant, newest first
    found="$(find "$ROOT/$module/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' \
        -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n1 | cut -d' ' -f2- || true)"
    # 2. any APK under this module's outputs
    if [ -z "$found" ]; then
        found="$(find "$ROOT/$module/build/outputs" -type f -name '*.apk' \
            -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n1 | cut -d' ' -f2- || true)"
    fi
    # 3. repo-wide by canonical name — only files from the last 24h so a stale
    #    old download (site/downloads/…) can never be hashed by mistake.
    if [ -z "$found" ]; then
        found="$(find "$ROOT" -type f -name "$fallback_name" -mtime -1 \
            -printf '%T@ %p\n' 2>/dev/null | grep -v '/\.git/' | sort -rn | head -n1 | cut -d' ' -f2- || true)"
    fi
    printf '%s' "$found"
}

PHONE_APK="$(find_apk composeApp "$PHONE_NAME")"
TV_APK="$(find_apk tvApp "$TV_NAME")"

if [ -z "$PHONE_APK" ] || [ -z "$TV_APK" ]; then
    [ -z "$PHONE_APK" ] && echo "ERROR: no phone APK found (looked in composeApp/build/outputs + repo-wide $PHONE_NAME)" >&2
    [ -z "$TV_APK" ]    && echo "ERROR: no TV APK found (looked in tvApp/build/outputs + repo-wide $TV_NAME)" >&2
    echo "Build them first:  ./gradlew :composeApp:assembleRelease :tvApp:assembleRelease" >&2
    exit 1
fi

PHONE_SHA="$(sha256sum "$PHONE_APK" | awk '{print $1}')"
PHONE_BYTES="$(stat --format=%s "$PHONE_APK")"
TV_SHA="$(sha256sum "$TV_APK" | awk '{print $1}')"
TV_BYTES="$(stat --format=%s "$TV_APK")"

echo "Phone APK: $PHONE_APK"
echo "  sha256: $PHONE_SHA"
echo "  bytes : $PHONE_BYTES"
echo "TV APK:    $TV_APK"
echo "  sha256: $TV_SHA"
echo "  bytes : $TV_BYTES"

# ── Optional sanity check: APK versionCode must match the JSON channel ──────
AAPT2="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools -maxdepth 2 -name aapt2 2>/dev/null | sort | tail -n1 || true)"
if [ -n "$AAPT2" ]; then
    for apk in "$PHONE_APK" "$TV_APK"; do
        code="$({ "$AAPT2" dump badging "$apk" 2>/dev/null || true; } \
            | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -n1)"
        echo "  versionCode in $(basename "$apk"): ${code:-unknown (not a valid APK)}"
    done
fi

# ── Write the values into site/app-version.json ─────────────────────────────
python3 - "$JSON" "$PHONE_SHA" "$PHONE_BYTES" "$TV_SHA" "$TV_BYTES" <<'PY'
import json
import sys

path, phone_sha, phone_bytes, tv_sha, tv_bytes = sys.argv[1:6]

with open(path, encoding="utf-8") as handle:
    data = json.load(handle)

data["apkSha256"] = phone_sha
data["apkBytes"] = int(phone_bytes)
data["tvApkSha256"] = tv_sha
data["tvApkBytes"] = int(tv_bytes)

# indent=4 + ensure_ascii keep the file's original formatting (… escapes).
with open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, indent=4, ensure_ascii=True)

print()
print(f"Updated {path}")
print(f"  versionName={data.get('versionName')} versionCode={data.get('versionCode')} "
      f"tvVersionName={data.get('tvVersionName')} tvVersionCode={data.get('tvVersionCode')}")
print("  apkSha256/apkBytes + tvApkSha256/tvApkBytes now match the files above.")
PY
