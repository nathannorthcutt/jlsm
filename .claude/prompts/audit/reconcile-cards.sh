#!/usr/bin/env bash
# Reconcile construct cards — invert outgoing edges to add incoming edges,
# derive co_mutators/co_readers, flag inconsistencies.
#
# Usage: bash reconcile-cards.sh <feature-dir>
#
# Reads:  <feature-dir>/construct-cards.yaml
# Writes: <feature-dir>/analysis-cards.yaml (reconciled)
#
# This is a mechanical data transformation. No LLM judgment involved.
# Bash reference implementation — uses grep/sed/awk for YAML subset parsing.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: bash reconcile-cards.sh <feature-dir>" >&2
    exit 1
fi

FEATURE_DIR="$1"
INPUT_PATH="$FEATURE_DIR/construct-cards.yaml"
OUTPUT_PATH="$FEATURE_DIR/analysis-cards.yaml"
TMP_PATH="$OUTPUT_PATH.tmp"

if [[ ! -f "$INPUT_PATH" ]]; then
    echo "Error: $INPUT_PATH not found" >&2
    exit 1
fi

# ── Parse cards into a flat key-value store ─────────────────────────
# Each card gets an index (0, 1, 2...). Fields stored as:
#   CARD_<idx>_construct=Name
#   CARD_<idx>_kind=class
#   CARD_<idx>_execution_invokes=A,B,C
#   CARD_<idx>_state_owns=x,y
# etc.

declare -A CARDS
CARD_COUNT=0
declare -A NAME_TO_IDX

