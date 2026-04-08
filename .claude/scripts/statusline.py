#!/usr/bin/env python3
"""vallorcine status line — Python enhanced implementation.

Reads pipeline state from JSON state files and produces ANSI-colored status
output identical to statusline.sh. Advantages over bash:
  - Native JSON parsing (no grep/sed patterns)
  - Reads .subagent-state for active subagent display
  - Single-pass state file reads

State files (JSON):
  .claude/.token-state          — {"feature_dir":"...","cached_stage":"...",...}
  .claude/.statusline-baseline  — {"baseline_stage":"...","baseline_ctx_tokens":N,...}
  .claude/.subagent-state       — {"active":true,"description":"...","started":"..."}

Stdlib only — no pip dependencies.
"""

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


def fmt_tokens(n: int) -> str:
    """Format token count: 1234567 → '1.2M', 51000 → '51.0K'."""
    if n >= 1_000_000:
        return f"{n // 1_000_000}.{(n % 1_000_000) // 100_000}M"
    elif n >= 1_000:
        return f"{n // 1_000}.{(n % 1_000) // 100}K"
    return str(n)


def read_json_or_shell(path: Path) -> dict:
    """Read a state file — JSON or legacy shell variable format."""
    if not path.is_file():
        return {}
    try:
        text = path.read_text().strip()
        if not text:
            return {}
        if text.startswith("{"):
            return json.loads(text)
        # Legacy shell variable format: key=value lines
        result = {}
        for line in text.splitlines():
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                key, _, val = line.partition("=")
                # Strip shell quoting ($'...' or simple quotes)
                val = val.strip("'\"")
                if val.startswith("$'"):
                    val = val[2:-1] if val.endswith("'") else val[2:]
                result[key.strip()] = val
        return result
    except (json.JSONDecodeError, OSError):
        return {}


def read_stage_from_status(status_path: str) -> tuple[str, str]:
    """Read Stage and Substage from status.md."""
    stage = ""
    substage = ""
    try:
        with open(status_path) as f:
            for line in f:
                if line.startswith("**Stage:**"):
                    stage = line.split("**Stage:**", 1)[1].strip()
                elif line.startswith("**Substage:**"):
                    substage = line.split("**Substage:**", 1)[1].strip()
                    break  # Substage always comes after Stage
    except OSError:
        pass
    return stage, substage


def substage_label(stage: str, substage: str) -> str:
    """Map stage/substage to display label."""
    labels = {
        "scoping": {
            "interviewing": "interviewing",
            "confirming-brief": "confirming brief",
            "complete": "complete",
        },
        "planning": {
            "loading-context": "loading context",
            "surveying-codebase": "surveying code",
            "confirmed-design": "design confirmed",
            "writing-stubs": "writing stubs",
            "contract-revised": "contract revised",
        },
        "testing": {
            "planning": "planning tests",
            "confirming-plan": "confirming plan",
            "writing-tests": "writing tests",
            "verifying-failures": "verifying failures",
        },
        "implementation": {
            "loading-context": "loading context",
            "implementing": "implementing",
        },
        "refactor": {
            "loading-context": "loading context",
        },
    }

    # Direct lookup
    stage_labels = labels.get(stage, {})
    if substage in stage_labels:
        return stage_labels[substage]

    # Pattern matching for complex substages
    if stage == "testing":
        if "verified" in substage and "failing" in substage:
            return "tests verified"
        if substage.startswith("escalation"):
            return "escalation"
    elif stage == "implementation":
        if substage.startswith("implemented:"):
            return substage.split(":", 1)[1].strip()
        if "all" in substage and "tests" in substage and "passing" in substage:
            return "all passing"
        if substage.startswith("escalat"):
            return "escalation"
    elif stage == "refactor":
        refactor_map = {
            "coding": "coding standards",
            "duplication": "DRY",
            "security": "security",
            "performance": "performance",
            "missing": "missing tests",
            "integration": "integration",
            "documentation": "docs",
            "security-review": "security review",
            "final-lint": "final lint",
        }
        if "refactor:complete" in substage or "complete" in substage:
            return "complete"
        for key, label in refactor_map.items():
            if key in substage:
                return label
        if substage.startswith("escalat"):
            return "escalation"
        if substage.startswith("cycle-5"):
            return "cycle limit"
    elif stage == "pr":
        if substage == "pr-draft-written":
            return "draft ready"

    return ""


