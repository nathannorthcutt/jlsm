#!/usr/bin/env bash
# vallorcine finding list extractor
# Extracts finding IDs, titles, constructs, and locations from suspect files
# into a compact list for the audit orchestrator.
#
# Usage:
#   bash .claude/scripts/extract-findings.sh <run-dir>
#
# Output: <run-dir>/finding-list.txt
# Format: one line per finding, pipe-delimited:
#   <finding-id>|<title>|<construct>|<lens>|<cluster>|<suspect-file>
#
# Zero token cost — runs as shell outside Claude's context window.
# Always exits 0.

set -euo pipefail

RUN_DIR="${1:-}"
if [[ -z "$RUN_DIR" || ! -d "$RUN_DIR" ]]; then
    echo "Usage: extract-findings.sh <run-dir>" >&2
    exit 0
fi

OUTPUT="$RUN_DIR/finding-list.txt"
: > "$OUTPUT"

# Process each suspect file
for suspect_file in "$RUN_DIR"/suspect-*.md; do
    [[ -f "$suspect_file" ]] || continue

    filename="$(basename "$suspect_file")"

    # Extract lens and cluster from filename
    # Format: suspect-<lens>-cluster-<N>.md or suspect-<lens>-<N>.md
    lens=""
    cluster=""
    name_part="${filename#suspect-}"
    name_part="${name_part%.md}"

    if [[ "$name_part" == *"-cluster-"* ]]; then
        lens="${name_part%-cluster-*}"
        cluster="${name_part##*-cluster-}"
    else
        # Format: suspect-<lens>-<N>.md (no "cluster-" prefix)
        cluster="${name_part##*-}"
        lens="${name_part%-*}"
    fi

    # Extract findings: lines matching ### F-R followed by construct line
    finding_id=""
    title=""
    while IFS= read -r line; do
        if [[ "$line" == "### F-R"* ]]; then
            # Parse: ### F-R1.cb.1.1: Title text
            finding_id="$(echo "$line" | sed 's/^### //; s/:.*//')"
            title="$(echo "$line" | sed 's/^### [^:]*: //')"
        elif [[ -n "$finding_id" && "$line" == "- **Construct:**"* ]]; then
            construct="$(echo "$line" | sed 's/^- \*\*Construct:\*\* //')"
            echo "${finding_id}|${title}|${construct}|${lens}|${cluster}|${filename}" >> "$OUTPUT"
            finding_id=""
            title=""
        fi
    done < "$suspect_file"
done

# Filter out findings that already have prove-fix output files
# (resume scenario — don't re-process completed findings)
FILTERED="$RUN_DIR/finding-list.filtered.txt"
: > "$FILTERED"
already_done=0

while IFS='|' read -r fid title construct lens cluster suspect; do
    # Convert finding ID to output filename: F-R1.cb.1.1 → prove-fix-F-R1-cb-1-1.md
    short_id="$(echo "$fid" | tr '.' '-')"
    output_file="$RUN_DIR/prove-fix-${short_id}.md"
    if [[ -f "$output_file" ]]; then
        already_done=$((already_done + 1))
    else
        echo "${fid}|${title}|${construct}|${lens}|${cluster}|${suspect}" >> "$FILTERED"
    fi
done < "$OUTPUT"

# Replace output with filtered list
mv "$FILTERED" "$OUTPUT"

# Sort by finding ID for stable ordering
sort -t'|' -k1,1 -o "$OUTPUT" "$OUTPUT"

count="$(wc -l < "$OUTPUT" 2>/dev/null || echo 0)"
total=$((count + already_done))
echo "Extracted $count findings to $OUTPUT ($already_done already processed, $count remaining)"
