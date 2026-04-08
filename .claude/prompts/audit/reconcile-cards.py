#!/usr/bin/env python3
"""
Reconcile construct cards — invert outgoing edges to add incoming edges,
derive co_mutators/co_readers, flag inconsistencies.

Usage: python3 reconcile-cards.py <feature-dir>

Reads:  <feature-dir>/construct-cards.yaml
Writes: <feature-dir>/analysis-cards.yaml (reconciled)

This is a mechanical data transformation. No LLM judgment involved.
"""

import sys
import os
import re


def parse_yaml_cards(text):
    """Parse YAML-ish construct cards separated by '---'.

    We parse a restricted subset of YAML that matches our card schema
    rather than requiring PyYAML. Cards use only: scalars, lists, and
    list-of-dicts with scalar values.
    """
    cards = []
    documents = re.split(r'\n---\s*\n', '\n' + text.strip() + '\n')

    for doc in documents:
        doc = doc.strip()
        if not doc:
            continue
        card = parse_yaml_block(doc, 0)
        if card and card.get('construct'):
            cards.append(card)

    return cards


def parse_yaml_block(text, base_indent):
    """Parse a YAML block into a dict."""
    result = {}
    lines = text.split('\n')
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped or stripped.startswith('#'):
            i += 1
            continue

        indent = len(line) - len(line.lstrip())
        if indent < base_indent:
            break

        # key: value
        match = re.match(r'^(\s*)([a-z_]+):\s*(.*)', line)
        if not match:
            i += 1
            continue

        key = match.group(2)
        value = match.group(3).strip()

        if value == '' or value == '[]':
            # Check if next lines are indented (nested block or list)
            next_indent = None
            for j in range(i + 1, len(lines)):
                next_stripped = lines[j].strip()
                if next_stripped and not next_stripped.startswith('#'):
                    next_indent = len(lines[j]) - len(lines[j].lstrip())
                    break

            if value == '[]':
                result[key] = []
            elif next_indent is not None and next_indent > indent:
                # Collect nested block
                block_lines = []
                for j in range(i + 1, len(lines)):
                    l = lines[j]
                    ls = l.strip()
                    if not ls or ls.startswith('#'):
                        block_lines.append(l)
                        continue
                    li = len(l) - len(l.lstrip())
                    if li <= indent:
                        break
                    block_lines.append(l)

                block_text = '\n'.join(block_lines)

                # Is it a list of items (lines starting with '- ')?
                first_content = None
                for bl in block_lines:
                    bs = bl.strip()
                    if bs and not bs.startswith('#'):
                        first_content = bs
                        break

                if first_content and first_content.startswith('- '):
                    result[key] = parse_yaml_list(block_text, next_indent)
                else:
                    result[key] = parse_yaml_block(block_text, next_indent)

                i += 1 + len(block_lines)
                continue
            else:
                result[key] = ''
        elif value.startswith('[') and value.endswith(']'):
            # Inline list: [a, b, c]
            inner = value[1:-1].strip()
            if inner:
                result[key] = [x.strip().strip('"').strip("'")
                               for x in inner.split(',')]
            else:
                result[key] = []
        else:
            # Scalar value
            result[key] = value.strip('"').strip("'")

        i += 1

    return result


def parse_yaml_list(text, base_indent):
    """Parse a YAML list (lines starting with '- ')."""
    items = []
    lines = text.split('\n')
    current_item = None
    item_lines = []

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue

        if stripped.startswith('- '):
            if current_item is not None:
                items.append(current_item)

            rest = stripped[2:].strip()
            # Simple scalar list item
            if ':' not in rest or rest.startswith('"') or rest.startswith("'"):
                current_item = rest.strip('"').strip("'")
                item_lines = []
            else:
                # Dict item starting on the '- ' line
                current_item = {}
                k, v = rest.split(':', 1)
                current_item[k.strip()] = v.strip().strip('"').strip("'")
                item_lines = []
        elif current_item is not None and isinstance(current_item, dict):
            # Continuation of a dict item
            if ':' in stripped:
                k, v = stripped.split(':', 1)
                current_item[k.strip()] = v.strip().strip('"').strip("'")

    if current_item is not None:
        items.append(current_item)

    return items


