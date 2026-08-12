#!/bin/bash
# Updates the bundled models.dev/api.json used as a fallback model registry.
# Run this periodically to keep the bundled data fresh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DEST="$SCRIPT_DIR/../src/android/app/src/main/assets/models-dev-api.json"
URL="https://models.dev/api.json"

echo "Downloading $URL ..."
curl -fsSL "$URL" -o "$ANDROID_DEST.tmp"

# Validate JSON
if ! python3 -m json.tool "$ANDROID_DEST.tmp" > /dev/null 2>&1; then
    echo "ERROR: Downloaded file is not valid JSON"
    rm -f "$ANDROID_DEST.tmp"
    exit 1
fi

PROVIDERS=$(python3 -c "import json; d=json.load(open('$ANDROID_DEST.tmp')); print(len(d))")
echo "Valid JSON — $PROVIDERS providers"

mv "$ANDROID_DEST.tmp" "$ANDROID_DEST"
echo "Updated $ANDROID_DEST"

# Show size
SIZE=$(wc -c < "$ANDROID_DEST" | tr -d ' ')
echo "File size: ${SIZE} bytes"
