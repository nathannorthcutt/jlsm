#!/usr/bin/env bash
# vallorcine uninstall script
# Removes all kit-managed files from a project while preserving user data.
#
# Usage:
#   bash .claude/scripts/uninstall.sh              # interactive uninstall
#   bash .claude/scripts/uninstall.sh --dry-run    # preview what would be removed
#   bash .claude/scripts/uninstall.sh --yes        # skip confirmation prompt

set -euo pipefail

# ── Arguments ─────────────────────────────────────────────────────────────────

DRY_RUN=0
YES=0

for arg in "$@"; do
    case "$arg" in
        --dry-run)  DRY_RUN=1 ;;
        --yes)      YES=1 ;;
        *) ;;
    esac
done

# ── Colour helpers ────────────────────────────────────────────────────────────

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

# ── Locate project root ──────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# .claude/scripts/ → .claude/ → project root
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"

# ── Safety guard: refuse to run in the vallorcine repo itself ─────────────────

if [[ -f "$PROJECT_ROOT/install.sh" && -f "$PROJECT_ROOT/VERSION" && -f "$PROJECT_ROOT/MANIFEST" ]]; then
    echo ""
    echo -e "${RED}Error:${NC} This appears to be the vallorcine source repository."
    echo "  Uninstall is intended for projects where vallorcine was installed as a plugin."
    echo ""
    exit 1
fi

# ── Read manifest ────────────────────────────────────────────────────────────

MANIFEST_FILE="$PROJECT_ROOT/.claude/.vallorcine-manifest"

if [[ ! -f "$MANIFEST_FILE" ]]; then
    echo ""
    echo -e "${RED}Error:${NC} .claude/.vallorcine-manifest not found."
    echo "  Cannot determine which files to remove without the manifest."
    echo "  Is vallorcine installed in this project?"
    echo ""
    exit 1
fi

# ── Header ───────────────────────────────────────────────────────────────────

echo ""
if [[ "$DRY_RUN" == "1" ]]; then
    echo -e "${BLUE}vallorcine uninstall — dry run (no changes will be made)${NC}"
else
    echo -e "${BLUE}vallorcine uninstall${NC}"
fi
echo "────────────────────────────────────────────────"

# ── Collect files to remove ──────────────────────────────────────────────────

removed=0
skipped=0
preserved=()

remove_file() {
    local path="$1"
    local label="${2:-$path}"
    if [[ -f "$path" ]]; then
        if [[ "$DRY_RUN" == "1" ]]; then
            echo -e "  ${RED}remove${NC} $label"
        else
            rm "$path"
            echo -e "  ${RED}remove${NC} $label"
        fi
        ((removed++)) || true
    fi
}

remove_dir_if_empty() {
    local dir="$1"
    if [[ -d "$dir" ]] && [[ -z "$(ls -A "$dir" 2>/dev/null)" ]]; then
        if [[ "$DRY_RUN" == "1" ]]; then
            echo -e "  ${RED}rmdir${NC}  $dir"
        else
            rmdir "$dir"
            echo -e "  ${RED}rmdir${NC}  $dir"
        fi
    fi
}

# ── Step 1: Remove manifest-listed files ──────────────────────────────────────

echo ""
echo "── Manifest files ───────────────────────────────"

