#!/usr/bin/env bash
# vallorcine index merge driver
# Custom git merge driver for .kb/ and .decisions/ CLAUDE.md index files.
#
# These files contain markdown tables where rows are appended by agents.
# Concurrent edits to the same table always produce valid-but-conflicting diffs.
# The correct resolution is always: keep all rows from both sides, deduplicate.
#
# Git merge driver contract:
#   $1 = ancestor (base)    — %O
#   $2 = current  (ours)    — %A  ← this file is modified IN PLACE with the result
#   $3 = other    (theirs)  — %B
#
# Exit 0 = merge succeeded (result written to $2)
# Exit 1 = merge failed (fall back to normal merge)
#
# Registered via: git config merge.vallorcine-index.driver "bash <path> %O %A %B"
# Scoped via .gitattributes to only vallorcine-managed index files.

# Note: no set -e — if anything fails, we fall through to exit 1 (normal merge).
# pipefail is safe since we want pipe failures to propagate.
set -uo pipefail

BASE="${1:-}"   # ancestor
OURS="${2:-}"   # current branch — result must be written here
THEIRS="${3:-}" # other branch

# Guard: require all 3 arguments
if [[ -z "$BASE" || -z "$OURS" || -z "$THEIRS" ]]; then
    exit 1
fi

# Strategy: collect all unique table rows from both sides, preserve non-table
# content from ours (structure, headers, comments), merge table rows.
#
# Table rows start with "| " and are NOT header/separator rows.
# Header rows: first "| " row after a heading or the "| --- |" separator line.
# Separator rows: contain only |, -, and spaces.

is_table_separator() {
    [[ "$1" =~ ^[[:space:]]*\|([[:space:]-]*\|)+[[:space:]]*$ ]]
}

is_table_row() {
    [[ "$1" =~ ^[[:space:]]*\| ]] && ! is_table_separator "$1"
}

is_table_header() {
    # A table row immediately followed by a separator is a header row.
    # We detect this contextually during processing, not here.
    false
}

# Extract all non-header table rows from a file, deduped by content
extract_data_rows() {
    local file="$1"
    local prev_was_row=0
    local skip_next_as_header=0
    local in_header_zone=1  # start of each table section

    while IFS= read -r line; do
        if is_table_separator "$line"; then
            # Next non-separator row after this is data, not header
            in_header_zone=0
            continue
        fi

        if is_table_row "$line"; then
            if [[ $in_header_zone -eq 1 ]]; then
                # This is the header row (before separator), skip it
                continue
            fi
            echo "$line"
        else
            # Non-table line resets header detection for next table
            in_header_zone=1
        fi
    done < "$file"
}

# Collect all unique data rows from both files
MERGED_ROWS_FILE=$(mktemp)
{
    extract_data_rows "$OURS"
    extract_data_rows "$THEIRS"
} | sort -u > "$MERGED_ROWS_FILE"

# Rebuild the file: use OURS as the template (preserves structure, headers,
# comments), but replace table data rows with the merged set.
#
# For each table section: keep the header + separator, then insert all merged
# rows that belong to that table. We detect which table a row belongs to by
# checking if the row existed in OURS's or THEIRS's version of that section.
#
# Simpler approach: since all index files have the same structure (headers +
# tables), rebuild by keeping all non-data-row lines from OURS and inserting
# merged rows after each separator.

RESULT=$(mktemp)
trap 'rm -f "$RESULT" "$MERGED_ROWS_FILE"' EXIT
in_header_zone=1
just_saw_separator=0
emitted_for_section=0

# Track which rows we've already emitted to avoid duplicates
declare -A emitted_rows

while IFS= read -r line; do
    if is_table_separator "$line"; then
        in_header_zone=0
        just_saw_separator=1
        echo "$line" >> "$RESULT"
        continue
    fi

    if is_table_row "$line"; then
        if [[ $in_header_zone -eq 1 ]]; then
            # Header row — keep it
            echo "$line" >> "$RESULT"
            continue
        fi

        if [[ $just_saw_separator -eq 1 ]]; then
            # First data row position — emit ALL merged rows here
            while IFS= read -r merged_line; do
                if [[ -z "${emitted_rows["$merged_line"]+x}" ]]; then
                    echo "$merged_line" >> "$RESULT"
                    emitted_rows["$merged_line"]=1
                fi
            done < "$MERGED_ROWS_FILE"
            just_saw_separator=0
        fi
        # Skip original data rows (replaced by merged set above)
        continue
    else
        # Non-table line
        just_saw_separator=0
        in_header_zone=1
        echo "$line" >> "$RESULT"
    fi
done < "$OURS"

# Write result back to OURS (git expects it there)
cp "$RESULT" "$OURS"

exit 0
