#!/usr/bin/env bash
# vallorcine decisions scanner
# Scans .decisions/ to build a structured summary of deferred decisions
# for the /decisions roadmap skill. Groups by parent ADR, checks resume
# conditions, detects roadmap staleness.
#
# Usage:
#   bash .claude/scripts/decisions-scan.sh
#
# Output: .decisions/.roadmap-scan.md (gitignored, read by Claude)
# Zero token cost — runs as shell outside Claude's context window.

set -euo pipefail

# ── Constants ────────────────────────────────────────────────────────────────

DECISIONS_DIR=".decisions"
FEATURE_DIR=".feature"
ARCHIVE_DIR=".feature/_archive"
SUMMARY_FILE="$DECISIONS_DIR/.roadmap-scan.md"
ROADMAP_FILE="$DECISIONS_DIR/roadmap.md"

# ── Safety checks ────────────────────────────────────────────────────────────

if [[ ! -d "$DECISIONS_DIR" ]]; then
    echo "ERROR: no .decisions/ directory found" >&2
    exit 1
fi

# ── Temp directory ───────────────────────────────────────────────────────────

TMPDIR_SCAN="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_SCAN"' EXIT

touch "$TMPDIR_SCAN/deferred.txt"
touch "$TMPDIR_SCAN/confirmed.txt"
touch "$TMPDIR_SCAN/parents.txt"
touch "$TMPDIR_SCAN/resume-met.txt"

# ── Scan all decision stubs ─────────────────────────────────────────────────

for slug_dir in "$DECISIONS_DIR"/*/; do
    [[ -d "$slug_dir" ]] || continue
    adr_file="$slug_dir/adr.md"
    [[ -f "$adr_file" ]] || continue

    slug="$(basename "$slug_dir")"

    # Parse frontmatter — extract status and depends_on
    status=""
    depends_on=""
    in_frontmatter=0
    while IFS= read -r line; do
        if [[ "$line" == "---" ]]; then
            if [[ $in_frontmatter -eq 1 ]]; then
                break
            fi
            in_frontmatter=1
            continue
        fi
        if [[ $in_frontmatter -eq 1 ]]; then
            case "$line" in
                *status:*)
                    status="$(echo "$line" | sed 's/.*status:[[:space:]]*//; s/"//g; s/[[:space:]]*$//')"
                    ;;
                *depends_on:*)
                    depends_on="$(echo "$line" | sed 's/.*depends_on:[[:space:]]*//; s/\[//; s/\]//; s/"//g; s/[[:space:]]*$//')"
                    ;;
            esac
        fi
    done < "$adr_file"

    if [[ "$status" == "deferred" ]]; then
        # Extract parent ADR from "Resume When" section or CLAUDE.md
        parent=""
        problem=""
        resume_when=""
        in_section=""

        while IFS= read -r line; do
            # Skip frontmatter
            if [[ "$line" == "---" ]]; then
                continue
            fi

            # Track sections
            if [[ "$line" == "# "* ]]; then
                # Title line — extract problem name
                problem="$(echo "$line" | sed 's/^# //; s/ — Deferred$//')"
            elif [[ "$line" == "## Problem"* ]]; then
                in_section="problem"
            elif [[ "$line" == "## Resume When"* || "$line" == "## Resume"* ]]; then
                in_section="resume"
            elif [[ "$line" == "## "* ]]; then
                in_section=""
            fi

            # Extract resume condition
            if [[ "$in_section" == "resume" && -n "$line" && "$line" != "## "* ]]; then
                resume_when="$line"
                # Try to extract parent slug from backtick references
                parent_ref="$(echo "$line" | grep -oE '`[a-z][-a-z0-9]+`' | head -1 | tr -d '`' || true)"
                if [[ -n "$parent_ref" && -d "$DECISIONS_DIR/$parent_ref" ]]; then
                    parent="$parent_ref"
                fi
            fi
        done < "$adr_file"

        # Fallback: extract parent from CLAUDE.md table if not found in body
        if [[ -z "$parent" && -f "$DECISIONS_DIR/CLAUDE.md" ]]; then
            parent="$( (grep -E "\| *$slug *\|" "$DECISIONS_DIR/CLAUDE.md" 2>/dev/null || true) | grep -oE '[a-z][-a-z0-9]+$' | tail -1 || true)"
        fi

        [[ -z "$problem" ]] && problem="$slug"

        echo "DEFERRED|$slug|$problem|$parent|$resume_when|$depends_on" >> "$TMPDIR_SCAN/deferred.txt"
    elif [[ "$status" == "confirmed" || "$status" == "accepted" ]]; then
        echo "CONFIRMED|$slug" >> "$TMPDIR_SCAN/confirmed.txt"
    fi
done

# ── Count by status ──────────────────────────────────────────────────────────

DEFERRED_COUNT="$(wc -l < "$TMPDIR_SCAN/deferred.txt" 2>/dev/null || echo 0)"
CONFIRMED_COUNT="$(wc -l < "$TMPDIR_SCAN/confirmed.txt" 2>/dev/null || echo 0)"

# ── Group by parent ADR ──────────────────────────────────────────────────────

# Extract unique parents and count children
while IFS='|' read -r _ slug problem parent resume; do
    if [[ -n "$parent" ]]; then
        echo "$parent" >> "$TMPDIR_SCAN/parents.txt"
    else
        echo "(no-parent)" >> "$TMPDIR_SCAN/parents.txt"
    fi
done < "$TMPDIR_SCAN/deferred.txt"

# ── Check resume conditions ─────────────────────────────────────────────────