def reconcile(cards):
    """Add incoming edges, derive co_mutators/co_readers, flag inconsistencies."""

    # Index cards by construct name
    by_name = {c['construct']: c for c in cards}

    # Initialize reconciliation fields on every card
    for card in cards:
        exec_section = card.get('execution', {})
        if isinstance(exec_section, str):
            exec_section = {}
            card['execution'] = exec_section
        exec_section.setdefault('invoked_by', [])

        state_section = card.get('state', {})
        if isinstance(state_section, str):
            state_section = {}
            card['state'] = state_section
        state_section.setdefault('read_by', [])
        state_section.setdefault('written_by', [])
        state_section.setdefault('co_mutators', [])
        state_section.setdefault('co_readers', [])

        card.setdefault('reconciliation', {'inconsistencies': []})

    # Pass 1: Invert execution edges
    for card in cards:
        source = card['construct']
        invokes = card.get('execution', {}).get('invokes', [])
        if isinstance(invokes, str):
            invokes = [invokes] if invokes else []

        for target_name in invokes:
            if target_name in by_name:
                target_card = by_name[target_name]
                invoked_by = target_card['execution']['invoked_by']
                if source not in invoked_by:
                    invoked_by.append(source)

    # Pass 2: Invert state edges
    for card in cards:
        source = card['construct']

        reads_ext = card.get('state', {}).get('reads_external', [])
        if isinstance(reads_ext, str):
            reads_ext = [reads_ext] if reads_ext else []

        for ref in reads_ext:
            # Format: ConstructName.fieldName
            parts = ref.split('.', 1) if isinstance(ref, str) else []
            if len(parts) >= 1:
                target_name = parts[0]
                if target_name in by_name:
                    target_card = by_name[target_name]
                    read_by = target_card['state']['read_by']
                    if source not in read_by:
                        read_by.append(source)

        writes_ext = card.get('state', {}).get('writes_external', [])
        if isinstance(writes_ext, str):
            writes_ext = [writes_ext] if writes_ext else []

        for ref in writes_ext:
            parts = ref.split('.', 1) if isinstance(ref, str) else []
            if len(parts) >= 1:
                target_name = parts[0]
                if target_name in by_name:
                    target_card = by_name[target_name]
                    written_by = target_card['state']['written_by']
                    if source not in written_by:
                        written_by.append(source)

    # Pass 3: Derive co_mutators (constructs that both write to the same owned state)
    for card in cards:
        owner = card['construct']
        owns = card.get('state', {}).get('owns', [])
        if not owns:
            continue

        # Find all constructs that write to this owner's state
        writers = set()
        for other_card in cards:
            if other_card['construct'] == owner:
                continue
            writes_ext = other_card.get('state', {}).get('writes_external', [])
            if isinstance(writes_ext, str):
                writes_ext = [writes_ext] if writes_ext else []
            for ref in writes_ext:
                if isinstance(ref, str) and ref.split('.', 1)[0] == owner:
                    writers.add(other_card['construct'])

        # co_mutators: other constructs that also write to this owner's state
        # Set on both the owner and each writer
        for writer in writers:
            other_writers = writers - {writer}
            if other_writers:
                writer_card = by_name[writer]
                for ow in other_writers:
                    if ow not in writer_card['state']['co_mutators']:
                        writer_card['state']['co_mutators'].append(ow)

            # Owner also gets co_mutators if multiple external writers exist
            if writer not in card['state']['co_mutators']:
                card['state']['co_mutators'].append(writer)

    # Pass 4: Derive co_readers (constructs that both read the same owned state)
    for card in cards:
        owner = card['construct']
        owns = card.get('state', {}).get('owns', [])
        if not owns:
            continue

        readers = set()
        for other_card in cards:
            if other_card['construct'] == owner:
                continue
            reads_ext = other_card.get('state', {}).get('reads_external', [])
            if isinstance(reads_ext, str):
                reads_ext = [reads_ext] if reads_ext else []
            for ref in reads_ext:
                if isinstance(ref, str) and ref.split('.', 1)[0] == owner:
                    readers.add(other_card['construct'])

        for reader in readers:
            other_readers = readers - {reader}
            for ordr in other_readers:
                reader_card = by_name[reader]
                if ordr not in reader_card['state']['co_readers']:
                    reader_card['state']['co_readers'].append(ordr)

    # Pass 5: Flag inconsistencies
    for card in cards:
        source = card['construct']
        invokes = card.get('execution', {}).get('invokes', [])
        if isinstance(invokes, str):
            invokes = [invokes] if invokes else []

        for target_name in invokes:
            if target_name in by_name:
                target_card = by_name[target_name]
                entry_points = target_card.get('execution', {}).get(
                    'entry_points', [])
                if isinstance(entry_points, str):
                    entry_points = [entry_points] if entry_points else []

                # Check if any entry point could match
                # (we check for substring match since invokes might reference
                # a method while entry_points lists method names)
                if entry_points and not any(
                    ep in target_name or target_name.endswith('.' + ep)
                    for ep in entry_points
                ):
                    # No obvious match — flag as inconsistency
                    card['reconciliation']['inconsistencies'].append({
                        'type': 'invokes_without_entry_point',
                        'source': source,
                        'target': target_name,
                        'detail': (f'{source} invokes {target_name} but '
                                   f'{target_name} entry_points '
                                   f'{entry_points} may not include the '
                                   f'called method')
                    })

    return cards


def _as_str_list(val):
    """Coerce a field value to a list of strings for serialization."""
    if isinstance(val, str):
        return [val] if val else []
    if isinstance(val, list):
        return [str(v) for v in val]
    return []


