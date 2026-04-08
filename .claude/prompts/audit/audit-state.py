#!/usr/bin/env python3
"""
Audit pipeline state management — deterministic resume detection.

Usage:
  python3 audit-state.py init <type> <value> [--project-root <path>]
  python3 audit-state.py complete <audit-dir> <step-name> [key=value ...]
  python3 audit-state.py status <audit-dir>
  python3 audit-state.py resume <audit-dir>
  python3 audit-state.py locate <type> <value> [--project-root <path>]

Commands:
  init      Create a new audit run directory with state.json
  complete  Mark a pipeline step as complete, record metadata
  status    Show current run state (human-readable)
  resume    Output machine-readable resume instruction (for orchestrator)
  locate    Print the audit directory path for an entry point (no creation)

Entry point types: feature, files, spec, prior

Directory structure:
  Feature-scoped:     .feature/<slug>/audit/run-NNN/
  Non-feature-scoped: .audit/<generated-slug>/run-NNN/

All pipeline artifacts for a run live in its run-NNN/ directory.
Each run has state.json tracking completed steps and metadata.
The audit/ directory has index.json listing all runs.
"""

import sys
import os
import json
import time
import hashlib
import re


# ── Pipeline definition ──────────────────────────────────────────────

PIPELINE_STEPS = [
    'classification',
    'exploration',
    'card_construction',
    'reconciliation',
    'domain_pruning',
    'view_projection',
    'assembly',
    'suspect',
    'prove',
    'fix',
    'regression',
    'report',
]


# ── Utilities ────────────────────────────────────────────────────────

def atomic_write(path, data):
    """Write JSON atomically via tmp+rename."""
    tmp = path + '.tmp'
    try:
        with open(tmp, 'w') as f:
            json.dump(data, f, indent=2)
            f.write('\n')
        os.rename(tmp, path)
    except OSError as e:
        print(f"Error writing {path}: {e}", file=sys.stderr)


def read_json(path):
    """Read JSON file, return empty dict on any error."""
    try:
        with open(path, 'r') as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


def make_slug(entry_type, entry_value):
    """Generate a deterministic, filesystem-safe slug for any entry point.

    Feature slugs pass through directly. Other entry types get a
    human-readable prefix + short hash for uniqueness.
    """
    if entry_type == 'feature':
        return entry_value

    safe = re.sub(r'[^a-zA-Z0-9_-]', '-', entry_value)
    safe = re.sub(r'-+', '-', safe).strip('-')
    short_hash = hashlib.sha256(entry_value.encode()).hexdigest()[:4]
    if len(safe) > 40:
        safe = safe[:40]
    return f"{entry_type}-{safe}-{short_hash}"


def audit_dir_for(entry_type, entry_value, project_root='.'):
    """Return the audit directory path for an entry point.

    Feature-scoped: <root>/.feature/<slug>/audit/
    Non-feature:    <root>/.audit/<slug>/
    """
    slug = make_slug(entry_type, entry_value)
    if entry_type == 'feature':
        return os.path.join(project_root, '.feature', slug, 'audit')
    return os.path.join(project_root, '.audit', slug)


def find_latest_run(audit_dir):
    """Find the latest run directory. Returns path or None."""
    index = read_json(os.path.join(audit_dir, 'index.json'))
    latest = index.get('latest_run')
    if latest:
        run_dir = os.path.join(audit_dir, latest)
        if os.path.exists(os.path.join(run_dir, 'state.json')):
            return run_dir

    if not os.path.isdir(audit_dir):
        return None
    runs = sorted(d for d in os.listdir(audit_dir)
                  if d.startswith('run-')
                  and os.path.isdir(os.path.join(audit_dir, d)))
    if not runs:
        return None
    return os.path.join(audit_dir, runs[-1])


# ── Commands ─────────────────────────────────────────────────────────

def cmd_init(entry_type, entry_value, project_root='.'):
    """Create a new audit run directory."""
    audit_dir = audit_dir_for(entry_type, entry_value, project_root)
    os.makedirs(audit_dir, exist_ok=True)

    existing = sorted(d for d in os.listdir(audit_dir)
                      if d.startswith('run-')
                      and os.path.isdir(os.path.join(audit_dir, d))) \
        if os.path.isdir(audit_dir) else []

    run_num = int(existing[-1].split('-')[1]) + 1 if existing else 1
    run_name = f'run-{run_num:03d}'
    run_dir = os.path.join(audit_dir, run_name)
    os.makedirs(run_dir, exist_ok=True)

    state = {
        'run': run_num,
        'run_name': run_name,
        'entry_type': entry_type,
        'entry_value': entry_value,
        'started': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'completed_steps': [],
        'current_step': 'classification',
        'status': 'in_progress',
        'clusters': 0,
        'lenses': 0,
        'findings': 0,
        'confirmed': 0,
        'fixed': 0,
    }
    atomic_write(os.path.join(run_dir, 'state.json'), state)

    index = read_json(os.path.join(audit_dir, 'index.json'))
    if 'runs' not in index:
        index['runs'] = []
    index['runs'].append({
        'name': run_name,
        'started': state['started'],
        'entry_type': entry_type,
        'entry_value': entry_value,
        'status': 'in_progress',
    })
    index['latest_run'] = run_name
    atomic_write(os.path.join(audit_dir, 'index.json'), index)

    # Output both the run directory and audit directory
    print(f"INIT_RUN_DIR={run_dir}")
    print(f"INIT_AUDIT_DIR={audit_dir}")
    print(f"INIT_RUN={run_name}")


