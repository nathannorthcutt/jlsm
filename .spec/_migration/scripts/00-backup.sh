#!/usr/bin/env bash
# 00-backup.sh — Copy all pre-migration spec files to .spec/_archive/migration-<date>/
#
# Run from jlsm repo root. Idempotent (re-runs overwrite).

set -euo pipefail

BACKUP_DATE="${BACKUP_DATE:-$(date +%Y-%m-%d)}"
BACKUP_DIR=".spec/_archive/migration-${BACKUP_DATE}"

if [[ ! -d ".spec/domains" ]]; then
    echo "ERROR: .spec/domains/ not found — run from jlsm repo root" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

count=0
while IFS= read -r -d '' spec; do
    rel="${spec#.spec/domains/}"
    dest="$BACKUP_DIR/$rel"
    mkdir -p "$(dirname "$dest")"
    cp -p "$spec" "$dest"
    count=$((count + 1))
done < <(find .spec/domains -name "F*.md" -type f -print0)

# Also back up registry artifacts
if [[ -f .spec/registry/manifest.json ]]; then
    mkdir -p "$BACKUP_DIR/_registry"
    cp -p .spec/registry/manifest.json "$BACKUP_DIR/_registry/"
fi
if [[ -f .spec/registry/_obligations.json ]]; then
    mkdir -p "$BACKUP_DIR/_registry"
    cp -p .spec/registry/_obligations.json "$BACKUP_DIR/_registry/"
fi

echo "Backed up $count spec files to $BACKUP_DIR/"
