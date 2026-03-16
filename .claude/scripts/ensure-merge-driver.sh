#!/usr/bin/env bash
# vallorcine merge driver setup check
# Ensures the index merge driver is registered in git config and .gitattributes.
# Idempotent — safe to run on every pipeline command. Silent when already set up.
#
# Usage: bash .claude/scripts/ensure-merge-driver.sh
#   Exit 0 always — setup is best-effort, never blocks.

# Must be in a git repo
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

DRIVER_SCRIPT=".claude/scripts/merge-driver-index.sh"

# Check the driver script exists
[[ -f "$DRIVER_SCRIPT" ]] || exit 0

# ── Register merge driver in git config if missing ───────────────────────────

CONFIGURED="$(git config --local merge.vallorcine-index.driver 2>/dev/null || echo "")"

if [[ -z "$CONFIGURED" ]]; then
    git config --local merge.vallorcine-index.name "vallorcine index merge (keep all rows)"
    git config --local merge.vallorcine-index.driver "bash $DRIVER_SCRIPT %O %A %B"
    echo "vallorcine: registered index merge driver in git config"
fi

# ── Add .gitattributes entries if missing ────────────────────────────────────

MARKER="# vallorcine merge driver"

if ! grep -qF "$MARKER" .gitattributes 2>/dev/null; then
    cat >> .gitattributes << 'GITATTR'

# vallorcine merge driver — scoped to managed index files only
# Auto-resolves concurrent table row additions by keeping all rows.
.kb/CLAUDE.md           merge=vallorcine-index
.kb/*/CLAUDE.md         merge=vallorcine-index
.kb/*/*/CLAUDE.md       merge=vallorcine-index
.decisions/CLAUDE.md    merge=vallorcine-index
GITATTR
    echo "vallorcine: added merge driver entries to .gitattributes"
fi

exit 0
