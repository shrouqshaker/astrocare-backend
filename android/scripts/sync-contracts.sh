#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/android/app/src/main/assets/contracts}"
mkdir -p "$DEST"
cp "$ROOT/contracts/enums.json" "$DEST/"
cp "$ROOT/contracts/data/"*.json "$DEST/"
echo "Copied frozen contract assets to $DEST"
