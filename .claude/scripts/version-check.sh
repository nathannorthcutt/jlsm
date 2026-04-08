#!/usr/bin/env bash
# vallorcine version skew check
# Compares installed vallorcine version against the main branch.
# Prints a warning if the current branch is behind main.
# Silent on success, missing data, or when not in a git repo.
#
# Usage: bash .claude/scripts/version-check.sh
#   Exit 0 always — this is advisory, never blocks.

VERSION_FILE=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find version file relative to script location
# Installed: .claude/scripts/version-check.sh → .claude/.vallorcine-version
# Repo:      scripts/version-check.sh → not applicable (skip)
if [[ -f "$SCRIPT_DIR/../.vallorcine-version" ]]; then
    VERSION_FILE="$SCRIPT_DIR/../.vallorcine-version"
elif [[ -f ".claude/.vallorcine-version" ]]; then
    VERSION_FILE=".claude/.vallorcine-version"
fi

[[ -z "$VERSION_FILE" ]] && exit 0

INSTALLED="$(cat "$VERSION_FILE" 2>/dev/null)"
[[ -z "$INSTALLED" ]] && exit 0

# Must be in a git repo
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

# Determine main branch name (main or master)
MAIN_BRANCH=""
for candidate in main master; do
    if git show-ref --verify --quiet "refs/heads/$candidate" 2>/dev/null; then
        MAIN_BRANCH="$candidate"
        break
    fi
done
[[ -z "$MAIN_BRANCH" ]] && exit 0

# Skip if already on the main branch
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
[[ "$CURRENT_BRANCH" == "$MAIN_BRANCH" ]] && exit 0

# Read version from main branch
MAIN_VERSION="$(git show "$MAIN_BRANCH:.claude/.vallorcine-version" 2>/dev/null)"
[[ -z "$MAIN_VERSION" ]] && exit 0

# Compare — only warn if main is ahead (installed < main)
if [[ "$INSTALLED" == "$MAIN_VERSION" ]]; then
    exit 0
fi

# Simple semver comparison: is MAIN_VERSION newer than INSTALLED?
version_gt() {
    local IFS=.
    local i
    local -a a=($1) b=($2)
    for ((i=0; i<${#a[@]}; i++)); do
        if ((10#${a[i]:-0} > 10#${b[i]:-0})); then return 0; fi
        if ((10#${a[i]:-0} < 10#${b[i]:-0})); then return 1; fi
    done
    return 1
}

if version_gt "$MAIN_VERSION" "$INSTALLED"; then
    echo "⚠  vallorcine version skew: this branch has v${INSTALLED}, ${MAIN_BRANCH} has v${MAIN_VERSION}"
    echo "   Run: bash .claude/upgrade.sh --apply --kit-root <kit-path> --project-root . --from-version ${INSTALLED} --to-version ${MAIN_VERSION}"
    echo "   Or merge ${MAIN_BRANCH} into this branch to pick up the upgrade."
fi

exit 0