while IFS= read -r rel_path; do
    [[ "$rel_path" =~ ^#.*$ || -z "$rel_path" ]] && continue

    # Safety: only remove files under known kit-managed prefixes
    case "$rel_path" in
        .claude/commands/*|\
        .claude/skills/*|\
        .claude/agents/*|\
        .claude/rules/*|\
        .claude/scripts/*|\
        .claude/watchers/*|\
        .claude/upgrade.sh)
            ;; # known kit prefix — safe to remove
        *)
            echo -e "  ${YELLOW}skip${NC}  $rel_path  (not a known kit path)"
            ((skipped++)) || true
            continue
            ;;
    esac

    remove_file "$PROJECT_ROOT/$rel_path" "$rel_path"

    # Also clean up pre-migration command file if it exists (commands/ → skills/)
    case "$rel_path" in
        .claude/skills/*/SKILL.md)
            skill_name="$(basename "$(dirname "$rel_path")")"
            old_cmd="$PROJECT_ROOT/.claude/commands/$skill_name.md"
            if [[ -f "$old_cmd" ]]; then
                remove_file "$old_cmd" ".claude/commands/$skill_name.md (stale pre-migration)"
            fi
            ;;
    esac
done < "$MANIFEST_FILE"

# Clean up commands/ directory if empty after migration removal
if [[ -d "$PROJECT_ROOT/.claude/commands" ]]; then
    remove_dir_if_empty "$PROJECT_ROOT/.claude/commands"
fi

# ── Step 2: Remove empty skill directories ────────────────────────────────────

echo ""
echo "── Empty directories ──────────────────────────────"

# Clean up empty skill dirs after SKILL.md removal
if [[ -d "$PROJECT_ROOT/.claude/skills" ]]; then
    for d in "$PROJECT_ROOT/.claude/skills"/*/; do
        [[ -d "$d" ]] || continue
        remove_dir_if_empty "$d"
    done
    remove_dir_if_empty "$PROJECT_ROOT/.claude/skills"
fi

# Clean up empty agents dir
remove_dir_if_empty "$PROJECT_ROOT/.claude/agents"

# Clean up empty rules dir
remove_dir_if_empty "$PROJECT_ROOT/.claude/rules"

# Clean up empty scripts dir
remove_dir_if_empty "$PROJECT_ROOT/.claude/scripts"

# Clean up empty watchers dir
remove_dir_if_empty "$PROJECT_ROOT/.claude/watchers"

# ── Step 3: Remove metadata files ────────────────────────────────────────────

echo ""
echo "── Metadata ─────────────────────────────────────"

remove_file "$PROJECT_ROOT/.claude/.vallorcine-version" ".claude/.vallorcine-version"
remove_file "$PROJECT_ROOT/.claude/.vallorcine-manifest" ".claude/.vallorcine-manifest"
remove_file "$PROJECT_ROOT/.claude/.vallorcine-source" ".claude/.vallorcine-source"

# ── Step 4: Clean settings.json Stop hook ────────────────────────────────────

echo ""
echo "── Settings.json ──────────────────────────────────"

SETTINGS_FILE="$PROJECT_ROOT/.claude/settings.json"
HOOK_MARKER="token-stop-hook.sh"

if [[ -f "$SETTINGS_FILE" ]] && grep -qF "$HOOK_MARKER" "$SETTINGS_FILE" 2>/dev/null; then
    if [[ "$DRY_RUN" == "1" ]]; then
        echo -e "  ${RED}clean${NC}  settings.json (remove Stop hook containing $HOOK_MARKER)"
    else
        if command -v jq &>/dev/null; then
            jq '
                if .hooks.Stop then
                    .hooks.Stop |= map(
                        select(.hooks | any(.command | contains("token-stop-hook.sh")) | not)
                    ) |
                    if .hooks.Stop == [] then del(.hooks.Stop) else . end |
                    if .hooks == {} then del(.hooks) else . end
                else .
                end
            ' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${RED}clean${NC}  settings.json (Stop hook removed via jq)"
        else
            grep -v "$HOOK_MARKER" "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${RED}clean${NC}  settings.json (Stop hook removed via grep)"
        fi
    fi
else
    echo -e "  ${YELLOW}skip${NC}  settings.json (no vallorcine hook found)"
fi

# Clean statusLine config
STATUSLINE_MARKER="statusline.sh"
if [[ -f "$SETTINGS_FILE" ]] && grep -qF "$STATUSLINE_MARKER" "$SETTINGS_FILE" 2>/dev/null; then
    if [[ "$DRY_RUN" == "1" ]]; then
        echo -e "  ${RED}clean${NC}  settings.json (remove statusLine)"
    else
        if command -v jq &>/dev/null; then
            jq 'del(.statusLine)' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${RED}clean${NC}  settings.json (statusLine removed)"
        fi
    fi
fi

# Clean token state file
remove_file "$PROJECT_ROOT/.claude/.token-state" ".claude/.token-state"

# ── Step 5: Clean git config ────────────────────────────────────────────────

echo ""
echo "── Git config ─────────────────────────────────────"

if git -C "$PROJECT_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    if git -C "$PROJECT_ROOT" config --get merge.vallorcine-index.name >/dev/null 2>&1; then
        if [[ "$DRY_RUN" == "1" ]]; then
            echo -e "  ${RED}unset${NC}  merge.vallorcine-index.name"
            echo -e "  ${RED}unset${NC}  merge.vallorcine-index.driver"
        else
            git -C "$PROJECT_ROOT" config --unset merge.vallorcine-index.name 2>/dev/null || true
            git -C "$PROJECT_ROOT" config --unset merge.vallorcine-index.driver 2>/dev/null || true
            # Remove the section if empty
            git -C "$PROJECT_ROOT" config --remove-section merge.vallorcine-index 2>/dev/null || true
            echo -e "  ${RED}unset${NC}  merge.vallorcine-index (name + driver)"
        fi
    else
        echo -e "  ${YELLOW}skip${NC}  merge driver not configured"
    fi
else
    echo -e "  ${YELLOW}skip${NC}  not a git repo"
fi

# Clean up dashboard state directory if it exists (legacy from pre-0.5.0)
if [[ -d "$PROJECT_ROOT/.claude/dashboard" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
        echo -e "  ${RED}remove${NC} .claude/dashboard/ (legacy directory)"
    else
        rm -rf "$PROJECT_ROOT/.claude/dashboard"
        echo -e "  ${RED}remove${NC} .claude/dashboard/ (legacy directory)"
    fi
fi

# ── Step 7: Clean .gitattributes ─────────────────────────────────────────────

echo ""
echo "── .gitattributes ───────────────────────────────"

GITATTRIBUTES="$PROJECT_ROOT/.gitattributes"
MARKER="# vallorcine merge driver"

if [[ -f "$GITATTRIBUTES" ]] && grep -qF "$MARKER" "$GITATTRIBUTES" 2>/dev/null; then
    if [[ "$DRY_RUN" == "1" ]]; then
        echo -e "  ${RED}clean${NC}  .gitattributes (remove vallorcine merge driver block)"
    else
        # Remove the vallorcine block: marker comment, subsequent comment/pattern lines
        awk '
            /^# vallorcine merge driver/ { skip=1; next }
            skip && /^#/ { next }
            skip && /^\./ { next }
            skip && /^[[:space:]]*$/ { next }
            { skip=0; print }
        ' "$GITATTRIBUTES" > "$GITATTRIBUTES.tmp"

        # Remove trailing blank lines
        if command -v tac &>/dev/null; then
            tac "$GITATTRIBUTES.tmp" | awk '/[^[:space:]]/{found=1} found' | tac > "$GITATTRIBUTES"
        else
            mv "$GITATTRIBUTES.tmp" "$GITATTRIBUTES"
        fi
        rm -f "$GITATTRIBUTES.tmp"

        # If file is now empty or whitespace-only, remove it
        if [[ ! -s "$GITATTRIBUTES" ]] || ! grep -q '[^[:space:]]' "$GITATTRIBUTES" 2>/dev/null; then
            rm -f "$GITATTRIBUTES"
            echo -e "  ${RED}remove${NC} .gitattributes (empty after cleanup)"
        else
            echo -e "  ${RED}clean${NC}  .gitattributes (merge driver block removed)"
        fi
    fi
else
    echo -e "  ${YELLOW}skip${NC}  .gitattributes (no vallorcine entries)"
fi

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
echo "────────────────────────────────────────────────"

if [[ "$DRY_RUN" == "1" ]]; then
    echo -e "${BLUE}Dry run complete.${NC}  $removed files would be removed."
    echo ""
    echo "Preserved (not touched by uninstall):"
else
    echo -e "${GREEN}Uninstall complete.${NC}  $removed files removed."
    echo ""
    echo "Preserved (still in place):"
fi

echo "  .kb/                   your knowledge base"
echo "  .decisions/            your architecture decisions"
echo "  .feature/              your in-progress feature work"
echo "  PROJECT-CONTEXT.md     your project context"
echo "  CLAUDE.md              your root config"
echo "  .claude/settings.json  non-vallorcine settings (if any)"

if [[ "$DRY_RUN" == "1" ]]; then
    echo ""
    echo "To apply: bash .claude/scripts/uninstall.sh --yes"
    echo ""
    exit 0
fi

# ── Step 8: Self-delete ──────────────────────────────────────────────────────

SELF_PATH="$PROJECT_ROOT/.claude/scripts/uninstall.sh"
if [[ -f "$SELF_PATH" ]]; then
    rm "$SELF_PATH"
fi

# Clean up .claude/ if empty
remove_dir_if_empty "$PROJECT_ROOT/.claude/scripts"
remove_dir_if_empty "$PROJECT_ROOT/.claude"

echo ""
echo "vallorcine has been removed from this project."
echo ""
