#!/usr/bin/env python3
"""
Extract per-lens card views from reconciled analysis cards.

Usage:
  Phase 1 (detect active lenses):
    python3 extract-views.py detect <feature-dir>

  Phase 2 (produce projections after pruning):
    python3 extract-views.py project <feature-dir>

Phase 1 reads analysis-cards.yaml, evaluates lens predicates,
writes active-lenses.md with candidate-active lenses.

Phase 2 reads analysis-cards.yaml and active-lenses.md (after LLM
pruning has marked lenses as confirmed/pruned), produces per-lens
projected card files and the full analysis view.
"""

import sys
import os
import re


# ── YAML parsing (same restricted subset as reconcile-cards.py) ──────

def parse_yaml_cards(text):
    """Parse YAML-ish construct cards separated by '---'."""
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
        match = re.match(r'^(\s*)([a-z_]+):\s*(.*)', line)
        if not match:
            i += 1
            continue
        key = match.group(2)
        value = match.group(3).strip()
        if value == '' or value == '[]':
            next_indent = None
            for j in range(i + 1, len(lines)):
                ns = lines[j].strip()
                if ns and not ns.startswith('#'):
                    next_indent = len(lines[j]) - len(lines[j].lstrip())
                    break
            if value == '[]':
                result[key] = []
            elif next_indent is not None and next_indent > indent:
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
            inner = value[1:-1].strip()
            if inner:
                result[key] = [x.strip().strip('"').strip("'")
                               for x in inner.split(',')]
            else:
                result[key] = []
        else:
            result[key] = value.strip('"').strip("'")
        i += 1
    return result


def parse_yaml_list(text, base_indent):
    """Parse a YAML list."""
    items = []
    lines = text.split('\n')
    current_item = None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        if stripped.startswith('- '):
            if current_item is not None:
                items.append(current_item)
            rest = stripped[2:].strip()
            if ':' not in rest or rest.startswith('"') or rest.startswith("'"):
                current_item = rest.strip('"').strip("'")
            else:
                current_item = {}
                k, v = rest.split(':', 1)
                current_item[k.strip()] = v.strip().strip('"').strip("'")
        elif current_item is not None and isinstance(current_item, dict):
            if ':' in stripped:
                k, v = stripped.split(':', 1)
                current_item[k.strip()] = v.strip().strip('"').strip("'")
    if current_item is not None:
        items.append(current_item)
    return items


# ── Helper functions ─────────────────────────────────────────────────

def get_list(card, section, field):
    """Safely get a list from a nested card structure."""
    s = card.get(section, {})
    if isinstance(s, str):
        return []
    val = s.get(field, [])
    if isinstance(val, str):
        return [val] if val else []
    return val


def get_location_file(card):
    """Extract file path from location field."""
    loc = card.get('location', '')
    if ':' in loc:
        return loc.split(':')[0]
    return loc


def constructs_cross_module(card_a, card_b):
    """Check if two constructs are in different files (cross-module)."""
    return get_location_file(card_a) != get_location_file(card_b)


# ── Lens predicate functions ─────────────────────────────────────────

def check_shared_state(card, by_name):
    for field in ['owns', 'reads_external', 'writes_external',
                  'co_mutators', 'co_readers']:
        if get_list(card, 'state', field):
            return True
    return False


def check_resource_lifecycle(card, by_name):
    """Card qualifies if it owns state AND has invokes/invoked_by
    to constructs that also own state."""
    owns = get_list(card, 'state', 'owns')
    if not owns:
        return False
    for target in (get_list(card, 'execution', 'invokes') +
                   get_list(card, 'execution', 'invoked_by')):
        if target in by_name:
            if get_list(by_name[target], 'state', 'owns'):
                return True
    return False


def check_contract_boundaries(card, by_name):
    """Card qualifies if it has cross-module invokes or invoked_by."""
    for target in get_list(card, 'execution', 'invokes'):
        if target in by_name and constructs_cross_module(card,
                                                         by_name[target]):
            return True
    for target in get_list(card, 'execution', 'invoked_by'):
        if target in by_name and constructs_cross_module(card,
                                                         by_name[target]):
            return True
    return False


