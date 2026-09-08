#!/usr/bin/env bash
# Copia la carpeta local `content/` a /sdcard/Julicuentos en la tablet (adb).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTENT="${CONTENT_DIR:-$ROOT/content}"
DEST="${DEST_DIR:-/sdcard/Julicuentos}"

if [[ ! -f "$CONTENT/stories.json" ]]; then
  echo "No encuentro $CONTENT/stories.json" >&2
  echo "Crea content/ con stories.json, audio/ y covers/ (ver README)." >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No hay ningún dispositivo adb." >&2
  exit 1
fi

echo "Origen: $CONTENT"
echo "Destino: $DEST"
adb shell mkdir -p "$DEST/audio" "$DEST/covers"
adb push "$CONTENT/." "$DEST/"
adb shell am force-stop com.julicuentos.app || true
echo "OK. En la tablet: $DEST"
