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

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)            CHECK_ONLY=1 ;;
        --version)          TARGET_VERSION="$2"; shift ;;
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
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"   # .claude/ is one level down from project root

# ── Read installed metadata ───────────────────────────────────────────────────

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

# ── Fetch latest release tag ──────────────────────────────────────────────────

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

# Try gh CLI first
if command -v gh &>/dev/null && gh auth status &>/dev/null 2>&1; then
    echo "  Using gh CLI..."

    if [[ -n "$TARGET_VERSION" ]]; then
        RELEASE_JSON="$(gh release view "$TARGET_VERSION" --repo "$REPO_URL" --json tagName,assets,body 2>/dev/null || echo "")"
    else
        RELEASE_JSON="$(gh release view --repo "$REPO_URL" --json tagName,assets,body 2>/dev/null || echo "")"
    fi

    if [[ -n "$RELEASE_JSON" ]]; then
        LATEST_VERSION="$(echo "$RELEASE_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['tagName'].lstrip('v'))")"
        RELEASE_NOTES="$(echo "$RELEASE_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('body','')[:500])")"
        RELEASE_ZIP_URL="$(echo "$RELEASE_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
assets = d.get('assets', [])
zips = [a['url'] for a in assets if a['name'].endswith('.zip')]
print(zips[0] if zips else '')
")"
    fi
fi

# Fallback to curl + GitHub API
if [[ -z "$LATEST_VERSION" ]] && [[ -n "$API_URL" ]]; then
    echo "  Using curl + GitHub API..."

    if [[ -n "$TARGET_VERSION" ]]; then
        TAG="${TARGET_VERSION#v}"
        RELEASE_JSON="$(curl -sf "${API_URL}/tags/v${TAG}" 2>/dev/null || echo "")"
    else
        RELEASE_JSON="$(curl -sf "${API_URL}/latest" 2>/dev/null || echo "")"
    fi

    if [[ -n "$RELEASE_JSON" ]]; then
        LATEST_VERSION="$(echo "$RELEASE_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('tag_name','').lstrip('v'))
")"
        RELEASE_NOTES="$(echo "$RELEASE_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('body','')[:500])
")"
        RELEASE_ZIP_URL="$(echo "$RELEASE_JSON" | python3 -c "
import sys, json
d = json.load(sys.stdin)
assets = d.get('assets', [])
zips = [a['browser_download_url'] for a in assets if a['name'].endswith('.zip')]
print(zips[0] if zips else '')
")"
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

compare_versions "$LATEST_VERSION" "$INSTALLED_VERSION"
CMP=$?

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
        --repo "$REPO_URL" \
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

# ── Apply (reached when called with --apply from new script, or no new script) ─

APPLY=0
KIT_ROOT_APPLY=""
FROM_VERSION=""
TO_VERSION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)            APPLY=1 ;;
        --kit-root)         KIT_ROOT_APPLY="$2"; shift ;;
        --project-root)     PROJECT_ROOT="$2"; shift ;;
        --from-version)     FROM_VERSION="$2"; shift ;;
        --to-version)       TO_VERSION="$2"; shift ;;
        *) ;;
    esac
    shift
done

# If we're not in apply mode and got here, use extracted kit
if [[ "$APPLY" == "0" ]]; then
    KIT_ROOT_APPLY="$KIT_ROOT"
    FROM_VERSION="$INSTALLED_VERSION"
    TO_VERSION="$LATEST_VERSION"
fi

[[ -z "$KIT_ROOT_APPLY" ]] && KIT_ROOT_APPLY="$KIT_ROOT"

echo ""
echo "── Applying v${TO_VERSION} ──────────────────────────────"

updated=0
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
echo "  Updating commands..."
for f in "$KIT_ROOT_APPLY"/commands/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.claude/commands/$(basename "$f")"
done

echo "  Updating agents..."
for f in "$KIT_ROOT_APPLY"/agents/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.claude/agents/$(basename "$f")"
done

echo "  Updating rules..."
for f in "$KIT_ROOT_APPLY"/rules/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.claude/rules/$(basename "$f")"
done

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

# _refs are safe to update — they're reference fragments, not user content
for f in "$KIT_ROOT_APPLY"/kb/_refs/*.md; do
    [[ -f "$f" ]] && apply_file "$f" "$PROJECT_ROOT/.kb/_refs/$(basename "$f")"
done

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
[[ $skipped_user -gt 0 ]] && echo -e "  Skipped (user) : $skipped_user"
echo ""
