#!/usr/bin/env bash
# vallorcine ADR contradiction check
# Scans .decisions/CLAUDE.md for duplicate question slugs with "accepted" status.
# Two accepted ADRs for the same slug means contradictory decisions landed
# (e.g., from parallel branches). See "Known team issues" in DESIGN.md.
#
# Usage: bash .claude/scripts/adr-validate.sh
#   Exit 0 always — advisory, never blocks.

DECISIONS_INDEX=""

# Find the decisions index
if [[ -f ".decisions/CLAUDE.md" ]]; then
    DECISIONS_INDEX=".decisions/CLAUDE.md"
else
    # Try relative to script location (installed path)
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
    if [[ -f "$PROJECT_ROOT/.decisions/CLAUDE.md" ]]; then
        DECISIONS_INDEX="$PROJECT_ROOT/.decisions/CLAUDE.md"
    fi
fi

[[ -z "$DECISIONS_INDEX" ]] && exit 0

# Extract slugs from table rows with "accepted" status (case-insensitive).
# Table format: | Problem | Slug | Date | Status | Recommendation |
# We look for rows where the Status column contains "accepted".
# Slugs appear in column 2 of pipe-delimited tables.
accepted_slugs="$(grep -i '| *accepted *|' "$DECISIONS_INDEX" 2>/dev/null \
    | awk -F'|' '{gsub(/^[ \t]+|[ \t]+$/, "", $3); print $3}' \
    | sort)"

[[ -z "$accepted_slugs" ]] && exit 0

# Find duplicates
duplicates="$(echo "$accepted_slugs" | uniq -d)"

[[ -z "$duplicates" ]] && exit 0

# Report
dup_count="$(echo "$duplicates" | wc -l | tr -d ' ')"
echo "⚠  ADR contradiction: $dup_count slug(s) have multiple accepted decisions:"
while IFS= read -r slug; do
    count="$(echo "$accepted_slugs" | grep -c "^${slug}$")"
    echo "   - $slug ($count accepted entries)"
done <<< "$duplicates"
echo "   Review with: /decisions revisit <slug>"

exit 0