def check_data_transformation(card, by_name):
    """Card qualifies if it reads from one construct and writes to another."""
    reads = get_list(card, 'state', 'reads_external')
    writes = get_list(card, 'state', 'writes_external')
    if reads and writes:
        read_targets = {r.split('.')[0] for r in reads if '.' in r}
        write_targets = {w.split('.')[0] for w in writes if '.' in w}
        if read_targets != write_targets:
            return True
    return False


def check_concurrency(card, by_name):
    return bool(get_list(card, 'state', 'co_mutators'))


def check_dispatch_routing(card, by_name):
    """Card qualifies if it invokes 3+ constructs that share no mutual
    execution edges."""
    invokes = get_list(card, 'execution', 'invokes')
    if len(invokes) < 3:
        return False

    targets_in_scope = [t for t in invokes if t in by_name]
    if len(targets_in_scope) < 3:
        return False

    for i, t1 in enumerate(targets_in_scope):
        t1_invokes = set(get_list(by_name[t1], 'execution', 'invokes'))
        t1_invoked_by = set(
            get_list(by_name[t1], 'execution', 'invoked_by'))
        for t2 in targets_in_scope[i + 1:]:
            if t2 in t1_invokes or t2 in t1_invoked_by:
                return False
    return True


# ── Lens definitions ─────────────────────────────────────────────────

LENSES = {
    'shared_state': {
        'description': 'Shared mutable state consistency',
        'challenge': ('This codebase has shared mutable state between '
                      'constructs. Prove it doesn\'t.'),
        'fields': {
            'execution': ['invokes', 'invoked_by'],
            'state': ['owns', 'reads_external', 'writes_external',
                      'read_by', 'written_by', 'co_mutators', 'co_readers'],
        },
        'predicate': check_shared_state,
    },
    'resource_lifecycle': {
        'description': 'Resource acquisition/release lifecycle',
        'challenge': ('This codebase has resource lifecycle patterns '
                      '(acquire/release, open/close). Prove it doesn\'t.'),
        'fields': {
            'execution': ['invokes', 'invoked_by', 'entry_points'],
            'state': ['owns'],
        },
        'predicate': check_resource_lifecycle,
    },
    'contract_boundaries': {
        'description': 'Cross-module contract violations',
        'challenge': ('This codebase has cross-module invocations where '
                      'caller and callee assumptions could mismatch. '
                      'Prove it doesn\'t.'),
        'fields': {
            'execution': ['invokes', 'invoked_by', 'entry_points'],
            'reconciliation': ['inconsistencies'],
        },
        'predicate': check_contract_boundaries,
    },
    'data_transformation': {
        'description': 'Data format/type transformation fidelity',
        'challenge': ('This codebase has data transformation chains where '
                      'data changes form between producer and consumer. '
                      'Prove it doesn\'t.'),
        'fields': {
            'execution': ['invokes', 'invoked_by'],
            'state': ['reads_external', 'writes_external'],
        },
        'predicate': check_data_transformation,
    },
    'concurrency': {
        'description': 'Concurrent access to shared state',
        'challenge': ('This codebase has concurrent access patterns — '
                      'multiple constructs mutate the same state. '
                      'Prove it doesn\'t.'),
        'fields': {
            'execution': ['invoked_by'],
            'state': ['owns', 'co_mutators', 'co_readers',
                      'writes_external', 'written_by'],
        },
        'predicate': check_concurrency,
    },
    'dispatch_routing': {
        'description': 'Dispatch/routing pattern correctness',
        'challenge': ('This codebase has dispatch patterns where one '
                      'construct routes to multiple independent handlers. '
                      'Prove it doesn\'t.'),
        'fields': {
            'execution': ['invokes', 'invoked_by', 'entry_points'],
        },
        'predicate': check_dispatch_routing,
    },
}