def cmd_complete(audit_dir, step_name, **kwargs):
    """Mark a pipeline step as complete in the latest run."""
    run_dir = find_latest_run(audit_dir)
    if not run_dir:
        print("Error: no run found", file=sys.stderr)
        sys.exit(0)

    state_path = os.path.join(run_dir, 'state.json')
    state = read_json(state_path)
    if not state:
        print(f"Error: no state.json in {run_dir}", file=sys.stderr)
        sys.exit(0)

    if step_name not in PIPELINE_STEPS:
        print(f"Error: unknown step '{step_name}'", file=sys.stderr)
        sys.exit(0)

    completed = state.get('completed_steps', [])
    if step_name not in completed:
        completed.append(step_name)
    state['completed_steps'] = completed

    step_idx = PIPELINE_STEPS.index(step_name)
    if step_idx + 1 < len(PIPELINE_STEPS):
        state['current_step'] = PIPELINE_STEPS[step_idx + 1]
    else:
        state['current_step'] = 'done'
        state['status'] = 'complete'

        # Update index entry
        index_path = os.path.join(audit_dir, 'index.json')
        index = read_json(index_path)
        for entry in index.get('runs', []):
            if entry.get('name') == state.get('run_name'):
                entry['status'] = 'complete'
        atomic_write(index_path, index)

    for key, value in kwargs.items():
        if key in ('clusters', 'lenses', 'findings', 'confirmed', 'fixed'):
            state[key] = value

    atomic_write(state_path, state)
    print(f"COMPLETE={step_name}")
    print(f"NEXT={state['current_step']}")
    print(f"RUN_DIR={run_dir}")


def cmd_status(audit_dir):
    """Human-readable status of the latest run."""
    run_dir = find_latest_run(audit_dir)
    if not run_dir:
        print("NO_RUNS")
        return

    state = read_json(os.path.join(run_dir, 'state.json'))
    if not state:
        print("NO_STATE")
        return

    print(f"RUN={state.get('run_name', '?')}")
    print(f"STATUS={state.get('status', '?')}")
    print(f"CURRENT_STEP={state.get('current_step', '?')}")
    print(f"COMPLETED={','.join(state.get('completed_steps', []))}")
    print(f"CLUSTERS={state.get('clusters', 0)}")
    print(f"LENSES={state.get('lenses', 0)}")
    print(f"FINDINGS={state.get('findings', 0)}")
    print(f"CONFIRMED={state.get('confirmed', 0)}")
    print(f"FIXED={state.get('fixed', 0)}")
    print(f"RUN_DIR={run_dir}")


def cmd_resume(audit_dir):
    """Machine-readable resume instruction for orchestrator."""
    run_dir = find_latest_run(audit_dir)
    if not run_dir:
        print("RESUME_AT=init")
        return

    state = read_json(os.path.join(run_dir, 'state.json'))
    if not state:
        print("RESUME_AT=init")
        return

    if state.get('status') == 'complete':
        print("RESUME_AT=new_run")
        print(f"PRIOR_RUN_DIR={run_dir}")
        return

    current = state.get('current_step', 'classification')
    print(f"RESUME_AT={current}")
    print(f"RUN_DIR={run_dir}")
    print(f"CLUSTERS={state.get('clusters', 0)}")
    print(f"LENSES={state.get('lenses', 0)}")
    print(f"FINDINGS={state.get('findings', 0)}")
    print(f"CONFIRMED={state.get('confirmed', 0)}")


def cmd_locate(entry_type, entry_value, project_root='.'):
    """Print the audit directory path without creating anything."""
    print(audit_dir_for(entry_type, entry_value, project_root))


# ── Main ─────────────────────────────────────────────────────────────

def parse_project_root(args):
    """Extract --project-root from args, default to '.'."""
    if '--project-root' in args:
        idx = args.index('--project-root')
        if idx + 1 < len(args):
            return args[idx + 1]
    return '.'


def main():
    if len(sys.argv) < 3:
        print("Usage: python3 audit-state.py <command> <args...>",
              file=sys.stderr)
        sys.exit(0)

    cmd = sys.argv[1]

    if cmd == 'init':
        if len(sys.argv) < 4:
            print("Usage: audit-state.py init <type> <value> "
                  "[--project-root <path>]", file=sys.stderr)
            sys.exit(0)
        entry_type = sys.argv[2]
        entry_value = sys.argv[3]
        project_root = parse_project_root(sys.argv)
        cmd_init(entry_type, entry_value, project_root)

    elif cmd == 'complete':
        if len(sys.argv) < 4:
            print("Usage: audit-state.py complete <audit-dir> <step> "
                  "[key=value ...]", file=sys.stderr)
            sys.exit(0)
        audit_dir = sys.argv[2]
        step = sys.argv[3]
        meta = {}
        for arg in sys.argv[4:]:
            if '=' in arg:
                k, v = arg.split('=', 1)
                try:
                    meta[k] = int(v)
                except ValueError:
                    meta[k] = v
        cmd_complete(audit_dir, step, **meta)

    elif cmd == 'status':
        cmd_status(sys.argv[2])

    elif cmd == 'resume':
        cmd_resume(sys.argv[2])

    elif cmd == 'locate':
        if len(sys.argv) < 4:
            print("Usage: audit-state.py locate <type> <value> "
                  "[--project-root <path>]", file=sys.stderr)
            sys.exit(0)
        entry_type = sys.argv[2]
        entry_value = sys.argv[3]
        project_root = parse_project_root(sys.argv)
        cmd_locate(entry_type, entry_value, project_root)

    else:
        print(f"Unknown command: {cmd}", file=sys.stderr)
        sys.exit(0)


if __name__ == '__main__':
    main()
