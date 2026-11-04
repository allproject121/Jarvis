#!/bin/sh
# Usage (from project root): sh backups/feature-fixes-2026-11-04/backup.sh <file> [file...]
# Copies each file into this backup folder once (first original wins).
B="backups/feature-fixes-2026-11-04"
for f in "$@"; do
  [ -f "$f" ] || { echo "skip (missing): $f"; continue; }
  d="$B/$f"
  [ -f "$d" ] || { mkdir -p "$(dirname "$d")"; cp -p "$f" "$d"; echo "backed up: $f"; }
done
