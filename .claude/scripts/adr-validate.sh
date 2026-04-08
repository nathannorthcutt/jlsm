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

[[ -z "$duplicates" ]] || {
    dup_count="$(echo "$duplicates" | wc -l | tr -d ' ')"
    echo "ADR contradiction: $dup_count slug(s) have multiple accepted decisions:"
    while IFS= read -r slug; do
        count="$(echo "$accepted_slugs" | grep -c "^${slug}$")"
        echo "   - $slug ($count accepted entries)"
    done <<< "$duplicates"
    echo "   Review with: /decisions revisit <slug>"
}

# ── Cross-reference validation (advisory) ────────────────────────────────────
# Check kb_refs and related_decisions in ADR frontmatter resolve to real files.

# Reuse SCRIPT_DIR/PROJECT_ROOT from above if available, otherwise compute
if [[ -z "${SCRIPT_DIR:-}" ]]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
if [[ -z "${PROJECT_ROOT:-}" ]]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
warnings=0

while IFS= read -r -d '' adr_file; do
    # Extract YAML frontmatter (between first two --- lines)
    fm=$(awk '/^---$/{n++; next} n==1{print} n>=2{exit}' "$adr_file" 2>/dev/null)
    [[ -z "$fm" ]] && continue

    slug=$(basename "$(dirname "$adr_file")")

    # Check kb_refs — extract values between brackets on kb_refs line
    kb_refs=$(echo "$fm" | grep '^kb_refs:' | sed 's/^kb_refs: *\[//; s/\].*//; s/"//g; s/,/ /g')
    for ref in $kb_refs; do
        ref=$(echo "$ref" | sed 's/^[ \t]*//;s/[ \t]*$//')
        [[ -z "$ref" ]] && continue
        if [[ ! -f "$PROJECT_ROOT/.kb/$ref.md" ]]; then
            echo "  WARN [$slug] kb_ref '$ref' -> .kb/$ref.md not found"
            warnings=$((warnings + 1))
        fi
    done

    # Check related_decisions — same extraction pattern
    rel_decisions=$(echo "$fm" | grep '^related_decisions:' | sed 's/^related_decisions: *\[//; s/\].*//; s/"//g; s/,/ /g')
    for ref in $rel_decisions; do
        ref=$(echo "$ref" | sed 's/^[ \t]*//;s/[ \t]*$//')
        [[ -z "$ref" ]] && continue
        if [[ ! -f "$PROJECT_ROOT/.decisions/$ref/adr.md" ]]; then
            echo "  WARN [$slug] related_decision '$ref' -> .decisions/$ref/adr.md not found"
            warnings=$((warnings + 1))
        fi
    done
done < <(find "$PROJECT_ROOT/.decisions" -name "adr*.md" -print0 2>/dev/null)

[[ $warnings -gt 0 ]] && echo "  $warnings cross-reference warning(s) found."

exit 0
