#!/usr/bin/env bash
# vallorcine KB freshness check
# Compares local .kb/ and .decisions/ indexes against the main branch.
# Warns if the local branch is behind — meaning KB entries or decisions
# were added on main that this branch doesn't have yet.
#
# Usage: bash .claude/scripts/kb-freshness-check.sh
#   Exit 0 always — advisory, never blocks.

# Must be in a git repo
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

# Determine main branch
MAIN_BRANCH=""
for candidate in main master; do
    if git show-ref --verify --quiet "refs/heads/$candidate" 2>/dev/null; then
        MAIN_BRANCH="$candidate"
        break
    fi
done
[[ -z "$MAIN_BRANCH" ]] && exit 0

# Skip if on the main branch
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
[[ "$CURRENT_BRANCH" == "$MAIN_BRANCH" ]] && exit 0

# Compare each index file against main's version
stale_files=()

for index_file in .kb/CLAUDE.md .decisions/CLAUDE.md; do
    [[ -f "$index_file" ]] || continue

    # Get main's version of this file
    main_content="$(git show "$MAIN_BRANCH:$index_file" 2>/dev/null)" || continue

    # Count data rows (lines starting with "| " that aren't headers/separators)
    count_data_rows() {
        echo "$1" | grep -c '^| [^-]' 2>/dev/null || echo "0"
    }

    local_rows="$(count_data_rows "$(cat "$index_file")")"
    main_rows="$(count_data_rows "$main_content")"

    if [[ "$main_rows" -gt "$local_rows" ]]; then
        diff_count=$((main_rows - local_rows))
        stale_files+=("$index_file ($diff_count new entries on $MAIN_BRANCH)")
    elif [[ "$main_content" != "$(cat "$index_file")" ]]; then
        # Same row count but different content — entries may have been updated
        # Only warn if main has lines not present locally
        main_only="$(diff <(cat "$index_file") <(echo "$main_content") 2>/dev/null | grep '^>' | grep -c '^> |' || echo "0")"
        if [[ "$main_only" -gt 0 ]]; then
            stale_files+=("$index_file ($main_only entries differ from $MAIN_BRANCH)")
        fi
    fi
done

if [[ ${#stale_files[@]} -gt 0 ]]; then
    echo "⚠  KB/decisions indexes are behind $MAIN_BRANCH:"
    for f in "${stale_files[@]}"; do
        echo "   - $f"
    done
    echo "   Consider: git merge $MAIN_BRANCH  (to pick up latest entries)"
fi

exit 0
