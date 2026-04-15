#!/usr/bin/env bash
# vallorcine PreCompact hook — checkpoint feature state before context compaction
# Performance: <5ms (2 stat() calls + 1 file copy)
#
# Fires before Claude Code compacts the conversation context. Copies the
# active feature's status.md to .status-checkpoint.md so /feature-resume
# can recover after overflow.

cat > /dev/null  # consume stdin (required by hooks)

# Find active feature directory (skip archived)
ACTIVE=""
for d in .feature/*/; do
    [[ -f "$d/status.md" ]] || continue
    stage=$(grep -m1 '^stage:' "$d/status.md" 2>/dev/null | sed 's/stage: *//')
    [[ "$stage" == "archived" ]] && continue
    ACTIVE="$d"
    break
done

[[ -z "$ACTIVE" ]] && exit 0

# Checkpoint: atomic write via tmp + rename
cp "$ACTIVE/status.md" "$ACTIVE/.status-checkpoint.md.tmp" 2>/dev/null \
    && mv "$ACTIVE/.status-checkpoint.md.tmp" "$ACTIVE/.status-checkpoint.md" 2>/dev/null

exit 0
