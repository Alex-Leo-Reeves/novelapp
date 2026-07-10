#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  bump-version.sh  –  NovelApp version bumping tool
#  Usage:
#    ./scripts/bump-version.sh 29 "1.29"
#
#  What it updates:
#    composeApp/build.gradle.kts           (versionCode & versionName)
#    composeApp/build.gradle.kts           (packageVersion for Desktop)
#    PlatformAppVersion.desktop.kt         (versionCode & versionName)
#    site/app-version.json                 (versionCode & versionName)
#    iosApp/project.yml                    (CURRENT_PROJECT_VERSION & MARKETING_VERSION)
#    tvApp/build.gradle.kts                (versionCode & versionName)
#    package.json                          (version)
#
#  NOTE: AppReleaseConfig.kt is NOT updated manually anymore — it now
#  derives CURRENT_VERSION_CODE / CURRENT_VERSION_NAME from PlatformAppVersion
#  at runtime, so those values are always the actual compiled version.
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <versionCode> <versionName>"
  echo "Example: $0 29 \"1.29\""
  exit 1
fi

VC="$1"
VN="$2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "═══ Bumping to v$VN (code $VC) ═══"

# ── 1. composeApp/build.gradle.kts ─────────────────────────────────────────
sed -i \
  -E "s/^(\\s*versionCode\\s*=\\s*)[0-9]+/\\1${VC}/" \
  "$ROOT/composeApp/build.gradle.kts"
sed -i \
  -E "s/^(\\s*versionName\\s*=\\s*)\"[^\"]+\"/\\1\"${VN}\"/" \
  "$ROOT/composeApp/build.gradle.kts"
# Desktop packageVersion (last occurrence in the file)
sed -i \
  -E "s/^(\\s*packageVersion\\s*=\\s*)\"[^\"]+\"/\\1\"${VN}.0\"/" \
  "$ROOT/composeApp/build.gradle.kts"
echo "  ✔ composeApp/build.gradle.kts  →  $VC / $VN"

# ── 2. PlatformAppVersion.desktop.kt ──────────────────────────────────────
DESKTOP="$ROOT/composeApp/src/desktopMain/kotlin/com/alexleoreeves/novelapp/platform/PlatformAppVersion.desktop.kt"
sed -i \
  -E "s/^(\\s*actual val versionCode: Int\\s*=\\s*)[0-9]+/\\1${VC}/" \
  "$DESKTOP"
sed -i \
  -E "s/^(\\s*actual val versionName: String\\s*=\\s*)\"[^\"]+\"/\\1\"${VN}\"/" \
  "$DESKTOP"
echo "  ✔ PlatformAppVersion.desktop.kt  →  $VC / $VN"

# ── 3. site/app-version.json ───────────────────────────────────────────────
sed -i \
  -E "s/\"versionCode\":\\s*[0-9]+/\"versionCode\": $VC/" \
  "$ROOT/site/app-version.json"
sed -i \
  -E "s/\"versionName\":\\s*\"[^\"]+\"/\"versionName\": \"$VN\"/" \
  "$ROOT/site/app-version.json"
echo "  ✔ site/app-version.json        →  $VC / $VN"

# ── 4. iosApp/project.yml ──────────────────────────────────────────────────
sed -i \
  -E "s/^(\\s*CURRENT_PROJECT_VERSION:\\s*)[0-9]+/\\1${VC}/" \
  "$ROOT/iosApp/project.yml"
sed -i \
  -E "s/^(\\s*MARKETING_VERSION:\\s*)\"[^\"]+\"/\\1\"${VN}\"/" \
  "$ROOT/iosApp/project.yml"
echo "  ✔ iosApp/project.yml           →  $VC / $VN"

# ── 5. tvApp/build.gradle.kts ──────────────────────────────────────────────
sed -i \
  -E "s/^(\\s*versionCode\\s*=\\s*)[0-9]+/\\1${VC}/" \
  "$ROOT/tvApp/build.gradle.kts"
sed -i \
  -E "s/^(\\s*versionName\\s*=\\s*)\"[^\"]+\"/\\1\"${VN}\"/" \
  "$ROOT/tvApp/build.gradle.kts"
echo "  ✔ tvApp/build.gradle.kts       →  $VC / $VN"

# ── 6. package.json ────────────────────────────────────────────────────────
#   "version": "1.0.0"  →  "version": "X.Y.Z"
# We extract the major.minor.patch from versionName and write it
# package.json uses semver; we map 1.29 → 1.29.0
if echo "$VN" | grep -q '\.'; then
  SEGMENTS=$(echo "$VN" | awk -F. '{print NF}')
  if [ "$SEGMENTS" -eq 2 ]; then
    SEMVER="${VN}.0"
  else
    SEMVER="$VN"
  fi
else
  SEMVER="${VN}.0.0"
fi
sed -i \
  -E "s/\"version\":\\s*\"[^\"]+\"/\"version\": \"${SEMVER}\"/" \
  "$ROOT/package.json"
echo "  ✔ package.json                 →  $SEMVER"

echo ""
echo "═══ Done — v$VN (code $VC) written to all files ═══"
echo ""
echo "Next steps:"
echo "  1. Verify with:    git diff --stat"
echo "  2. Edit release notes in  site/app-version.json"
echo "  3. Build:          cd composeApp && ./gradlew assembleRelease"
echo "  4. Commit:         git add -A && git commit -m \"Bump to v$VN\""
