#!/usr/bin/env bash
# Build the Mojitama APK without Gradle.
#
# Why not Gradle: Gradle's daemon needs java.nio Selector/Pipe, which perform a
# loopback self-connect. On this machine that call fails ("Unable to establish
# loopback connection"), so every Gradle build dies before it starts. aapt2 /
# javac / d8 / apksigner need no such thing, so we drive them directly.
#
# Usage:  bash build-apk.sh [--no-bump]
#
# versionCode is bumped on every build unless --no-bump is passed. Android will
# not install an APK whose versionCode is not greater than the installed one, so
# a monotonic counter is the cheapest way to keep every build installable.
set -euo pipefail

BUMP=1
for a in "$@"; do [ "$a" = "--no-bump" ] && BUMP=0; done

SDK="${ANDROID_HOME:-/e/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot}"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="$SRC/build"
WEB="$SRC/../pwa"          # web assets to bundle
DIST="$SRC/.."             # where the finished APK lands

if [ "$BUMP" = "1" ]; then
  python - "$SRC/AndroidManifest.xml" <<'PY'
import io, re, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
m = re.search(r'android:versionCode="(\d+)"', s)
if m:
    n = int(m.group(1)) + 1
    s = s[:m.start(1)] + str(n) + s[m.end(1):]
    io.open(p, 'w', encoding='utf-8').write(s)
    print("==> versionCode -> %d" % n)
PY
fi

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/assets/web"

# Regenerate the web bundle first, so the APK can never embed a stale copy of
# the game while mojitama.html has moved on.
echo "==> web build"
bash "$SRC/../build-web.sh" | sed 's/^/    /'

echo "==> staging web assets"
cp "$WEB"/index.html "$WEB"/manifest.webmanifest "$WEB"/sw.js "$WEB"/*.png "$OUT/assets/web/"

echo "==> aapt2 compile resources"
"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$OUT/compiled/res.zip"

echo "==> aapt2 link"
"$BT/aapt2.exe" link \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  -A "$OUT/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 35 \
  -o "$OUT/base.apk" \
  "$OUT/compiled/res.zip"

# Git Bash rewrites POSIX paths for native exe arguments, but NOT for paths
# inside an @argfile — those must already be Windows-form.
w() { cygpath -w "$1"; }

echo "==> javac"
mkdir -p "$OUT/gen"
: > "$OUT/sources.txt"
while IFS= read -r f; do w "$f" >> "$OUT/sources.txt"; done < <(find "$SRC/java" "$OUT/gen" -name '*.java')
echo "    $(wc -l < "$OUT/sources.txt") source file(s)"
"$JAVAC" -encoding UTF-8 -source 11 -target 11 -nowarn \
  -classpath "$(w "$PLATFORM")" \
  -d "$(w "$OUT/classes")" "@$(w "$OUT/sources.txt")" 2>&1 | grep -v "bootstrap class path" || true

echo "==> d8 (dex)"
: > "$OUT/classes.txt"
while IFS= read -r f; do w "$f" >> "$OUT/classes.txt"; done < <(find "$OUT/classes" -name '*.class')
echo "    $(wc -l < "$OUT/classes.txt") class file(s)"
"$BT/d8.bat" --min-api 24 --lib "$PLATFORM" --output "$OUT" "@$(w "$OUT/classes.txt")"

echo "==> normalize asset paths (aapt2/Windows writes backslash entry names)"
python "$SRC/fix_asset_paths.py" "$(w "$OUT/base.apk")" "$(w "$OUT/unsigned.apk")"
(cd "$OUT" && "$SDK/build-tools/35.0.0/aapt.exe" add -f unsigned.apk classes.dex >/dev/null)

echo "==> zipalign"
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> keystore"
# Release signing comes from apk-src/signing.properties, which is deliberately
# NOT part of the source: whoever holds that key is the only one who can ever
# ship an update, because Android refuses an update signed by a different key.
# Without it we fall back to the throwaway debug key and say so loudly.
SIGN_PROPS="$SRC/signing.properties"
if [ -f "$SIGN_PROPS" ]; then
  KS_NAME=$(grep -E '^keystore=' "$SIGN_PROPS" | cut -d= -f2-)
  KS_ALIAS=$(grep -E '^alias=' "$SIGN_PROPS" | cut -d= -f2-)
  KS_PASS=$(grep -E '^password=' "$SIGN_PROPS" | cut -d= -f2-)
  KS="$SRC/$KS_NAME"
  echo "    release key ($KS_ALIAS)"
else
  KS="$SRC/mojitama-debug.keystore"
  KS_ALIAS=mojitama
  KS_PASS=mojitama
  echo "    !! no signing.properties — falling back to the DEBUG key."
  echo "    !! Builds signed this way cannot be updated in place."
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass mojitama -keypass mojitama \
      -alias mojitama -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Mojitama, OU=Game, O=Mojitama, L=, S=, C=US" >/dev/null
  fi
fi

echo "==> apksigner"
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$DIST/mojitama.apk" "$OUT/aligned.apk"

"$BT/apksigner.bat" verify --verbose "$DIST/mojitama.apk"
ls -la "$DIST/mojitama.apk"
echo "==> done: $DIST/mojitama.apk"
