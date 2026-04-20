#!/usr/bin/env bash
# Extract per-lens card views from reconciled analysis cards.
#
# Usage:
#   Phase 1 (detect active lenses):
#     bash extract-views.sh detect <feature-dir>
#
#   Phase 2 (produce projections after pruning):
#     bash extract-views.sh project <feature-dir>
#
# Bash reference implementation — uses grep/sed/awk for YAML subset parsing.

set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "Usage: bash extract-views.sh <detect|project> <feature-dir>" >&2
    exit 1
fi

PHASE="$1"
FEATURE_DIR="$2"
CARDS_PATH="$FEATURE_DIR/analysis-cards.yaml"

if [[ ! -f "$CARDS_PATH" ]]; then
    echo "Error: $CARDS_PATH not found" >&2
    exit 1
fi

# ── Parse cards into flat key-value store ───────────────────────────
# Identical parsing approach to reconcile-cards.sh

declare -A CARDS
CARD_COUNT=0
declare -A NAME_TO_IDX

parse_inline_list() {
    local val="$1"
    val="${val#\[}"
    val="${val%\]}"
    val="$(echo "$val" | sed 's/^ *//;s/ *$//;s/ *, */,/g;s/"//g;s/'\''//g')"
    echo "$val"
}

parse_cards() {
    local card_idx=-1
    local section=""
    local in_list=0
    local list_key=""
    local list_items=""
    local list_item_type=""
    local dict_count=0

    while IFS= read -r line || [[ -n "$line" ]]; do
        local stripped="${line#"${line%%[![:space:]]*}"}"

        if [[ "$stripped" == "---" ]]; then
            if [[ $in_list -eq 1 && -n "$list_key" ]]; then
                CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
                if [[ "$list_item_type" == "dict" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
                fi
                in_list=0; list_items=""; dict_count=0
            fi
            card_idx=$((card_idx + 1))
            CARD_COUNT=$((card_idx + 1))
            section=""
            continue
        fi

        [[ -z "$stripped" || "$stripped" == \#* ]] && continue

        local indent=$(( ${#line} - ${#stripped} ))

        if [[ $indent -eq 0 && "$stripped" == *":"* ]]; then
            if [[ $in_list -eq 1 && -n "$list_key" ]]; then
                CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
                if [[ "$list_item_type" == "dict" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
                fi
                in_list=0; list_items=""; dict_count=0
            fi

            if [[ $card_idx -lt 0 ]]; then
                card_idx=0; CARD_COUNT=1
            fi

            local key="${stripped%%:*}"
            local val="${stripped#*:}"
            val="${val#"${val%%[![:space:]]*}"}"

            case "$key" in
                construct|kind|location)
                    val="${val#\"}" ; val="${val%\"}" ; val="${val#\'}" ; val="${val%\'}"
                    CARDS["CARD_${card_idx}_${key}"]="$val"
                    [[ "$key" == "construct" ]] && NAME_TO_IDX["$val"]=$card_idx
                    section=""
                    ;;
                execution|state|contracts|reconciliation)
                    section="$key"
                    ;;
            esac
            continue
        fi

        if [[ $indent -eq 2 && "$stripped" == *":"* && "$stripped" != "- "* ]]; then
            if [[ $in_list -eq 1 && -n "$list_key" ]]; then
                CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
                if [[ "$list_item_type" == "dict" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
                fi
                in_list=0; list_items=""; dict_count=0
            fi

            local key="${stripped%%:*}"
            local val="${stripped#*:}"
            val="${val#"${val%%[![:space:]]*}"}"

            if [[ "$val" == "["*"]" ]]; then
                val="$(parse_inline_list "$val")"
                CARDS["CARD_${card_idx}_${section}_${key}"]="$val"
            elif [[ -z "$val" || "$val" == "[]" ]]; then
                CARDS["CARD_${card_idx}_${section}_${key}"]=""
                if [[ "$val" != "[]" ]]; then
                    in_list=1; list_key="$key"; list_items=""; list_item_type="scalar"; dict_count=0
                fi
            else
                val="${val#\"}" ; val="${val%\"}" ; val="${val#\'}" ; val="${val%\'}"
                CARDS["CARD_${card_idx}_${section}_${key}"]="$val"
            fi
            continue
        fi

        if [[ $in_list -eq 1 && "$stripped" == "- "* ]]; then
            local rest="${stripped#- }"
            rest="${rest#"${rest%%[![:space:]]*}"}"

            if [[ "$rest" == *":"* && "$rest" != \"* && "$rest" != \'* ]]; then
                list_item_type="dict"
                local dk="${rest%%:*}"
                local dv="${rest#*:}"
                dv="${dv#"${dv%%[![:space:]]*}"}"
                dv="${dv#\"}" ; dv="${dv%\"}" ; dv="${dv#\'}" ; dv="${dv%\'}"
                CARDS["CARD_${card_idx}_${section}_${list_key}_d${dict_count}_${dk}"]="$dv"
                dict_count=$((dict_count + 1))
            else
                rest="${rest#\"}" ; rest="${rest%\"}" ; rest="${rest#\'}" ; rest="${rest%\'}"
                if [[ -n "$list_items" ]]; then
                    list_items="${list_items},${rest}"
                else
                    list_items="$rest"
                fi
            fi
            continue
        fi

        if [[ $in_list -eq 1 && "$list_item_type" == "dict" && "$stripped" == *":"* ]]; then
            local dk="${stripped%%:*}"
            local dv="${stripped#*:}"
            dv="${dv#"${dv%%[![:space:]]*}"}"
            dv="${dv#\"}" ; dv="${dv%\"}" ; dv="${dv#\'}" ; dv="${dv%\'}"
            local didx=$((dict_count - 1))
            CARDS["CARD_${card_idx}_${section}_${list_key}_d${didx}_${dk}"]="$dv"
            continue
        fi

    done < "$CARDS_PATH"

    if [[ $in_list -eq 1 && -n "$list_key" ]]; then
        CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
        if [[ "$list_item_type" == "dict" ]]; then
            CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
        fi
    fi
}

# ── Utility functions ──────────────────────────────────────────────

csv_has() {
    local csv="$1" item="$2"
    local IFS=','
    for v in $csv; do
        [[ "$v" == "$item" ]] && return 0
    done
    return 1
}

get_location_file() {
    local idx="$1"
    local loc="${CARDS["CARD_${idx}_location"]:-}"
    echo "${loc%%:*}"
}

constructs_cross_module() {
    local idx_a="$1" idx_b="$2"
    [[ "$(get_location_file "$idx_a")" != "$(get_location_file "$idx_b")" ]]
}

get_field() {
    local idx="$1" section="$2" field="$3"
    echo "${CARDS["CARD_${idx}_${section}_${field}"]:-}"
}

# ── Lens predicate functions ───────────────────────────────────────
# Each returns 0 (true) or 1 (false)

check_shared_state() {
    local idx="$1"
    for field in owns reads_external writes_external co_mutators co_readers; do
        [[ -n "$(get_field "$idx" state "$field")" ]] && return 0
    done
    return 1
}

check_resource_lifecycle() {
    local idx="$1"
    local owns="$(get_field "$idx" state owns)"
    [[ -z "$owns" ]] && return 1

    local targets="$(get_field "$idx" execution invokes)"
    [[ -n "$(get_field "$idx" execution invoked_by)" ]] && {
        if [[ -n "$targets" ]]; then
            targets="${targets},$(get_field "$idx" execution invoked_by)"
        else
            targets="$(get_field "$idx" execution invoked_by)"
        fi
    }

    local IFS=','
    for target in $targets; do
        if [[ -n "${NAME_TO_IDX[$target]+x}" ]]; then
            local tidx="${NAME_TO_IDX[$target]}"
            [[ -n "$(get_field "$tidx" state owns)" ]] && return 0
        fi
    done
    return 1
}

check_contract_boundaries() {
    local idx="$1"

    local invokes="$(get_field "$idx" execution invokes)"
    if [[ -n "$invokes" ]]; then
        local IFS=','
        for target in $invokes; do
            if [[ -n "${NAME_TO_IDX[$target]+x}" ]]; then
                constructs_cross_module "$idx" "${NAME_TO_IDX[$target]}" && return 0
            fi
        done
        unset IFS
    fi

    local invoked_by="$(get_field "$idx" execution invoked_by)"
    if [[ -n "$invoked_by" ]]; then
        local IFS=','
        for target in $invoked_by; do
            if [[ -n "${NAME_TO_IDX[$target]+x}" ]]; then
                constructs_cross_module "$idx" "${NAME_TO_IDX[$target]}" && return 0
            fi
        done
        unset IFS
    fi
    return 1
}

check_data_transformation() {
    local idx="$1"
    local reads="$(get_field "$idx" state reads_external)"
    local writes="$(get_field "$idx" state writes_external)"
    [[ -z "$reads" || -z "$writes" ]] && return 1

    # Extract target names from dotted refs
    local read_targets="" write_targets=""
    local IFS=','
    for r in $reads; do
        [[ "$r" == *.* ]] && {
            local rt="${r%%.*}"
            if [[ -z "$read_targets" ]]; then read_targets="$rt"
            elif ! csv_has "$read_targets" "$rt"; then read_targets="${read_targets},${rt}"
            fi
        }
    done
    for w in $writes; do
        [[ "$w" == *.* ]] && {
            local wt="${w%%.*}"
            if [[ -z "$write_targets" ]]; then write_targets="$wt"
            elif ! csv_has "$write_targets" "$wt"; then write_targets="${write_targets},${wt}"
            fi
        }
    done
    unset IFS

    # Compare sets (not equal = qualifies)
    [[ "$read_targets" != "$write_targets" ]] && return 0
    return 1
}

check_concurrency() {
    local idx="$1"
    [[ -n "$(get_field "$idx" state co_mutators)" ]] && return 0
    return 1
}

check_dispatch_routing() {
    local idx="$1"
    local invokes="$(get_field "$idx" execution invokes)"
    [[ -z "$invokes" ]] && return 1

    # Count targets in scope
    local targets_in_scope=""
    local count=0
    local IFS=','
    for t in $invokes; do
        if [[ -n "${NAME_TO_IDX[$t]+x}" ]]; then
            if [[ -z "$targets_in_scope" ]]; then targets_in_scope="$t"
            else targets_in_scope="${targets_in_scope},$t"; fi
            count=$((count + 1))
        fi
    done
    unset IFS

    [[ $count -lt 3 ]] && return 1

    # Check no mutual execution edges between targets
    local IFS=','
    local -a tarr=($targets_in_scope)
    unset IFS
    local i j
    for ((i=0; i<${#tarr[@]}; i++)); do
        local t1="${tarr[$i]}"
        local t1idx="${NAME_TO_IDX[$t1]}"
        local t1_inv="$(get_field "$t1idx" execution invokes)"
        local t1_invby="$(get_field "$t1idx" execution invoked_by)"
        for ((j=i+1; j<${#tarr[@]}; j++)); do
            local t2="${tarr[$j]}"
            if csv_has "${t1_inv:-_}" "$t2" 2>/dev/null || csv_has "${t1_invby:-_}" "$t2" 2>/dev/null; then
                return 1
            fi
        done
    done
    return 0
}

# ── Lens definitions ───────────────────────────────────────────────

# Lens metadata stored as parallel arrays
LENS_NAMES=(shared_state resource_lifecycle contract_boundaries data_transformation concurrency dispatch_routing)

declare -A LENS_DESC
LENS_DESC[shared_state]="Shared mutable state consistency"
LENS_DESC[resource_lifecycle]="Resource acquisition/release lifecycle"
LENS_DESC[contract_boundaries]="Cross-module contract violations"
LENS_DESC[data_transformation]="Data format/type transformation fidelity"
LENS_DESC[concurrency]="Concurrent access to shared state"
LENS_DESC[dispatch_routing]="Dispatch/routing pattern correctness"

declare -A LENS_CHALLENGE
LENS_CHALLENGE[shared_state]="This codebase has shared mutable state between constructs. Prove it doesn't."
LENS_CHALLENGE[resource_lifecycle]="This codebase has resource lifecycle patterns (acquire/release, open/close). Prove it doesn't."
LENS_CHALLENGE[contract_boundaries]="This codebase has cross-module invocations where caller and callee assumptions could mismatch. Prove it doesn't."
LENS_CHALLENGE[data_transformation]="This codebase has data transformation chains where data changes form between producer and consumer. Prove it doesn't."
LENS_CHALLENGE[concurrency]="This codebase has concurrent access patterns — multiple constructs mutate the same state. Prove it doesn't."
LENS_CHALLENGE[dispatch_routing]="This codebase has dispatch patterns where one construct routes to multiple independent handlers. Prove it doesn't."

# Fields per lens per section (comma-separated)
declare -A LENS_FIELDS_EXEC
LENS_FIELDS_EXEC[shared_state]="invokes,invoked_by"
LENS_FIELDS_EXEC[resource_lifecycle]="invokes,invoked_by,entry_points"
LENS_FIELDS_EXEC[contract_boundaries]="invokes,invoked_by,entry_points"
LENS_FIELDS_EXEC[data_transformation]="invokes,invoked_by"
LENS_FIELDS_EXEC[concurrency]="invoked_by"
LENS_FIELDS_EXEC[dispatch_routing]="invokes,invoked_by,entry_points"

declare -A LENS_FIELDS_STATE
LENS_FIELDS_STATE[shared_state]="owns,reads_external,writes_external,read_by,written_by,co_mutators,co_readers"
LENS_FIELDS_STATE[resource_lifecycle]="owns"
LENS_FIELDS_STATE[contract_boundaries]=""
LENS_FIELDS_STATE[data_transformation]="reads_external,writes_external"
LENS_FIELDS_STATE[concurrency]="owns,co_mutators,co_readers,writes_external,written_by"
LENS_FIELDS_STATE[dispatch_routing]=""

declare -A LENS_FIELDS_RECON
LENS_FIELDS_RECON[shared_state]=""
LENS_FIELDS_RECON[resource_lifecycle]=""
LENS_FIELDS_RECON[contract_boundaries]="inconsistencies"
LENS_FIELDS_RECON[data_transformation]=""
LENS_FIELDS_RECON[concurrency]=""
LENS_FIELDS_RECON[dispatch_routing]=""

# ── Phase 1: Detect active lenses ──────────────────────────────────

detect_lenses() {
    local active_names=""
    local eliminated_names=""
    local active_count=0
    local eliminated_count=0

    # For each lens, evaluate predicate against all cards
    declare -A LENS_QUALIFYING
    for lens in "${LENS_NAMES[@]}"; do
        LENS_QUALIFYING[$lens]=""
        local qual_count=0
        for ((i=0; i<CARD_COUNT; i++)); do
            if "check_${lens}" "$i" 2>/dev/null; then
                local name="${CARDS["CARD_${i}_construct"]}"
                if [[ -z "${LENS_QUALIFYING[$lens]}" ]]; then
                    LENS_QUALIFYING[$lens]="$name"
                else
                    LENS_QUALIFYING[$lens]="${LENS_QUALIFYING[$lens]},$name"
                fi
                qual_count=$((qual_count + 1))
            fi
        done

        if [[ $qual_count -gt 0 ]]; then
            if [[ -z "$active_names" ]]; then active_names="$lens"
            else active_names="${active_names},$lens"; fi
            active_count=$((active_count + 1))
        else
            if [[ -z "$eliminated_names" ]]; then eliminated_names="$lens"
            else eliminated_names="${eliminated_names},$lens"; fi
            eliminated_count=$((eliminated_count + 1))
        fi
    done

    # Write active-lenses.md
    local out_path="$FEATURE_DIR/active-lenses.md"
    local tmp_path="${out_path}.tmp"

    {
        echo "# Active Domain Lenses"
        echo ""
        echo "Candidate-active lenses detected from construct cards."
        echo "Each lens must survive pruning: the LLM must prove the domain"
        echo "does NOT apply to eliminate it. Lenses not listed here had zero"
        echo "qualifying constructs and are already pruned."
        echo ""
        echo "---"
        echo ""

        # Output active lenses in sorted order
        local sorted_lenses
        sorted_lenses="$(printf '%s\n' "${LENS_NAMES[@]}" | sort)"
        while IFS= read -r lens; do
            local qual="${LENS_QUALIFYING[$lens]:-}"
            [[ -z "$qual" ]] && continue

            # Count qualifying
            local qcount=1
            local tmp="$qual"
            while [[ "$tmp" == *","* ]]; do
                qcount=$((qcount + 1))
                tmp="${tmp#*,}"
            done

            echo "## $lens"
            echo ""
            echo "**Description:** ${LENS_DESC[$lens]}"
            echo "**Qualifying constructs:** $qcount"
            echo "**Challenge:** \"${LENS_CHALLENGE[$lens]}\""
            echo "**Status:** CANDIDATE"
            echo ""

            # Show first 10 constructs
            local shown=0
            local display=""
            local IFS=','
            for name in $qual; do
                if [[ $shown -lt 10 ]]; then
                    if [[ -z "$display" ]]; then display="$name"
                    else display="${display}, $name"; fi
                fi
                shown=$((shown + 1))
            done
            unset IFS
            echo "Constructs: $display"
            if [[ $shown -gt 10 ]]; then
                echo "  ... and $((shown - 10)) more"
            fi
            echo ""
            echo "---"
            echo ""
        done <<< "$sorted_lenses"

        # Eliminated lenses (sorted)
        if [[ -n "$eliminated_names" ]]; then
            echo "## Eliminated (zero qualifying constructs)"
            echo ""
            local sorted_elim
            sorted_elim="$(echo "$eliminated_names" | tr ',' '\n' | sort)"
            while IFS= read -r lens; do
                [[ -z "$lens" ]] && continue
                echo "- **$lens**: ${LENS_DESC[$lens]} — no constructs matched predicate"
            done <<< "$sorted_elim"
            echo ""
        fi
    } > "$tmp_path" 2>/dev/null || {
        echo "Error writing $out_path" >&2
        exit 0
    }

    mv "$tmp_path" "$out_path" 2>/dev/null || {
        echo "Error writing $out_path" >&2
        exit 0
    }

    # Sort active names for display
    local sorted_active
    sorted_active="$(echo "$active_names" | tr ',' '\n' | sort | tr '\n' ',' | sed 's/,$//;s/,/, /g')"

    echo "Detected $active_count candidate lenses, $eliminated_count eliminated — active: $sorted_active"
}

# ── Phase 2: Produce projections ───────────────────────────────────

parse_active_lenses() {
    local lenses_path="$FEATURE_DIR/active-lenses.md"
    if [[ ! -f "$lenses_path" ]]; then
        echo "Error: $lenses_path not found" >&2
        exit 1
    fi

    local confirmed=""
    local current_lens=""

    while IFS= read -r line; do
        local stripped="${line#"${line%%[![:space:]]*}"}"

        # Match lens headers
        if [[ "$stripped" =~ ^##\ ([a-z_]+)$ ]]; then
            current_lens="${BASH_REMATCH[1]}"
            continue
        fi

        if [[ -n "$current_lens" && "$line" == *"**Status:**"* ]]; then
            if [[ "$line" == *"CONFIRMED"* ]]; then
                if [[ -z "$confirmed" ]]; then confirmed="$current_lens"
                else confirmed="${confirmed},$current_lens"; fi
            fi
            current_lens=""
        fi
    done < "$lenses_path"

    echo "$confirmed"
}

format_list() {
    local csv="$1"
    if [[ -z "$csv" ]]; then echo "[]"
    else echo "[$(echo "$csv" | sed 's/,/, /g')]"; fi
}

emit_projected_card() {
    local idx="$1" lens="$2"

    echo "construct: ${CARDS["CARD_${idx}_construct"]:-}"
    echo "kind: ${CARDS["CARD_${idx}_kind"]:-}"
    echo "location: ${CARDS["CARD_${idx}_location"]:-}"

    # Execution fields for this lens
    local exec_fields="${LENS_FIELDS_EXEC[$lens]:-}"
    if [[ -n "$exec_fields" ]]; then
        local has_exec=0
        local IFS=','
        for field in $exec_fields; do
            [[ -n "${CARDS["CARD_${idx}_execution_${field}"]:-}" || \
               -n "${CARDS["CARD_${idx}_execution_${field}"]+"set"}" ]] && has_exec=1
        done
        unset IFS

        if [[ $has_exec -eq 1 ]]; then
            echo ""
            echo "execution:"
            local IFS=','
            for field in $exec_fields; do
                echo "  $field: $(format_list "${CARDS["CARD_${idx}_execution_${field}"]:-}")"
            done
            unset IFS
        fi
    fi

    # State fields for this lens
    local state_fields="${LENS_FIELDS_STATE[$lens]:-}"
    if [[ -n "$state_fields" ]]; then
        local has_state=0
        local IFS=','
        for field in $state_fields; do
            [[ -n "${CARDS["CARD_${idx}_state_${field}"]+"set"}" ]] && has_state=1
        done
        unset IFS

        if [[ $has_state -eq 1 ]]; then
            echo ""
            echo "state:"
            local IFS=','
            for field in $state_fields; do
                echo "  $field: $(format_list "${CARDS["CARD_${idx}_state_${field}"]:-}")"
            done
            unset IFS
        fi
    fi

    # Reconciliation fields for this lens
    local recon_fields="${LENS_FIELDS_RECON[$lens]:-}"
    if [[ -n "$recon_fields" ]]; then
        echo ""
        echo "reconciliation:"
        local IFS=','
        for field in $recon_fields; do
                if [[ "$field" == "inconsistencies" ]]; then
                    local dc="${CARDS["CARD_${idx}_reconciliation_inconsistencies_dict_count"]:-0}"
                    if [[ $dc -eq 0 ]]; then
                        echo "  inconsistencies: []"
                    else
                        echo "  inconsistencies:"
                        for ((ic=0; ic<dc; ic++)); do
                            echo "    - type: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_type"]:-}"
                            echo "      source: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_source"]:-}"
                            echo "      target: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_target"]:-}"
                            echo "      detail: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_detail"]:-}"
                        done
                    fi
                else
                    echo "  $field: $(format_list "${CARDS["CARD_${idx}_reconciliation_${field}"]:-}")"
                fi
            done
            unset IFS
    fi
}

produce_projections() {
    local confirmed="$1"
    local total=0
    local details=""

    local IFS=','
    for lens in $confirmed; do
        unset IFS

        # Check lens is known
        local known=0
        for known_lens in "${LENS_NAMES[@]}"; do
            [[ "$known_lens" == "$lens" ]] && { known=1; break; }
        done
        if [[ $known -eq 0 ]]; then
            echo "Warning: unknown lens '$lens', skipping" >&2
            continue
        fi

        # Find qualifying cards
        local lens_count=0
        local out_path="$FEATURE_DIR/lens-${lens}-cards.yaml"
        local tmp_path="${out_path}.tmp"
        local first=1

        {
            for ((i=0; i<CARD_COUNT; i++)); do
                if "check_${lens}" "$i" 2>/dev/null; then
                    if [[ $first -eq 0 ]]; then
                        echo "---"
                    fi
                    emit_projected_card "$i" "$lens"
                    first=0
                    lens_count=$((lens_count + 1))
                fi
            done
        } > "$tmp_path" 2>/dev/null || {
            echo "Error writing $out_path" >&2
            continue
        }

        mv "$tmp_path" "$out_path" 2>/dev/null || {
            echo "Error writing $out_path" >&2
            continue
        }

        total=$((total + lens_count))
        if [[ -z "$details" ]]; then details="${lens}=${lens_count}"
        else details="${details}, ${lens}=${lens_count}"; fi

        IFS=','
    done
    unset IFS

    # Count confirmed lenses
    local conf_count=0
    local IFS=','
    for _ in $confirmed; do conf_count=$((conf_count + 1)); done
    unset IFS

    echo "Projected $conf_count lenses, $total total card projections — $details"
}

# ── Main ───────────────────────────────────────────────────────────

parse_cards || {
    echo "Error parsing $CARDS_PATH" >&2
    exit 0
}

if [[ $CARD_COUNT -eq 0 ]]; then
    echo "Error: no cards parsed" >&2
    exit 1
fi

case "$PHASE" in
    detect)
        detect_lenses
        ;;
    project)
        confirmed="$(parse_active_lenses)"
        if [[ -z "$confirmed" ]]; then
            echo "No confirmed lenses found in active-lenses.md" >&2
            exit 1
        fi
        produce_projections "$confirmed"
        ;;
    *)
        echo "Unknown phase: $PHASE. Use 'detect' or 'project'." >&2
        exit 1
        ;;
esac