parse_inline_list() {
    # Parse [a, b, c] or empty [] into comma-separated string
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
    local list_item_type=""  # "scalar" or "dict"
    local dict_count=0
    local current_indent=0

    while IFS= read -r line || [[ -n "$line" ]]; do
        local stripped="${line#"${line%%[![:space:]]*}"}"

        # Document separator
        if [[ "$stripped" == "---" ]]; then
            # Flush any pending list
            if [[ $in_list -eq 1 && -n "$list_key" ]]; then
                CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
                if [[ "$list_item_type" == "dict" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
                fi
                in_list=0
                list_items=""
                dict_count=0
            fi
            card_idx=$((card_idx + 1))
            CARD_COUNT=$((card_idx + 1))
            section=""
            continue
        fi

        # Skip empty/comment lines
        [[ -z "$stripped" || "$stripped" == \#* ]] && continue

        # Calculate indent
        local indent=$(( ${#line} - ${#stripped} ))

        # Top-level key (indent 0)
        if [[ $indent -eq 0 && "$stripped" == *":"* ]]; then
            # Flush pending list
            if [[ $in_list -eq 1 && -n "$list_key" ]]; then
                CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
                if [[ "$list_item_type" == "dict" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
                fi
                in_list=0
                list_items=""
                dict_count=0
            fi

            if [[ $card_idx -lt 0 ]]; then
                card_idx=0
                CARD_COUNT=1
            fi

            local key="${stripped%%:*}"
            local val="${stripped#*:}"
            val="${val#"${val%%[![:space:]]*}"}"

            case "$key" in
                construct|kind|location)
                    val="${val#\"}" ; val="${val%\"}" ; val="${val#\'}" ; val="${val%\'}"
                    CARDS["CARD_${card_idx}_${key}"]="$val"
                    if [[ "$key" == "construct" ]]; then
                        NAME_TO_IDX["$val"]=$card_idx
                    fi
                    section=""
                    ;;
                execution|state|contracts|reconciliation)
                    section="$key"
                    ;;
            esac
            continue
        fi

        # Section-level key (indent 2)
        if [[ $indent -eq 2 && "$stripped" == *":"* && "$stripped" != "- "* ]]; then
            # Flush pending list
            if [[ $in_list -eq 1 && -n "$list_key" ]]; then
                CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
                if [[ "$list_item_type" == "dict" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
                fi
                in_list=0
                list_items=""
                dict_count=0
            fi

            local key="${stripped%%:*}"
            local val="${stripped#*:}"
            val="${val#"${val%%[![:space:]]*}"}"

            if [[ "$val" == "["*"]" ]]; then
                # Inline list
                val="$(parse_inline_list "$val")"
                CARDS["CARD_${card_idx}_${section}_${key}"]="$val"
            elif [[ -z "$val" || "$val" == "[]" ]]; then
                if [[ "$val" == "[]" ]]; then
                    CARDS["CARD_${card_idx}_${section}_${key}"]=""
                else
                    # Will be populated by subsequent indented lines
                    CARDS["CARD_${card_idx}_${section}_${key}"]=""
                fi
                # Prepare to collect list items if they follow
                in_list=1
                list_key="$key"
                list_items=""
                list_item_type="scalar"
                dict_count=0
            else
                val="${val#\"}" ; val="${val%\"}" ; val="${val#\'}" ; val="${val%\'}"
                CARDS["CARD_${card_idx}_${section}_${key}"]="$val"
            fi
            continue
        fi

        # List items (indent 4, starting with "- ")
        if [[ $in_list -eq 1 && "$stripped" == "- "* ]]; then
            local rest="${stripped#- }"
            rest="${rest#"${rest%%[![:space:]]*}"}"

            if [[ "$rest" == *":"* && "$rest" != \"* && "$rest" != \'* ]]; then
                # Dict item
                list_item_type="dict"
                local dk="${rest%%:*}"
                local dv="${rest#*:}"
                dv="${dv#"${dv%%[![:space:]]*}"}"
                dv="${dv#\"}" ; dv="${dv%\"}" ; dv="${dv#\'}" ; dv="${dv%\'}"
                CARDS["CARD_${card_idx}_${section}_${list_key}_d${dict_count}_${dk}"]="$dv"
                dict_count=$((dict_count + 1))
            else
                # Scalar item
                rest="${rest#\"}" ; rest="${rest%\"}" ; rest="${rest#\'}" ; rest="${rest%\'}"
                if [[ -n "$list_items" ]]; then
                    list_items="${list_items},${rest}"
                else
                    list_items="$rest"
                fi
            fi
            continue
        fi

        # Dict item continuation (indent 6)
        if [[ $in_list -eq 1 && "$list_item_type" == "dict" && "$stripped" == *":"* ]]; then
            local dk="${stripped%%:*}"
            local dv="${stripped#*:}"
            dv="${dv#"${dv%%[![:space:]]*}"}"
            dv="${dv#\"}" ; dv="${dv%\"}" ; dv="${dv#\'}" ; dv="${dv%\'}"
            # dict_count points to next, so current is dict_count-1
            local didx=$((dict_count - 1))
            CARDS["CARD_${card_idx}_${section}_${list_key}_d${didx}_${dk}"]="$dv"
            continue
        fi

    done < "$INPUT_PATH"

    # Flush last pending list
    if [[ $in_list -eq 1 && -n "$list_key" ]]; then
        CARDS["CARD_${card_idx}_${section}_${list_key}"]="$list_items"
        if [[ "$list_item_type" == "dict" ]]; then
            CARDS["CARD_${card_idx}_${section}_${list_key}_dict_count"]="$dict_count"
        fi
    fi
}

# ── Utility: get comma-separated list as array ──────────────────────

get_csv() {
    local key="$1"
    local val="${CARDS[$key]:-}"
    echo "$val"
}

csv_contains() {
    local csv="$1"
    local item="$2"
    local IFS=','
    for v in $csv; do
        [[ "$v" == "$item" ]] && return 0
    done
    return 1
}

csv_append() {
    local csv="$1"
    local item="$2"
    if [[ -z "$csv" ]]; then
        echo "$item"
    else
        echo "${csv},${item}"
    fi
}

# ── Reconciliation passes ──────────────────────────────────────────

reconcile() {
    local i

    # Initialize reconciliation fields
    for ((i=0; i<CARD_COUNT; i++)); do
        CARDS["CARD_${i}_execution_invoked_by"]="${CARDS["CARD_${i}_execution_invoked_by"]:-}"
        CARDS["CARD_${i}_state_read_by"]="${CARDS["CARD_${i}_state_read_by"]:-}"
        CARDS["CARD_${i}_state_written_by"]="${CARDS["CARD_${i}_state_written_by"]:-}"
        CARDS["CARD_${i}_state_co_mutators"]="${CARDS["CARD_${i}_state_co_mutators"]:-}"
        CARDS["CARD_${i}_state_co_readers"]="${CARDS["CARD_${i}_state_co_readers"]:-}"
        CARDS["CARD_${i}_reconciliation_inconsistencies"]="${CARDS["CARD_${i}_reconciliation_inconsistencies"]:-}"
        CARDS["CARD_${i}_reconciliation_inconsistencies_dict_count"]="${CARDS["CARD_${i}_reconciliation_inconsistencies_dict_count"]:-0}"
    done

    # Pass 1: Invert execution edges
    for ((i=0; i<CARD_COUNT; i++)); do
        local source="${CARDS["CARD_${i}_construct"]}"
        local invokes="${CARDS["CARD_${i}_execution_invokes"]:-}"
        [[ -z "$invokes" ]] && continue

        local IFS=','
        for target_name in $invokes; do
            if [[ -n "${NAME_TO_IDX[$target_name]+x}" ]]; then
                local tidx="${NAME_TO_IDX[$target_name]}"
                local invoked_by="${CARDS["CARD_${tidx}_execution_invoked_by"]:-}"
                if ! csv_contains "$invoked_by" "$source"; then
                    CARDS["CARD_${tidx}_execution_invoked_by"]="$(csv_append "$invoked_by" "$source")"
                fi
            fi
        done
        unset IFS
    done

    # Pass 2: Invert state edges
    for ((i=0; i<CARD_COUNT; i++)); do
        local source="${CARDS["CARD_${i}_construct"]}"

        # reads_external -> read_by
        local reads_ext="${CARDS["CARD_${i}_state_reads_external"]:-}"
        if [[ -n "$reads_ext" ]]; then
            local IFS=','
            for ref in $reads_ext; do
                local target_name="${ref%%.*}"
                if [[ -n "${NAME_TO_IDX[$target_name]+x}" ]]; then
                    local tidx="${NAME_TO_IDX[$target_name]}"
                    local read_by="${CARDS["CARD_${tidx}_state_read_by"]:-}"
                    if ! csv_contains "$read_by" "$source"; then
                        CARDS["CARD_${tidx}_state_read_by"]="$(csv_append "$read_by" "$source")"
                    fi
                fi
            done
            unset IFS
        fi

        # writes_external -> written_by
        local writes_ext="${CARDS["CARD_${i}_state_writes_external"]:-}"
        if [[ -n "$writes_ext" ]]; then
            local IFS=','
            for ref in $writes_ext; do
                local target_name="${ref%%.*}"
                if [[ -n "${NAME_TO_IDX[$target_name]+x}" ]]; then
                    local tidx="${NAME_TO_IDX[$target_name]}"
                    local written_by="${CARDS["CARD_${tidx}_state_written_by"]:-}"
                    if ! csv_contains "$written_by" "$source"; then
                        CARDS["CARD_${tidx}_state_written_by"]="$(csv_append "$written_by" "$source")"
                    fi
                fi
            done
            unset IFS
        fi
    done

    # Pass 3: Derive co_mutators
    for ((i=0; i<CARD_COUNT; i++)); do
        local owner="${CARDS["CARD_${i}_construct"]}"
        local owns="${CARDS["CARD_${i}_state_owns"]:-}"
        [[ -z "$owns" ]] && continue

        # Find all constructs that write to this owner's state
        local writers=""
        local j
        for ((j=0; j<CARD_COUNT; j++)); do
            [[ $j -eq $i ]] && continue
            local other_name="${CARDS["CARD_${j}_construct"]}"
            local w_ext="${CARDS["CARD_${j}_state_writes_external"]:-}"
            [[ -z "$w_ext" ]] && continue

            local IFS=','
            for ref in $w_ext; do
                local tgt="${ref%%.*}"
                if [[ "$tgt" == "$owner" ]]; then
                    if ! csv_contains "$writers" "$other_name"; then
                        writers="$(csv_append "$writers" "$other_name")"
                    fi
                    break
                fi
            done
            unset IFS
        done

        [[ -z "$writers" ]] && continue

        # Set co_mutators on each writer (other writers) and on owner (all writers)
        local IFS=','
        for writer in $writers; do
            # Add other writers as co_mutators of this writer
            for other_writer in $writers; do
                [[ "$other_writer" == "$writer" ]] && continue
                local widx="${NAME_TO_IDX[$writer]}"
                local co_mut="${CARDS["CARD_${widx}_state_co_mutators"]:-}"
                if ! csv_contains "$co_mut" "$other_writer"; then
                    CARDS["CARD_${widx}_state_co_mutators"]="$(csv_append "$co_mut" "$other_writer")"
                fi
            done

            # Add writer as co_mutator of owner
            local co_mut="${CARDS["CARD_${i}_state_co_mutators"]:-}"
            if ! csv_contains "$co_mut" "$writer"; then
                CARDS["CARD_${i}_state_co_mutators"]="$(csv_append "$co_mut" "$writer")"
            fi
        done
        unset IFS
    done

    # Pass 4: Derive co_readers
    for ((i=0; i<CARD_COUNT; i++)); do
        local owner="${CARDS["CARD_${i}_construct"]}"
        local owns="${CARDS["CARD_${i}_state_owns"]:-}"
        [[ -z "$owns" ]] && continue

        local readers=""
        local j
        for ((j=0; j<CARD_COUNT; j++)); do
            [[ $j -eq $i ]] && continue
            local other_name="${CARDS["CARD_${j}_construct"]}"
            local r_ext="${CARDS["CARD_${j}_state_reads_external"]:-}"
            [[ -z "$r_ext" ]] && continue

            local IFS=','
            for ref in $r_ext; do
                local tgt="${ref%%.*}"
                if [[ "$tgt" == "$owner" ]]; then
                    if ! csv_contains "$readers" "$other_name"; then
                        readers="$(csv_append "$readers" "$other_name")"
                    fi
                    break
                fi
            done
            unset IFS
        done

        [[ -z "$readers" ]] && continue

        local IFS=','
        for reader in $readers; do
            for other_reader in $readers; do
                [[ "$other_reader" == "$reader" ]] && continue
                local ridx="${NAME_TO_IDX[$reader]}"
                local co_rd="${CARDS["CARD_${ridx}_state_co_readers"]:-}"
                if ! csv_contains "$co_rd" "$other_reader"; then
                    CARDS["CARD_${ridx}_state_co_readers"]="$(csv_append "$co_rd" "$other_reader")"
                fi
            done
        done
        unset IFS
    done

    # Pass 5: Flag inconsistencies
    for ((i=0; i<CARD_COUNT; i++)); do
        local source="${CARDS["CARD_${i}_construct"]}"
        local invokes="${CARDS["CARD_${i}_execution_invokes"]:-}"
        [[ -z "$invokes" ]] && continue

        local IFS=','
        for target_name in $invokes; do
            if [[ -n "${NAME_TO_IDX[$target_name]+x}" ]]; then
                local tidx="${NAME_TO_IDX[$target_name]}"
                local entry_points="${CARDS["CARD_${tidx}_execution_entry_points"]:-}"
                [[ -z "$entry_points" ]] && continue

                # Check if any entry point matches
                local found=0
                for ep in $entry_points; do
                    if [[ "$target_name" == *"$ep"* || "$target_name" == *".$ep" ]]; then
                        found=1
                        break
                    fi
                done

                if [[ $found -eq 0 ]]; then
                    local dc="${CARDS["CARD_${i}_reconciliation_inconsistencies_dict_count"]:-0}"
                    CARDS["CARD_${i}_reconciliation_inconsistencies_d${dc}_type"]="invokes_without_entry_point"
                    CARDS["CARD_${i}_reconciliation_inconsistencies_d${dc}_source"]="$source"
                    CARDS["CARD_${i}_reconciliation_inconsistencies_d${dc}_target"]="$target_name"
                    local ep_display
                    ep_display="$(echo "$entry_points" | sed 's/,/, /g')"
                    CARDS["CARD_${i}_reconciliation_inconsistencies_d${dc}_detail"]="${source} invokes ${target_name} but ${target_name} entry_points [${ep_display}] may not include the called method"
                    dc=$((dc + 1))
                    CARDS["CARD_${i}_reconciliation_inconsistencies_dict_count"]="$dc"
                fi
            fi
        done
        unset IFS
    done
}

# ── YAML emission ──────────────────────────────────────────────────

format_list() {
    # Convert comma-separated to [a, b, c] format (with spaces after commas)
    local csv="$1"
    if [[ -z "$csv" ]]; then
        echo "[]"
    else
        echo "[$(echo "$csv" | sed 's/,/, /g')]"
    fi
}

emit_card() {
    local idx="$1"

    echo "construct: ${CARDS["CARD_${idx}_construct"]:-}"
    echo "kind: ${CARDS["CARD_${idx}_kind"]:-}"
    echo "location: ${CARDS["CARD_${idx}_location"]:-}"
    echo ""

    # Execution
    echo "execution:"
    echo "  invokes: $(format_list "${CARDS["CARD_${idx}_execution_invokes"]:-}")"
    echo "  invoked_by: $(format_list "${CARDS["CARD_${idx}_execution_invoked_by"]:-}")"
    echo "  entry_points: $(format_list "${CARDS["CARD_${idx}_execution_entry_points"]:-}")"
    echo ""

    # State
    echo "state:"
    echo "  owns: $(format_list "${CARDS["CARD_${idx}_state_owns"]:-}")"
    echo "  reads_external: $(format_list "${CARDS["CARD_${idx}_state_reads_external"]:-}")"
    echo "  writes_external: $(format_list "${CARDS["CARD_${idx}_state_writes_external"]:-}")"
    echo "  read_by: $(format_list "${CARDS["CARD_${idx}_state_read_by"]:-}")"
    echo "  written_by: $(format_list "${CARDS["CARD_${idx}_state_written_by"]:-}")"
    echo "  co_mutators: $(format_list "${CARDS["CARD_${idx}_state_co_mutators"]:-}")"
    echo "  co_readers: $(format_list "${CARDS["CARD_${idx}_state_co_readers"]:-}")"
    echo ""

    # Contracts
    echo "contracts:"
    local g_count="${CARDS["CARD_${idx}_contracts_guarantees_dict_count"]:-0}"
    if [[ "$g_count" -eq 0 && -z "${CARDS["CARD_${idx}_contracts_guarantees"]:-}" ]]; then
        echo "  guarantees: []"
    elif [[ "$g_count" -gt 0 ]]; then
        echo "  guarantees:"
        local g
        for ((g=0; g<g_count; g++)); do
            echo "    - what: ${CARDS["CARD_${idx}_contracts_guarantees_d${g}_what"]:-}"
            echo "      evidence: ${CARDS["CARD_${idx}_contracts_guarantees_d${g}_evidence"]:-}"
        done
    else
        # Scalar list
        local items="${CARDS["CARD_${idx}_contracts_guarantees"]:-}"
        if [[ -z "$items" ]]; then
            echo "  guarantees: []"
        else
            echo "  guarantees:"
            local IFS=','
            for item in $items; do
                echo "    - $item"
            done
            unset IFS
        fi
    fi

    local a_count="${CARDS["CARD_${idx}_contracts_assumptions_dict_count"]:-0}"
    if [[ "$a_count" -eq 0 && -z "${CARDS["CARD_${idx}_contracts_assumptions"]:-}" ]]; then
        echo "  assumptions: []"
    elif [[ "$a_count" -gt 0 ]]; then
        echo "  assumptions:"
        local a
        for ((a=0; a<a_count; a++)); do
            echo "    - what: ${CARDS["CARD_${idx}_contracts_assumptions_d${a}_what"]:-}"
            echo "      evidence: ${CARDS["CARD_${idx}_contracts_assumptions_d${a}_evidence"]:-}"
            echo "      failure_mode: ${CARDS["CARD_${idx}_contracts_assumptions_d${a}_failure_mode"]:-}"
        done
    else
        local items="${CARDS["CARD_${idx}_contracts_assumptions"]:-}"
        if [[ -z "$items" ]]; then
            echo "  assumptions: []"
        else
            echo "  assumptions:"
            local IFS=','
            for item in $items; do
                echo "    - $item"
            done
            unset IFS
        fi
    fi
    echo ""

    # Reconciliation
    echo "reconciliation:"
    local inc_count="${CARDS["CARD_${idx}_reconciliation_inconsistencies_dict_count"]:-0}"
    if [[ "$inc_count" -eq 0 ]]; then
        echo "  inconsistencies: []"
    else
        echo "  inconsistencies:"
        local ic
        for ((ic=0; ic<inc_count; ic++)); do
            echo "    - type: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_type"]:-}"
            echo "      source: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_source"]:-}"
            echo "      target: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_target"]:-}"
            echo "      detail: ${CARDS["CARD_${idx}_reconciliation_inconsistencies_d${ic}_detail"]:-}"
        done
    fi
}

# ── Main ───────────────────────────────────────────────────────────

parse_cards || {
    echo "Error parsing $INPUT_PATH" >&2
    exit 0
}

if [[ $CARD_COUNT -eq 0 ]]; then
    echo "Error: no cards parsed from input" >&2
    exit 1
fi

reconcile

# Count stats
total_inconsistencies=0
co_mutator_count=0
for ((i=0; i<CARD_COUNT; i++)); do
    local_inc="${CARDS["CARD_${i}_reconciliation_inconsistencies_dict_count"]:-0}"
    total_inconsistencies=$((total_inconsistencies + local_inc))
    local_co="${CARDS["CARD_${i}_state_co_mutators"]:-}"
    [[ -n "$local_co" ]] && co_mutator_count=$((co_mutator_count + 1))
done

# Atomic write
{
    for ((i=0; i<CARD_COUNT; i++)); do
        if [[ $i -gt 0 ]]; then
            echo "---"
        fi
        emit_card "$i"
    done
} > "$TMP_PATH" 2>/dev/null || {
    echo "Error writing $OUTPUT_PATH" >&2
    exit 0
}

mv "$TMP_PATH" "$OUTPUT_PATH" 2>/dev/null || {
    echo "Error writing $OUTPUT_PATH" >&2
    exit 0
}

echo "Reconciled ${CARD_COUNT} cards — ${total_inconsistencies} inconsistencies, ${co_mutator_count} with co_mutators"
