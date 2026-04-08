#!/usr/bin/env bash
# vallorcine upgrade script
# Downloads the latest release from the source repository and applies it.
# Called by the /upgrade slash command — not intended to be run directly.
#
# Usage:
#   bash .claude/upgrade.sh                        # check and apply latest
#   bash .claude/upgrade.sh --check                # check only, don't apply
#   bash .claude/upgrade.sh --version v0.3.0       # upgrade to a specific tag

set -euo pipefail

# ── Arguments ─────────────────────────────────────────────────────────────────

CHECK_ONLY=0
TARGET_VERSION=""
APPLY=0
KIT_ROOT_APPLY=""
FROM_VERSION=""
TO_VERSION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)            CHECK_ONLY=1 ;;
        --version)          TARGET_VERSION="$2"; shift ;;
        --apply)            APPLY=1 ;;
        --kit-root)         KIT_ROOT_APPLY="$2"; shift ;;
        --project-root)     PROJECT_ROOT_ARG="$2"; shift ;;
        --from-version)     FROM_VERSION="$2"; shift ;;
        --to-version)       TO_VERSION="$2"; shift ;;
        *) ;;
    esac
    shift
done

# ── Colour helpers ────────────────────────────────────────────────────────────

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

# ── Locate project root (where .claude/ lives) ────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# In --apply mode PROJECT_ROOT is passed explicitly; otherwise derive from SCRIPT_DIR
if [[ $APPLY -eq 1 && -n "${PROJECT_ROOT_ARG:-}" ]]; then
    PROJECT_ROOT="$PROJECT_ROOT_ARG"
else
    PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"   # .claude/ is one level down from project root
fi

# ── Read installed metadata (skipped in --apply mode — PROJECT_ROOT is known) ─

if [[ $APPLY -eq 0 ]]; then
    VERSION_FILE="$SCRIPT_DIR/.vallorcine-version"
    SOURCE_FILE="$SCRIPT_DIR/.vallorcine-source"

    if [[ ! -f "$VERSION_FILE" ]]; then
        echo -e "${RED}Error:${NC} .claude/.vallorcine-version not found."
        echo "Re-run install.sh to restore the version stamp."
        exit 1
    fi

    if [[ ! -f "$SOURCE_FILE" ]]; then
        echo -e "${RED}Error:${NC} .claude/.vallorcine-source not found."
        echo "This file is written by install.sh from the kit package."
        echo "Re-install from a release zip to restore it."
        exit 1
    fi

    INSTALLED_VERSION="$(cat "$VERSION_FILE")"
    REPO_URL="$(grep '^repo=' "$SOURCE_FILE" | cut -d= -f2-)"
    API_URL="$(grep '^api=' "$SOURCE_FILE" | cut -d= -f2-)"

    if [[ -z "$REPO_URL" ]]; then
        echo -e "${RED}Error:${NC} repo URL not found in .vallorcine-source."
        exit 1
    fi

    # Normalize SSH URLs to OWNER/REPO for gh CLI compatibility
    # git@github.com:owner/repo.git → owner/repo
    # https://github.com/owner/repo.git → owner/repo
    GH_REPO="$(echo "$REPO_URL" | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')"
fi

# ── Fetch / compare / download / exec (skipped when called with --apply) ──────

if [[ $APPLY -eq 0 ]]; then

echo ""
echo -e "${BLUE}vallorcine upgrade check${NC}"
echo -e "  Installed : v${INSTALLED_VERSION}"
echo -e "  Source    : ${REPO_URL}"
echo "────────────────────────────────────────────────"
echo ""
echo "── Checking for releases ────────────────────────"

LATEST_VERSION=""
RELEASE_ZIP_URL=""
RELEASE_NOTES=""