# A resume condition is "met" if the parent ADR is confirmed
while IFS='|' read -r _ slug problem parent resume; do
    if [[ -n "$parent" ]]; then
        if grep -qE "^CONFIRMED\|$parent$" "$TMPDIR_SCAN/confirmed.txt" 2>/dev/null; then
            echo "RESUME_MET|$slug|$parent" >> "$TMPDIR_SCAN/resume-met.txt"
        fi
    fi
done < "$TMPDIR_SCAN/deferred.txt"

RESUME_MET_COUNT="$(wc -l < "$TMPDIR_SCAN/resume-met.txt" 2>/dev/null || echo 0)"

# ── Check archived features ─────────────────────────────────────────────────

ARCHIVED_FEATURES=""
if [[ -d "$ARCHIVE_DIR" ]]; then
    ARCHIVED_FEATURES="$(ls -1 "$ARCHIVE_DIR" 2>/dev/null | tr '\n' ',' | sed 's/,$//')"
fi

ACTIVE_FEATURES=""
if [[ -d "$FEATURE_DIR" ]]; then
    ACTIVE_FEATURES="$(ls -1 "$FEATURE_DIR" 2>/dev/null | grep -v '^_' | grep -v '\.md$' | grep -v '\.sh$' | tr '\n' ',' | sed 's/,$//')"
fi

# ── Check roadmap staleness ──────────────────────────────────────────────────

ROADMAP_STATUS="none"
ROADMAP_DATE=""

if [[ -f "$ROADMAP_FILE" ]]; then
    ROADMAP_DATE="$(date -r "$ROADMAP_FILE" '+%Y-%m-%d' 2>/dev/null || echo 'unknown')"

    # Find newest deferred ADR
    NEWEST_DEFERRED=""
    while IFS='|' read -r _ slug _ _ _; do
        adr="$DECISIONS_DIR/$slug/adr.md"
        if [[ -f "$adr" ]]; then
            adr_date="$(date -r "$adr" '+%s' 2>/dev/null || echo 0)"
            if [[ -z "$NEWEST_DEFERRED" || "$adr_date" -gt "$NEWEST_DEFERRED" ]]; then
                NEWEST_DEFERRED="$adr_date"
            fi
        fi
    done < "$TMPDIR_SCAN/deferred.txt"

    roadmap_ts="$(date -r "$ROADMAP_FILE" '+%s' 2>/dev/null || echo 0)"
    if [[ -n "$NEWEST_DEFERRED" && "$NEWEST_DEFERRED" -gt "$roadmap_ts" ]]; then
        ROADMAP_STATUS="stale"
    else
        ROADMAP_STATUS="current"
    fi
fi

# ── Write summary ────────────────────────────────────────────────────────────

cat > "$SUMMARY_FILE" << HEADER
# Decisions Roadmap Scan

**Scanned:** $(date '+%Y-%m-%d %H:%M')
**Deferred:** $DEFERRED_COUNT
**Confirmed:** $CONFIRMED_COUNT
**Resume conditions met:** $RESUME_MET_COUNT of $DEFERRED_COUNT
**Roadmap:** $ROADMAP_STATUS $([ -n "$ROADMAP_DATE" ] && echo "(last updated: $ROADMAP_DATE)" || echo "")
**Archived features:** $ARCHIVED_FEATURES
**Active features:** $ACTIVE_FEATURES

HEADER

# Parent groups table
echo "## Deferred by Parent ADR" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"
echo "| Parent ADR | Status | Children | Slugs |" >> "$SUMMARY_FILE"
echo "|-----------|--------|----------|-------|" >> "$SUMMARY_FILE"

# Get unique parents with counts
sort "$TMPDIR_SCAN/parents.txt" | uniq -c | sort -rn | while read -r count parent; do
    # Check parent status
    parent_status="unknown"
    if [[ "$parent" == "(no-parent)" ]]; then
        parent_status="—"
    elif grep -qE "^CONFIRMED\|$parent$" "$TMPDIR_SCAN/confirmed.txt" 2>/dev/null; then
        parent_status="confirmed"
    fi

    # Get child slugs for this parent
    children=""
    while IFS='|' read -r _ slug _ p _; do
        if [[ "$p" == "$parent" ]] || { [[ "$parent" == "(no-parent)" ]] && [[ -z "$p" ]]; }; then
            if [[ -n "$children" ]]; then
                children="$children, $slug"
            else
                children="$slug"
            fi
        fi
    done < "$TMPDIR_SCAN/deferred.txt"

    echo "| $parent | $parent_status | $count | $children |" >> "$SUMMARY_FILE"
done

echo "" >> "$SUMMARY_FILE"

# Full deferred list with problem statements
echo "## All Deferred Decisions" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"
echo "| Slug | Problem | Parent | Resume Met | Depends On |" >> "$SUMMARY_FILE"
echo "|------|---------|--------|-----------|-----------|" >> "$SUMMARY_FILE"

while IFS='|' read -r _ slug problem parent resume deps; do
    met="no"
    if grep -qE "^RESUME_MET\|$slug\|" "$TMPDIR_SCAN/resume-met.txt" 2>/dev/null; then
        met="yes"
    fi
    [[ -z "$deps" ]] && deps="—"
    echo "| $slug | $problem | $parent | $met | $deps |" >> "$SUMMARY_FILE"
done < "$TMPDIR_SCAN/deferred.txt"

echo "" >> "$SUMMARY_FILE"

# ── Report ───────────────────────────────────────────────────────────────────

echo "Decisions scan complete:"
echo "  Deferred: $DEFERRED_COUNT"
echo "  Confirmed: $CONFIRMED_COUNT"
echo "  Resume conditions met: $RESUME_MET_COUNT"
echo "  Roadmap status: $ROADMAP_STATUS"
echo "  Summary written to: $SUMMARY_FILE"
