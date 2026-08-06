#!/usr/bin/env bash
# Build the web deliverables from the single source file.
#
#   bash build-web.sh
#
# There is exactly one source of truth: mojitama.html. Everything else — the PWA
# bundle, the zip, and the assets the APK embeds — is generated from it. This
# script exists because that copy used to be a manual step, which is precisely
# the kind of thing that eventually ships a stale build to one target and not
# another. build-apk.sh calls this first, so the three can no longer disagree.
#
# Note: python here is a native Windows binary and cannot read /e/... MSYS
# paths, so everything below uses shell tools or runs python from the right cwd
# with relative arguments.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

[ -f mojitama.html ] || { echo "!! missing mojitama.html"; exit 1; }

echo "==> syncing mojitama.html -> pwa/index.html"
cp mojitama.html pwa/index.html

# The service worker serves navigations network-first, so a new index.html lands
# on its own — but icons and the manifest are cache-first and would otherwise be
# pinned forever. Stamping the cache name with the build's content hash retires
# the old cache on every real change, and leaves clients alone when nothing moved.
STAMP=$(sha256sum mojitama.html | cut -c1-12)
echo "==> stamping service worker cache: mojitama-$STAMP"
sed -i "s/const CACHE = '[^']*';/const CACHE = 'mojitama-$STAMP';/" pwa/sw.js
grep -q "mojitama-$STAMP" pwa/sw.js || { echo "!! failed to stamp pwa/sw.js"; exit 1; }

echo "==> syncing pwa/ -> docs/ (GitHub Pages root)"
mkdir -p docs
cp pwa/* docs/

echo "==> zipping pwa/ -> mojitama-pwa.zip"
rm -f mojitama-pwa.zip
python -c "import shutil; shutil.make_archive('mojitama-pwa','zip','pwa')"

echo "==> web build ready"
echo "    pwa/            upload this directory to the subdomain root"
echo "    mojitama-pwa.zip"
echo "    mojitama.html   standalone single file (works with no siblings)"