# Try gh CLI first (plain text output — no --json flag, no python3)
if command -v gh &>/dev/null && gh auth status &>/dev/null 2>&1; then
    echo "  Using gh CLI..."

    if [[ -n "$TARGET_VERSION" ]]; then
        RELEASE_TAG="$TARGET_VERSION"
    else
        # Get latest release tag from plain text listing
        RELEASE_TAG="$(gh release list --repo "$GH_REPO" --limit 1 2>/dev/null | awk '{print $1}')"
    fi

    if [[ -n "$RELEASE_TAG" ]]; then
        LATEST_VERSION="${RELEASE_TAG#v}"

        # Get release notes (plain text body)
        RELEASE_NOTES="$(gh release view "$RELEASE_TAG" --repo "$GH_REPO" 2>/dev/null \
            | sed -n '/^--$/,$ p' | tail -n +2 | head -20)"

        # Download URL: construct from known GitHub pattern
        RELEASE_ZIP_URL="https://github.com/${GH_REPO}/releases/download/${RELEASE_TAG}/vallorcine-${RELEASE_TAG}.zip"

        # Verify the zip asset actually exists (HEAD request)
        if ! curl -sfI "$RELEASE_ZIP_URL" >/dev/null 2>&1; then
            RELEASE_ZIP_URL=""
        fi
    fi
fi

# Fallback to curl + GitHub API (parse JSON with sed/grep — no python3)
if [[ -z "$LATEST_VERSION" ]] && [[ -n "$API_URL" ]]; then
    echo "  Using curl + GitHub API..."

    if [[ -n "$TARGET_VERSION" ]]; then
        TAG="${TARGET_VERSION#v}"
        RELEASE_JSON="$(curl -sf "${API_URL}/tags/v${TAG}" 2>/dev/null || echo "")"
    else
        RELEASE_JSON="$(curl -sf "${API_URL}/latest" 2>/dev/null || echo "")"
    fi

    if [[ -n "$RELEASE_JSON" ]]; then
        # Extract tag_name (strip quotes and leading v)
        LATEST_VERSION="$(echo "$RELEASE_JSON" \
            | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' \
            | head -1 \
            | sed 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/')"

        # Extract body (first 500 chars)
        RELEASE_NOTES="$(echo "$RELEASE_JSON" \
            | grep -o '"body"[[:space:]]*:[[:space:]]*"[^"]*"' \
            | head -1 \
            | sed 's/.*"body"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' \
            | head -c 500 \
            | sed 's/\\n/\n/g')"

        # Extract first .zip asset URL
        RELEASE_ZIP_URL="$(echo "$RELEASE_JSON" \
            | grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*\.zip"' \
            | head -1 \
            | sed 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')"
    fi
fi

# Could not fetch
if [[ -z "$LATEST_VERSION" ]]; then
    echo -e "${RED}Error:${NC} Could not fetch release information."
    echo ""
    echo "Tried:"
    [[ -n "$API_URL" ]] && echo "  curl ${API_URL}/latest"
    echo "  gh release view --repo ${REPO_URL}"
    echo ""
    echo "Check your network connection and that the repository is accessible."
    exit 1
fi

# ── Version comparison ────────────────────────────────────────────────────────

