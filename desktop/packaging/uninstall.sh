#!/usr/bin/env bash
# Removes Hey-Alfred. The AI engine, model and your conversations are kept unless you ask.
#   ./uninstall.sh            remove the program only
#   ./uninstall.sh --data     also remove settings, saved conversations, backups and the engine (not the model)
#   ./uninstall.sh --model    also remove the downloaded model (about 19 GB)
set -euo pipefail
PREFIX="${PREFIX:-$HOME/.local}"
APPS="$PREFIX/share/applications"
INFO="$HOME/.local/share/hy-desktop/ui.json"
if [ -f "$INFO" ]; then
  PID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["pid"])' "$INFO" 2>/dev/null || true)"
  [ -n "$PID" ] && kill "$PID" 2>/dev/null || true
fi
systemctl --user stop hy-colibri 2>/dev/null || true
rm -f "$PREFIX/bin/hey-alfred" "$PREFIX/bin/hy" "$APPS/hey-alfred.desktop" "$APPS/hey-alfred-terminal.desktop" \
      "$PREFIX/share/icons/hicolor/scalable/apps/hey-alfred.svg" \
      "$APPS/hy-desktop.desktop" "$APPS/hy-desktop-terminal.desktop" "$PREFIX/share/icons/hicolor/scalable/apps/hy-desktop.svg"
for a in "$@"; do
  case "$a" in
    --data) rm -rf "$HOME/.config/hy-desktop" "$HOME/.local/share/hy-desktop" ;;
    --model) rm -rf "$HOME/colibri-models/qwen3-coder-30b" ;;
  esac
done
echo "Hey-Alfred removed."