def emit_yaml_card(card):
    """Serialize a card back to our YAML subset."""
    lines = []

    lines.append(f"construct: {card.get('construct', '')}")
    lines.append(f"kind: {card.get('kind', '')}")
    lines.append(f"location: {card.get('location', '')}")
    lines.append('')

    # Execution
    lines.append('execution:')
    exec_s = card.get('execution', {})
    if isinstance(exec_s, str):
        exec_s = {}
    lines.append(
        f"  invokes: [{', '.join(_as_str_list(exec_s.get('invokes', [])))}]")
    lines.append(
        f"  invoked_by: "
        f"[{', '.join(_as_str_list(exec_s.get('invoked_by', [])))}]")
    lines.append(
        f"  entry_points: "
        f"[{', '.join(_as_str_list(exec_s.get('entry_points', [])))}]")
    lines.append('')

    # State
    lines.append('state:')
    state_s = card.get('state', {})
    if isinstance(state_s, str):
        state_s = {}
    lines.append(
        f"  owns: [{', '.join(_as_str_list(state_s.get('owns', [])))}]")
    lines.append(
        f"  reads_external: "
        f"[{', '.join(_as_str_list(state_s.get('reads_external', [])))}]")
    lines.append(
        f"  writes_external: "
        f"[{', '.join(_as_str_list(state_s.get('writes_external', [])))}]")
    lines.append(
        f"  read_by: "
        f"[{', '.join(_as_str_list(state_s.get('read_by', [])))}]")
    lines.append(
        f"  written_by: "
        f"[{', '.join(_as_str_list(state_s.get('written_by', [])))}]")
    lines.append(
        f"  co_mutators: "
        f"[{', '.join(_as_str_list(state_s.get('co_mutators', [])))}]")
    lines.append(
        f"  co_readers: "
        f"[{', '.join(_as_str_list(state_s.get('co_readers', [])))}]")
    lines.append('')

    # Contracts
    lines.append('contracts:')
    contracts = card.get('contracts', {})
    if isinstance(contracts, str):
        contracts = {}

    guarantees = contracts.get('guarantees', [])
    if not guarantees:
        lines.append('  guarantees: []')
    else:
        lines.append('  guarantees:')
        for g in guarantees:
            if isinstance(g, dict):
                lines.append(f"    - what: {g.get('what', '')}")
                lines.append(f"      evidence: {g.get('evidence', '')}")
            else:
                lines.append(f"    - {g}")

    assumptions = contracts.get('assumptions', [])
    if not assumptions:
        lines.append('  assumptions: []')
    else:
        lines.append('  assumptions:')
        for a in assumptions:
            if isinstance(a, dict):
                lines.append(f"    - what: {a.get('what', '')}")
                lines.append(f"      evidence: {a.get('evidence', '')}")
                lines.append(
                    f"      failure_mode: {a.get('failure_mode', '')}")
            else:
                lines.append(f"    - {a}")
    lines.append('')

    # Reconciliation
    recon = card.get('reconciliation', {})
    if isinstance(recon, str):
        recon = {}
    inconsistencies = recon.get('inconsistencies', [])
    if not inconsistencies:
        lines.append('reconciliation:')
        lines.append('  inconsistencies: []')
    else:
        lines.append('reconciliation:')
        lines.append('  inconsistencies:')
        for inc in inconsistencies:
            if isinstance(inc, dict):
                lines.append(f"    - type: {inc.get('type', '')}")
                lines.append(f"      source: {inc.get('source', '')}")
                lines.append(f"      target: {inc.get('target', '')}")
                lines.append(f"      detail: {inc.get('detail', '')}")

    return '\n'.join(lines)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 reconcile-cards.py <feature-dir>",
              file=sys.stderr)
        sys.exit(1)

    feature_dir = sys.argv[1]
    input_path = os.path.join(feature_dir, 'construct-cards.yaml')
    output_path = os.path.join(feature_dir, 'analysis-cards.yaml')
    tmp_path = output_path + '.tmp'

    if not os.path.exists(input_path):
        print(f"Error: {input_path} not found", file=sys.stderr)
        sys.exit(1)

    try:
        with open(input_path, 'r') as f:
            text = f.read()
    except OSError as e:
        print(f"Error reading {input_path}: {e}", file=sys.stderr)
        sys.exit(0)

    try:
        cards = parse_yaml_cards(text)
    except Exception as e:
        print(f"Error parsing {input_path}: {e}", file=sys.stderr)
        sys.exit(0)

    if not cards:
        print("Error: no cards parsed from input", file=sys.stderr)
        sys.exit(1)

    reconciled = reconcile(cards)

    # Count stats
    total_inconsistencies = sum(
        len(c.get('reconciliation', {}).get('inconsistencies', []))
        for c in reconciled
    )
    co_mutator_count = sum(
        1 for c in reconciled
        if c.get('state', {}).get('co_mutators', [])
    )

    # Atomic write
    try:
        output = '\n---\n'.join(emit_yaml_card(c) for c in reconciled)
        with open(tmp_path, 'w') as f:
            f.write(output)
            f.write('\n')
        os.rename(tmp_path, output_path)
    except OSError as e:
        print(f"Error writing {output_path}: {e}", file=sys.stderr)
        sys.exit(0)

    print(f"Reconciled {len(reconciled)} cards — "
          f"{total_inconsistencies} inconsistencies, "
          f"{co_mutator_count} with co_mutators")


if __name__ == '__main__':
    main()