compare_versions() {
    # Returns 0 if $1 == $2, 1 if $1 > $2, 2 if $1 < $2
    if [[ "$1" == "$2" ]]; then return 0; fi
    local IFS=.
    local i a=($1) b=($2)
    for ((i=0; i<${#a[@]}; i++)); do
        if ((10#${a[i]:-0} > 10#${b[i]:-0})); then return 1; fi
        if ((10#${a[i]:-0} < 10#${b[i]:-0})); then return 2; fi
    done
    return 0
}

CMP=0
compare_versions "$LATEST_VERSION" "$INSTALLED_VERSION" || CMP=$?

if [[ $CMP -eq 0 ]]; then
    echo -e "  ${GREEN}Already up to date.${NC} v${INSTALLED_VERSION} is the latest release."
    echo ""
    exit 0
fi

if [[ $CMP -eq 2 ]]; then
    echo -e "  ${YELLOW}Installed version (v${INSTALLED_VERSION}) is newer than latest release (v${LATEST_VERSION}).${NC}"
    echo "  This can happen on development installs. No action taken."
    echo ""
    exit 0
fi

# Newer version available
echo -e "  Installed : v${INSTALLED_VERSION}"
echo -e "  Available : v${LATEST_VERSION}  ${GREEN}(new)${NC}"
echo ""

if [[ -n "$RELEASE_NOTES" ]]; then
    echo "── Release notes ────────────────────────────────"
    echo "$RELEASE_NOTES"
    echo "─────────────────────────────────────────────────"
    echo ""
fi

if [[ "$CHECK_ONLY" == "1" ]]; then
    echo "  (check-only mode — no changes made)"
    echo ""
    exit 0
fi

if [[ -z "$RELEASE_ZIP_URL" ]]; then
    echo -e "${RED}Error:${NC} No zip asset found in release v${LATEST_VERSION}."
    echo "Check the release on GitHub: ${REPO_URL}/releases/tag/v${LATEST_VERSION}"
    exit 1
fi

# ── Download release zip ──────────────────────────────────────────────────────

TMPDIR_UPGRADE="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_UPGRADE"' EXIT

ZIP_FILE="$TMPDIR_UPGRADE/vallorcine-v${LATEST_VERSION}.zip"

echo "── Downloading v${LATEST_VERSION} ───────────────────────"

# Try gh CLI first
DOWNLOAD_OK=0
if command -v gh &>/dev/null && gh auth status &>/dev/null 2>&1; then
    if gh release download "v${LATEST_VERSION}" \
        --repo "$GH_REPO" \
        --pattern "*.zip" \
        --dir "$TMPDIR_UPGRADE" 2>/dev/null; then
        # gh may save with the original filename — find it
        ZIP_FILE="$(find "$TMPDIR_UPGRADE" -name "*.zip" | head -1)"
        DOWNLOAD_OK=1
    fi
fi

# Fallback to curl
if [[ "$DOWNLOAD_OK" == "0" ]]; then
    if curl -fsSL "$RELEASE_ZIP_URL" -o "$ZIP_FILE" 2>/dev/null; then
        DOWNLOAD_OK=1
    fi
fi

if [[ "$DOWNLOAD_OK" == "0" ]] || [[ ! -f "$ZIP_FILE" ]]; then
    echo -e "${RED}Error:${NC} Download failed."
    echo "URL: $RELEASE_ZIP_URL"
    echo "Try downloading manually: ${REPO_URL}/releases/tag/v${LATEST_VERSION}"
    exit 1
fi

ZIP_SIZE="$(du -sh "$ZIP_FILE" | cut -f1)"
echo -e "  Downloaded: $(basename "$ZIP_FILE")  (${ZIP_SIZE})"

# ── Extract ───────────────────────────────────────────────────────────────────

EXTRACT_DIR="$TMPDIR_UPGRADE/extracted"
mkdir -p "$EXTRACT_DIR"
unzip -q "$ZIP_FILE" -d "$EXTRACT_DIR"

# Find the kit root inside the zip (may be wrapped in a subdirectory)
KIT_ROOT="$(find "$EXTRACT_DIR" -name "install.sh" -maxdepth 3 | head -1 | xargs dirname)"

if [[ -z "$KIT_ROOT" ]]; then
    echo -e "${RED}Error:${NC} Could not locate install.sh in the downloaded zip."
    echo "The release zip may be malformed."
    exit 1
fi

echo -e "  Extracted to: $TMPDIR_UPGRADE"

# ── Run upgrade from the NEW script ───────────────────────────────────────────
# Critical: we exec the new upgrade.sh so the rest of the upgrade
# runs with the new version's logic, not the old installed version's logic.

NEW_UPGRADE_SCRIPT="$KIT_ROOT/upgrade.sh"
if [[ ! -f "$NEW_UPGRADE_SCRIPT" ]]; then
    # Older kit versions may not have upgrade.sh — fall back to install logic below
    NEW_UPGRADE_SCRIPT=""
fi

if [[ -n "$NEW_UPGRADE_SCRIPT" ]]; then
    exec bash "$NEW_UPGRADE_SCRIPT" \
        --apply \
        --kit-root "$KIT_ROOT" \
        --project-root "$PROJECT_ROOT" \
        --from-version "$INSTALLED_VERSION" \
        --to-version "$LATEST_VERSION"
fi

fi  # end APPLY -eq 0 block

# ── Apply (reached when called with --apply from new script, or no new script) ─

# If we're not in apply mode and got here (no new upgrade.sh in zip), use extracted kit
if [[ $APPLY -eq 0 ]]; then
    KIT_ROOT_APPLY="$KIT_ROOT"
    FROM_VERSION="$INSTALLED_VERSION"
    TO_VERSION="$LATEST_VERSION"
fi

echo ""
echo "── Applying v${TO_VERSION} ──────────────────────────────"

updated=0
removed=0
skipped_user=0

apply_file() {
    local src="$1"
    local dst="$2"
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    echo -e "  ${GREEN}update${NC} $dst"
    ((updated++)) || true
}

# Kit-managed files — always overwrite
echo "  Updating skills..."
for d in "$KIT_ROOT_APPLY"/skills/*/; do
    [[ -d "$d" ]] || continue
    skill_name="$(basename "$d")"
    [[ -f "$d/SKILL.md" ]] && apply_file "$d/SKILL.md" "$PROJECT_ROOT/.claude/skills/$skill_name/SKILL.md"
done

echo "  Updating agents..."
for f in "$KIT_ROOT_APPLY"/agents/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.claude/agents/$(basename "$f")"
done

echo "  Updating rules..."
for f in "$KIT_ROOT_APPLY"/rules/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.claude/rules/$(basename "$f")"
done

echo "  Updating scripts..."
while IFS= read -r f; do
    [[ -f "$f" ]] || continue
    # Preserve subdirectory structure (e.g. scripts/narrative/model.py)
    rel="${f#"$KIT_ROOT_APPLY"/scripts/}"
    apply_file "$f" "$PROJECT_ROOT/.claude/scripts/$rel"
done < <(find "$KIT_ROOT_APPLY/scripts" -type f \( -name '*.sh' -o -name '*.py' -o -name '*.js' \))

echo "  Updating upgrade.sh..."
apply_file "$KIT_ROOT_APPLY/upgrade.sh" "$PROJECT_ROOT/.claude/upgrade.sh"
chmod +x "$PROJECT_ROOT/.claude/upgrade.sh"

# Seed files — only if not yet user-populated
# .kb/CLAUDE.md: skip if Topic Map has any entries (user has added topics)
KB_INDEX="$PROJECT_ROOT/.kb/CLAUDE.md"
if [[ -f "$KB_INDEX" ]]; then
    if grep -q "^| " "$KB_INDEX" 2>/dev/null; then
        echo -e "  ${YELLOW}skip${NC}  .kb/CLAUDE.md  (has user topics — not overwritten)"
        ((skipped_user++)) || true
    else
        apply_file "$KIT_ROOT_APPLY/kb/CLAUDE.md" "$KB_INDEX"
    fi
fi

# .decisions/CLAUDE.md: skip if it has any decision rows
DECISIONS_INDEX="$PROJECT_ROOT/.decisions/CLAUDE.md"
if [[ -f "$DECISIONS_INDEX" ]]; then
    if grep -q "^| " "$DECISIONS_INDEX" 2>/dev/null; then
        echo -e "  ${YELLOW}skip${NC}  .decisions/CLAUDE.md  (has user decisions — not overwritten)"
        ((skipped_user++)) || true
    else
        apply_file "$KIT_ROOT_APPLY/decisions/CLAUDE.md" "$DECISIONS_INDEX"
    fi
fi

# .spec/CLAUDE.md: skip if it has any domain rows
SPEC_INDEX="$PROJECT_ROOT/.spec/CLAUDE.md"
if [[ -f "$SPEC_INDEX" ]]; then
    if grep -q "^| " "$SPEC_INDEX" 2>/dev/null; then
        echo -e "  ${YELLOW}skip${NC}  .spec/CLAUDE.md  (has user domains — not overwritten)"
        ((skipped_user++)) || true
    else
        apply_file "$KIT_ROOT_APPLY/spec/CLAUDE.md" "$SPEC_INDEX"
    fi
else
    # First install of spec system — create seed
    if [[ -f "$KIT_ROOT_APPLY/spec/CLAUDE.md" ]]; then
        mkdir -p "$PROJECT_ROOT/.spec"
        apply_file "$KIT_ROOT_APPLY/spec/CLAUDE.md" "$SPEC_INDEX"
    fi
fi

# _refs are safe to update — they're reference fragments, not user content
for f in "$KIT_ROOT_APPLY"/kb/_refs/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.kb/_refs/$(basename "$f")"
done

# ── Patch settings.json hooks (#22) ──────────────────────────────────────────
# Only touch vallorcine-managed hooks (scripts referencing .claude/scripts/).
# User hooks are preserved untouched.

SETTINGS_FILE="$PROJECT_ROOT/.claude/settings.json"

if [[ -f "$SETTINGS_FILE" ]]; then
    echo "  Patching settings.json hooks..."
    patched=0

    # ── Migrate old direct script references to wrappers ─────────────
    if grep -qF "token-stop-hook.sh" "$SETTINGS_FILE" 2>/dev/null; then
        sed -i 's|token-stop-hook\.sh|token-hook-wrapper.sh|g' "$SETTINGS_FILE"
        echo -e "  ${GREEN}migrate${NC} Stop hook → token-hook-wrapper.sh"
        ((patched++)) || true
    fi
    if grep -q 'statusline\.sh[^a-z]' "$SETTINGS_FILE" 2>/dev/null && \
       ! grep -qF "statusline-wrapper.sh" "$SETTINGS_FILE" 2>/dev/null; then
        sed -i 's|scripts/statusline\.sh|scripts/statusline-wrapper.sh|g' "$SETTINGS_FILE"
        echo -e "  ${GREEN}migrate${NC} Status line → statusline-wrapper.sh"
        ((patched++)) || true
    fi

    # ── Remove stale hooks (e.g. retired dashboard) ──────────────────
    if grep -qF "dashboard-stop-hook" "$SETTINGS_FILE" 2>/dev/null; then
        if command -v jq &>/dev/null; then
            jq '(.hooks.Stop // []) |= [.[] | select(.hooks | all(.command | test("dashboard") | not))]' \
                "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${RED}remove${NC} stale dashboard-stop-hook"
            ((patched++)) || true
        else
            echo -e "  ${YELLOW}warn${NC}  dashboard-stop-hook is stale — remove manually (jq not available)"
        fi
    fi

    # ── Add missing hooks (requires jq for safe JSON manipulation) ───
    if command -v jq &>/dev/null; then
        # Stop hook (token tracking)
        if ! grep -qF "token-hook-wrapper.sh" "$SETTINGS_FILE" 2>/dev/null; then
            jq '.hooks.Stop = ((.hooks.Stop // []) + [{
                "hooks": [{
                    "type": "command",
                    "command": "bash .claude/scripts/token-hook-wrapper.sh"
                }]
            }])' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${GREEN}add${NC}    Stop hook (token-hook-wrapper.sh)"
            ((patched++)) || true
        fi

        # Status line
        if ! grep -qF "statusline" "$SETTINGS_FILE" 2>/dev/null; then
            jq '.statusLine = {
                "type": "command",
                "command": "bash .claude/scripts/statusline-wrapper.sh"
            }' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${GREEN}add${NC}    Status line (statusline-wrapper.sh)"
            ((patched++)) || true
        fi

        # SubagentStart/SubagentStop hooks
        if ! grep -qF "subagent-hook" "$SETTINGS_FILE" 2>/dev/null; then
            jq '.hooks.SubagentStart = ((.hooks.SubagentStart // []) + [{
                "hooks": [{
                    "type": "command",
                    "command": "bash .claude/scripts/subagent-hook-wrapper.sh"
                }]
            }]) | .hooks.SubagentStop = ((.hooks.SubagentStop // []) + [{
                "hooks": [{
                    "type": "command",
                    "command": "bash .claude/scripts/subagent-hook-wrapper.sh"
                }]
            }])' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${GREEN}add${NC}    SubagentStart/SubagentStop hooks"
            ((patched++)) || true
        fi

        # Script permissions
        if ! grep -qF "curate-scan.sh" "$SETTINGS_FILE" 2>/dev/null; then
            jq '.permissions.allow = ((.permissions.allow // []) + [
                "Bash(bash .claude/scripts/curate-scan.sh:*)",
                "Bash(bash .claude/scripts/kb-freshness-check.sh:*)",
                "Bash(bash .claude/scripts/version-check.sh:*)",
                "Bash(bash .claude/scripts/ensure-merge-driver.sh:*)",
                "Bash(bash .claude/scripts/adr-validate.sh:*)",
                "Bash(bash .claude/scripts/index-verify.sh:*)",
                "Bash(bash .claude/scripts/narrative-wrapper.sh:*)"
            ] | unique)' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"
            echo -e "  ${GREEN}add${NC}    Script permissions"
            ((patched++)) || true
        fi
    else
        # No jq — can only do sed-based migrations (already done above)
        if ! grep -qF "subagent-hook" "$SETTINGS_FILE" 2>/dev/null || \
           ! grep -qF "statusline" "$SETTINGS_FILE" 2>/dev/null; then
            echo -e "  ${YELLOW}warn${NC}  settings.json may be missing hooks — jq not available for safe patching"
            echo -e "         Re-run install.sh or add hooks manually"
        fi
    fi

    if [[ $patched -eq 0 ]]; then
        echo -e "  ${YELLOW}skip${NC}  settings.json hooks already up to date"
    fi
fi

# ── Remove stale files ────────────────────────────────────────────────────────
# Any file listed in the old installed manifest but absent from the new kit
# manifest is a file that was removed from the kit — delete it.

OLD_MANIFEST="$PROJECT_ROOT/.claude/.vallorcine-manifest"
NEW_MANIFEST="$KIT_ROOT_APPLY/MANIFEST"

if [[ -f "$OLD_MANIFEST" && -f "$NEW_MANIFEST" ]]; then
    echo "  Checking for stale files..."
    while IFS= read -r rel_path; do
        # Skip comment lines and blank lines
        [[ "$rel_path" =~ ^#.*$ || -z "$rel_path" ]] && continue

        # Safety: only remove files under known kit-managed prefixes.
        # This prevents a corrupted or malformed manifest from deleting
        # user files, other plugins' commands, or project source code.
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
                echo -e "  ${YELLOW}skip${NC}  $rel_path  (not a known kit path — refusing to remove)"
                ((skipped_user++)) || true
                continue
                ;;
        esac

        # If the path is not in the new manifest, it was removed from the kit
        if ! grep -qF "$rel_path" "$NEW_MANIFEST" 2>/dev/null; then
            target_file="$PROJECT_ROOT/$rel_path"
            if [[ -f "$target_file" ]]; then
                rm "$target_file"
                echo -e "  ${RED}remove${NC} $rel_path  (removed from kit)"
                ((removed++)) || true
            fi
        fi
    done < "$OLD_MANIFEST"
fi

# Update manifest
if [[ -f "$NEW_MANIFEST" ]]; then
    cp "$NEW_MANIFEST" "$PROJECT_ROOT/.claude/.vallorcine-manifest"
    echo -e "  ${GREEN}update${NC} .claude/.vallorcine-manifest"
fi

# Update version stamp and source file
echo "$TO_VERSION" > "$PROJECT_ROOT/.claude/.vallorcine-version"
echo -e "  ${GREEN}update${NC} .claude/.vallorcine-version  (v${FROM_VERSION} → v${TO_VERSION})"

if [[ -f "$KIT_ROOT_APPLY/.vallorcine-source" ]]; then
    cp "$KIT_ROOT_APPLY/.vallorcine-source" "$PROJECT_ROOT/.claude/.vallorcine-source"
    echo -e "  ${GREEN}update${NC} .claude/.vallorcine-source"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "────────────────────────────────────────────────"
echo -e "${GREEN}Upgrade complete.${NC}  v${FROM_VERSION} → v${TO_VERSION}"
echo -e "  Files updated  : $updated"
[[ $removed -gt 0 ]]      && echo -e "  Files removed  : $removed  (stale from previous version)"
[[ $skipped_user -gt 0 ]] && echo -e "  Skipped (user) : $skipped_user"
echo ""
echo -e "  ${BLUE}Note:${NC} vallorcine follows a fail-forward upgrade policy — rollbacks are"
echo -e "  not supported because removing new files and restoring old ones risks"
echo -e "  corrupting user data. If this upgrade introduced a problem, upgrade"
echo -e "  again once a fix is released."
echo -e "  To pin to a specific version: bash .claude/upgrade.sh --version vX.Y.Z"
echo ""