# ── Phase 1: Detect active lenses ────────────────────────────────────

def detect_lenses(cards):
    """Evaluate each lens predicate against all cards.
    Returns dict of lens_name -> list of qualifying construct names."""
    by_name = {c['construct']: c for c in cards}
    results = {}
    for lens_name, lens_def in LENSES.items():
        qualifying = []
        for card in cards:
            if lens_def['predicate'](card, by_name):
                qualifying.append(card['construct'])
        if qualifying:
            results[lens_name] = qualifying
    return results


def write_active_lenses(feature_dir, lens_results):
    """Write active-lenses.md with candidate lenses and their challenges."""
    lines = [
        '# Active Domain Lenses',
        '',
        'Candidate-active lenses detected from construct cards.',
        'Each lens must survive pruning: the LLM must prove the domain',
        'does NOT apply to eliminate it. Lenses not listed here had zero',
        'qualifying constructs and are already pruned.',
        '',
        '---',
        '',
    ]

    for lens_name, qualifying in sorted(lens_results.items()):
        lens_def = LENSES[lens_name]
        lines.append(f'## {lens_name}')
        lines.append(f'')
        lines.append(f'**Description:** {lens_def["description"]}')
        lines.append(f'**Qualifying constructs:** {len(qualifying)}')
        lines.append(f'**Challenge:** "{lens_def["challenge"]}"')
        lines.append(f'**Status:** CANDIDATE')
        lines.append(f'')
        lines.append(f'Constructs: {", ".join(qualifying[:10])}')
        if len(qualifying) > 10:
            lines.append(f'  ... and {len(qualifying) - 10} more')
        lines.append(f'')
        lines.append('---')
        lines.append('')

    # Also note lenses that were eliminated by having zero constructs
    eliminated = set(LENSES.keys()) - set(lens_results.keys())
    if eliminated:
        lines.append('## Eliminated (zero qualifying constructs)')
        lines.append('')
        for lens_name in sorted(eliminated):
            lines.append(
                f'- **{lens_name}**: {LENSES[lens_name]["description"]} '
                f'— no constructs matched predicate')
        lines.append('')

    path = os.path.join(feature_dir, 'active-lenses.md')
    tmp_path = path + '.tmp'
    try:
        with open(tmp_path, 'w') as f:
            f.write('\n'.join(lines))
        os.rename(tmp_path, path)
    except OSError as e:
        print(f"Error writing {path}: {e}", file=sys.stderr)

    return len(lens_results), len(eliminated)


# ── Phase 2: Produce projections ─────────────────────────────────────

def parse_active_lenses(feature_dir):
    """Read active-lenses.md and return list of confirmed lens names."""
    path = os.path.join(feature_dir, 'active-lenses.md')
    if not os.path.exists(path):
        print(f"Error: {path} not found", file=sys.stderr)
        sys.exit(1)

    try:
        with open(path, 'r') as f:
            text = f.read()
    except OSError as e:
        print(f"Error reading {path}: {e}", file=sys.stderr)
        return []

    confirmed = []
    current_lens = None

    for line in text.split('\n'):
        # Match lens headers: ## lens_name
        match = re.match(r'^## ([a-z_]+)\s*$', line.strip())
        if match:
            current_lens = match.group(1)
            continue

        if current_lens and '**Status:**' in line:
            if 'CONFIRMED' in line:
                confirmed.append(current_lens)
            current_lens = None

    return confirmed


def project_card(card, lens_name):
    """Project a card to include only fields relevant to the lens."""
    lens_def = LENSES[lens_name]
    field_map = lens_def['fields']

    projected = {
        'construct': card['construct'],
        'kind': card.get('kind', ''),
        'location': card.get('location', ''),
    }

    for section, fields in field_map.items():
        section_data = card.get(section, {})
        if isinstance(section_data, str):
            continue
        projected_section = {}
        for field in fields:
            if field in section_data:
                projected_section[field] = section_data[field]
        if projected_section:
            projected[section] = projected_section

    return projected


