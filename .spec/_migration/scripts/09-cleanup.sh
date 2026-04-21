#!/usr/bin/env bash
# 09-cleanup.sh — Final cleanup after successful migration.
#
# Removes:
#   - Old .spec/domains/F*-*.md files (after 08-validate-roundtrip.py confirms
#     they are fully replaced by domain.slug specs)
#   - Old empty domain directories (cluster-membership, cluster-transport,
#     storage, vector-indexing) once their contents have moved
#   - .spec/_migration/ working directory
#
# DOES NOT remove:
#   - .spec/_archive/migration-<date>/ — the immutable backup, kept as a safety
#     net for at least one release cycle
#   - .spec/MIGRATION.md — kept until a follow-up commit explicitly drops it
#
# Run from jlsm repo root. Idempotent.

set -euo pipefail

if [[ ! -d ".spec/domains" ]]; then
    echo "ERROR: .spec/domains/ not found — run from jlsm repo root" >&2
    exit 1
fi

if [[ ! -f ".spec/_migration/final-rn-table.json" ]]; then
    echo "ERROR: final-rn-table.json missing — run 02-generate-splits.py first" >&2
    exit 1
fi

# Step 1: remove legacy FXX files (preserved in .spec/_archive/migration-<date>/)
removed=0
while IFS= read -r -d '' f; do
    # Skip the verification-diff artifact (not a spec)
    [[ "$(basename "$f")" == "F04-verification-diff.md" ]] && continue
    rm "$f"
    removed=$((removed + 1))
done < <(find .spec/domains -name "F*-*.md" -type f -print0)
echo "Removed $removed legacy FXX-named spec files"

# Step 2: remove now-empty legacy domain directories (cluster-* renamed to membership/transport;
# storage absorbed into sstable; vector-indexing → vector)
for legacy_dir in cluster-membership cluster-transport storage vector-indexing; do
    dir=".spec/domains/$legacy_dir"
    if [[ -d "$dir" ]]; then
        # Only remove if no .md files remain (other than INDEX.md / CLAUDE.md)
        remaining=$(find "$dir" -name "*.md" -not -name "INDEX.md" -not -name "CLAUDE.md" -type f | wc -l)
        if [[ "$remaining" -eq 0 ]]; then
            rm -rf "$dir"
            echo "Removed empty legacy directory $dir"
        else
            echo "WARN: $dir still has $remaining content file(s); not removing"
        fi
    fi
done

# Step 3: remove migration working directory
if [[ -d ".spec/_migration" ]]; then
    rm -rf .spec/_migration
    echo "Removed .spec/_migration/"
fi

echo ""
echo "Cleanup complete."
echo "Backup remains at .spec/_archive/migration-<date>/ — keep for one release cycle, then remove in a follow-up commit."
echo "MIGRATION.md remains at .spec/MIGRATION.md — remove in a follow-up commit when no longer needed for review."
