#!/usr/bin/env bash
# Installs Hey-Alfred for the current user (no sudo). Usage: ./install.sh   (PREFIX=~/.local by default)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PREFIX="${PREFIX:-$HOME/.local}"
if ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2>/dev/null; then
  echo "Hey-Alfred needs Python 3.9 or newer (python3 --version)." >&2
  exit 1
fi
APPS="$PREFIX/share/applications"
ICONS="$PREFIX/share/icons/hicolor/scalable/apps"

# Remove a previous version first (also the one from before the rename): stop it if it is running,
# then delete its program and launcher files. Settings, saved conversations and the model are kept.
OLD=""
for b in hey-alfred hy; do
  [ -x "$PREFIX/bin/$b" ] && OLD="$("$PREFIX/bin/$b" --version 2>/dev/null | awk '{print $NF}')" && break
done
INFO="$HOME/.local/share/hy-desktop/ui.json"
if [ -f "$INFO" ]; then
  PID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["pid"])' "$INFO" 2>/dev/null || true)"
  if [ -n "$PID" ] && grep -qa "hy" "/proc/$PID/cmdline" 2>/dev/null; then
    kill "$PID" 2>/dev/null && echo "Stopped the running window${OLD:+ (version $OLD)}."
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
  fi
  rm -f "$INFO"
fi
if systemctl --user is-active --quiet hy-colibri 2>/dev/null; then
  systemctl --user stop hy-colibri 2>/dev/null && echo "Stopped the running AI engine."
fi
if [ -e "$PREFIX/bin/hey-alfred" ] || [ -e "$PREFIX/bin/hy" ]; then
  rm -f "$PREFIX/bin/hey-alfred" "$PREFIX/bin/hy" \
        "$APPS/hey-alfred.desktop" "$APPS/hey-alfred-terminal.desktop" "$ICONS/hey-alfred.svg" \
        "$APPS/hy-desktop.desktop" "$APPS/hy-desktop-terminal.desktop" "$ICONS/hy-desktop.svg"
  echo "Removed the old version${OLD:+ $OLD}."
fi

mkdir -p "$PREFIX/bin" "$APPS" "$ICONS"
install -m 755 "$HERE/hey-alfred" "$PREFIX/bin/hey-alfred"
ln -sf hey-alfred "$PREFIX/bin/hy"   # the short name
install -m 644 "$HERE/hey-alfred.svg" "$ICONS/hey-alfred.svg"
sed "s|@BIN@|$PREFIX/bin/hey-alfred|" "$HERE/hey-alfred.desktop" > "$APPS/hey-alfred.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" 2>/dev/null || true
echo "Installed $("$PREFIX/bin/hey-alfred" --version) to $PREFIX/bin/hey-alfred"
case ":$PATH:" in *":$PREFIX/bin:"*) ;; *) echo "Note: $PREFIX/bin is not in your PATH. Add it, or log out and in again.";; esac
echo
echo "Next:  hey-alfred setup     (installs the AI engine and downloads the model, about 19 GB, once; skip if already done)"
echo "Then:  open \"Hey-Alfred\" from your apps, or run  hey-alfred"