def emit_projected_card(card):
    """Serialize a projected card to YAML-ish format."""
    lines = []
    lines.append(f"construct: {card.get('construct', '')}")
    lines.append(f"kind: {card.get('kind', '')}")
    lines.append(f"location: {card.get('location', '')}")

    for section in ['execution', 'state', 'reconciliation']:
        if section in card:
            lines.append('')
            lines.append(f'{section}:')
            section_data = card[section]
            for key, val in section_data.items():
                if isinstance(val, list):
                    if not val:
                        lines.append(f'  {key}: []')
                    elif isinstance(val[0], dict):
                        lines.append(f'  {key}:')
                        for item in val:
                            first = True
                            for k, v in item.items():
                                prefix = '    - ' if first else '      '
                                lines.append(f'{prefix}{k}: {v}')
                                first = False
                    else:
                        lines.append(
                            f"  {key}: [{', '.join(str(v) for v in val)}]")
                else:
                    lines.append(f'  {key}: {val}')

    return '\n'.join(lines)


def produce_projections(feature_dir, cards, confirmed_lenses):
    """Write per-lens projected card files."""
    by_name = {c['construct']: c for c in cards}
    stats = {}

    for lens_name in confirmed_lenses:
        if lens_name not in LENSES:
            print(f"Warning: unknown lens '{lens_name}', skipping",
                  file=sys.stderr)
            continue
        lens_def = LENSES[lens_name]
        qualifying = [c for c in cards
                      if lens_def['predicate'](c, by_name)]

        projected = [project_card(c, lens_name) for c in qualifying]

        output = '\n---\n'.join(emit_projected_card(c) for c in projected)
        path = os.path.join(feature_dir, f'lens-{lens_name}-cards.yaml')
        tmp_path = path + '.tmp'
        try:
            with open(tmp_path, 'w') as f:
                f.write(output)
                f.write('\n')
            os.rename(tmp_path, path)
        except OSError as e:
            print(f"Error writing {path}: {e}", file=sys.stderr)
            continue

        stats[lens_name] = len(projected)

    return stats


# ── Main ─────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 extract-views.py <detect|project> "
              "<feature-dir>", file=sys.stderr)
        sys.exit(1)

    phase = sys.argv[1]
    feature_dir = sys.argv[2]
    cards_path = os.path.join(feature_dir, 'analysis-cards.yaml')

    if not os.path.exists(cards_path):
        print(f"Error: {cards_path} not found", file=sys.stderr)
        sys.exit(1)

    try:
        with open(cards_path, 'r') as f:
            text = f.read()
    except OSError as e:
        print(f"Error reading {cards_path}: {e}", file=sys.stderr)
        sys.exit(0)

    try:
        cards = parse_yaml_cards(text)
    except Exception as e:
        print(f"Error parsing {cards_path}: {e}", file=sys.stderr)
        sys.exit(0)

    if not cards:
        print("Error: no cards parsed", file=sys.stderr)
        sys.exit(1)

    if phase == 'detect':
        lens_results = detect_lenses(cards)
        active, eliminated = write_active_lenses(feature_dir, lens_results)
        print(f"Detected {active} candidate lenses, "
              f"{eliminated} eliminated — "
              f"active: {', '.join(sorted(lens_results.keys()))}")

    elif phase == 'project':
        confirmed = parse_active_lenses(feature_dir)
        if not confirmed:
            print("No confirmed lenses found in active-lenses.md",
                  file=sys.stderr)
            sys.exit(1)

        stats = produce_projections(feature_dir, cards, confirmed)

        total = sum(stats.values())
        details = ', '.join(f'{k}={v}' for k, v in sorted(stats.items()))
        print(f"Projected {len(confirmed)} lenses, "
              f"{total} total card projections — {details}")

    else:
        print(f"Unknown phase: {phase}. Use 'detect' or 'project'.",
              file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
