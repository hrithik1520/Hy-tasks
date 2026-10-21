#!/usr/bin/env bash
# Builds dist/hey-alfred-<version>.tar.gz (a single-file program plus installer). Needs only python3.
set -euo pipefail
cd "$(dirname "$0")/.."
V="$(tr -d '[:space:]' < version.txt)"
PYV="$(PYTHONPATH=src python3 -c 'import hy; print(hy.__version__)')"
[ "$V" = "$PYV" ] || { echo "version.txt ($V) and src/hy/config.py ($PYV) disagree" >&2; exit 1; }
PYTHONPATH=src python3 -W ignore -m unittest discover -s tests >/dev/null 2>&1 || { echo "tests fail: not building" >&2; exit 1; }
NAME="hey-alfred-$V"
rm -rf dist && mkdir -p "dist/$NAME"
find src -name __pycache__ -prune -exec rm -rf {} +
python3 -m zipapp src -o "dist/$NAME/hey-alfred" -p "/usr/bin/env python3" -m "hy.cli:main" -c
cp src/hy/web/Doto-OFL.txt "dist/$NAME/Doto-OFL.txt"
cp packaging/install.sh packaging/uninstall.sh packaging/hey-alfred.svg packaging/hey-alfred.desktop packaging/hey-alfred-terminal.desktop README.md version.txt "dist/$NAME/"
chmod +x "dist/$NAME/hey-alfred"
# Reproducible tarball: fixed order and timestamps.
tar --sort=name --mtime='2026-01-01 00:00:00' --owner=0 --group=0 --numeric-owner -C dist -czf "dist/$NAME.tar.gz" "$NAME"
(cd dist && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")
echo "built dist/$NAME.tar.gz"; cat "dist/$NAME.tar.gz.sha256"