def build_stage_display(slug: str, stage: str, substage: str) -> str:
    """Build the stage display string."""
    display_stage = {
        "implementation": "implementing",
        "pr": "PR draft",
    }.get(stage, stage)

    sub = substage_label(stage, substage)
    if stage == "domains":
        sub = substage  # domains shows raw substage
    base = f"{slug} · {display_stage}"
    return f"{base} · {sub}" if sub else base


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    # Read stdin JSON
    try:
        session = json.loads(sys.stdin.read())
    except (json.JSONDecodeError, ValueError):
        session = {}

    ctx_window = session.get("context_window", {})
    context_pct = ctx_window.get("used_percentage")
    ctx_size = ctx_window.get("context_window_size")

    # Compute current context tokens
    current_ctx_tokens = None
    if context_pct is not None and ctx_size is not None:
        current_ctx_tokens = int(context_pct * ctx_size / 100)

    # Read pipeline state
    stage_display = ""
    stage_tokens = ""
    baseline_file = Path(".claude/.statusline-baseline")
    token_state = read_json_or_shell(Path(".claude/.token-state"))

    feature_dir = token_state.get("feature_dir", "")
    cached_stage = token_state.get("cached_stage", "")

    if feature_dir and os.path.isfile(f"{feature_dir}/status.md"):
        slug = os.path.basename(feature_dir)
        actual_stage, substage = read_stage_from_status(f"{feature_dir}/status.md")
        current_stage = actual_stage or cached_stage

        # Terminal states
        is_terminal = f"{current_stage}/{substage}" in ("pr/created", "pr/complete")

        if not is_terminal:
            stage_display = build_stage_display(slug, current_stage, substage)

            # Per-stage token tracking
            if current_ctx_tokens is not None:
                baseline = read_json_or_shell(baseline_file)
                baseline_stage = baseline.get("baseline_stage", "")
                try:
                    baseline_ctx = int(baseline.get("baseline_ctx_tokens", 0))
                except (ValueError, TypeError):
                    baseline_ctx = 0

                if baseline_stage != current_stage or baseline_ctx == 0:
                    # Stage transition or no valid baseline — reset
                    new_baseline = {
                        "baseline_stage": current_stage,
                        "baseline_ctx_tokens": current_ctx_tokens,
                        "baseline_timestamp": now_utc(),
                    }
                    try:
                        baseline_file.parent.mkdir(parents=True, exist_ok=True)
                        tmp = baseline_file.with_suffix(".tmp")
                        tmp.write_text(json.dumps(new_baseline) + "\n")
                        tmp.rename(baseline_file)
                    except OSError:
                        pass
                    baseline_ctx = current_ctx_tokens

                stage_used = max(0, current_ctx_tokens - baseline_ctx)
                stage_tokens = fmt_tokens(stage_used)
        else:
            # Terminal — clean up baseline
            baseline_file.unlink(missing_ok=True)
    elif not token_state:
        # No active feature — clean up stale baseline
        baseline_file.unlink(missing_ok=True)

    # Read subagent state
    subagent_display = ""
    subagent_file = Path(".claude/.subagent-state")
    if subagent_file.is_file():
        try:
            subagent = json.loads(subagent_file.read_text())
            desc = subagent.get("description", "")
            if desc:
                subagent_display = f"agent: {desc}"
        except (json.JSONDecodeError, OSError):
            pass

    # Build output
    parts = []
    if stage_display:
        parts.append(f"\\033[36m{stage_display}\\033[0m")
    if subagent_display:
        parts.append(f"\\033[35m{subagent_display}\\033[0m")
    if stage_tokens:
        parts.append(f"{stage_tokens} tokens")
    if context_pct is not None:
        ctx_int = int(context_pct)
        if ctx_int >= 80:
            color = "31"  # red
        elif ctx_int >= 50:
            color = "33"  # yellow
        else:
            color = "32"  # green
        parts.append(f"\\033[{color}mctx {context_pct}%\\033[0m")

    if parts:
        print(" · ".join(parts))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
