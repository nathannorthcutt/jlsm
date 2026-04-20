#!/usr/bin/env bash
# adr-linkage.sh — Map spec → ADR → WD relationships.
# Output: TSV to stdout, one row per (spec_id, adr_slug) pair.
# Columns: spec_id | adr_slug | adr_exists | adr_status | wd_id | wd_status
#
# Usage: bash tools/spec-audit/adr-linkage.sh

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

MANIFEST=".spec/registry/manifest.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: $MANIFEST not found" >&2
  exit 1
fi

# Build WD lookup: adr_slug → wd_id
# Scan all WD files for produces entries of type adr
declare -A ADR_TO_WD
declare -A WD_STATUS
for wd_file in .work/*/WD-*.md; do
  [[ -f "$wd_file" ]] || continue
  wd_id=$(grep -oP '^id:\s*\K\S+' "$wd_file" 2>/dev/null || echo "")
  wd_status=$(grep -oP '^status:\s*\K\S+' "$wd_file" 2>/dev/null || echo "")
  [[ -z "$wd_id" ]] && continue
  WD_STATUS["$wd_id"]="$wd_status"
  # Extract adr slugs from produces
  while IFS= read -r slug; do
    [[ -n "$slug" ]] && ADR_TO_WD["$slug"]="$wd_id"
  done < <(grep -oP 'type:\s*adr.*?slug:\s*"?\K[^"}\s]+' "$wd_file" 2>/dev/null || true)
done

# Header
printf "spec_id\tadr_slug\tadr_exists\tadr_status\twd_id\twd_status\n"

# Extract decision_refs from each spec
python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
for fid in sorted(data.get('features', {}), key=lambda x: int(x[1:])):
    info = data['features'][fid]
    spec_file = info.get('latest_file', '')
    if not spec_file:
        continue
    # Read frontmatter
    lines = []
    in_front = False
    try:
        with open('.spec/' + spec_file) as sf:
            for line in sf:
                line = line.rstrip()
                if line == '---':
                    if in_front:
                        break
                    in_front = True
                    continue
                if in_front:
                    lines.append(line)
        meta = json.loads('\n'.join(lines))
    except:
        meta = {}
    for ref in meta.get('decision_refs', []):
        print(f'{fid}\t{ref}')
" | while IFS=$'\t' read -r spec_id adr_slug; do
  # Check ADR exists and status
  adr_file=".decisions/$adr_slug/adr.md"
  if [[ -f "$adr_file" ]]; then
    adr_exists="yes"
    adr_status=$(grep -oP '^status:\s*"?\K[^"]+' "$adr_file" 2>/dev/null | head -1 || echo "unknown")
  else
    adr_exists="no"
    adr_status="-"
  fi

  # Check WD mapping
  wd_id="${ADR_TO_WD[$adr_slug]:-none}"
  wd_status="${WD_STATUS[$wd_id]:-none}"

  printf "%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$spec_id" "$adr_slug" "$adr_exists" "$adr_status" "$wd_id" "$wd_status"
done
