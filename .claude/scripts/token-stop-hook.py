#!/usr/bin/env python3
"""vallorcine token tracking Stop hook — Python implementation.

Fires on every Claude response. Detects pipeline stage transitions and logs
token usage. No jq dependency — uses stdlib json for all parsing.

State file: .claude/.token-state (JSON)

Stdlib only — no pip dependencies.
"""

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def fmt_tokens(n: int) -> str:
    """Format token count: 1234567 → '1.2M'."""
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
        # Legacy shell variable format
        result = {}
        for line in text.splitlines():
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                key, _, val = line.partition("=")
                val = val.strip("'\"")
                if val.startswith("$'"):
                    val = val[2:-1] if val.endswith("'") else val[2:]
                result[key.strip()] = val
        return result
    except (json.JSONDecodeError, OSError):
        return {}


def write_state(path: Path, data: dict) -> None:
    """Write JSON state file atomically."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data) + "\n")
        tmp.rename(path)
    except OSError:
        pass


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
                    break
    except OSError:
        pass
    return stage, substage


def find_transcript() -> str:
    """Find the most recent session transcript JSONL."""
    project_dir = os.getcwd()
    project_id = project_dir.lstrip("/").replace("/", "-")
    session_dir = Path.home() / ".claude" / "projects" / f"-{project_id}"
    if not session_dir.is_dir():
        return ""
    jsonl_files = list(session_dir.glob("*.jsonl"))
    if not jsonl_files:
        return ""
    return str(max(jsonl_files, key=lambda p: p.stat().st_mtime))


def sum_usage(transcript: str, start_line: int) -> tuple[dict, int]:
    """Sum token usage from transcript JSONL starting at line start_line.

    Returns (usage_dict, total_line_count) to avoid re-reading the file.
    """
    result = {"input": 0, "output": 0, "cache_create": 0, "cache_read": 0, "messages": 0}
    total_lines = 0
    try:
        with open(transcript) as f:
            for i, line in enumerate(f, 1):
                total_lines = i
                if i < start_line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if entry.get("type") != "assistant":
                    continue
                msg = entry.get("message", {})
                if msg.get("stop_reason") is None:
                    continue
                usage = msg.get("usage", {})
                if usage.get("input_tokens", 0) <= 0:
                    continue
                result["input"] += usage.get("input_tokens", 0)
                result["output"] += usage.get("output_tokens", 0)
                result["cache_create"] += usage.get("cache_creation_input_tokens", 0)
                result["cache_read"] += usage.get("cache_read_input_tokens", 0)
                result["messages"] += 1
    except OSError:
        pass
    return result, total_lines


def update_status_md_actual_tokens(status_path: str, stage_cap: str, actual_str: str):
    """Update the Actual Tokens column in the status.md Stage Completion table."""
    try:
        lines = Path(status_path).read_text().splitlines()
        updated = []
        for line in lines:
            if "|" in line:
                parts = line.split("|")
                if len(parts) >= 7:
                    field2 = parts[1].strip()
                    if field2 == stage_cap:
                        parts[5] = f" {actual_str} "
                        line = "|".join(parts)
            updated.append(line)
        target = Path(status_path)
        tmp = target.with_suffix(".tmp")
        tmp.write_text("\n".join(updated) + "\n")
        tmp.rename(target)
    except OSError:
        pass


def main():
    # Consume stdin (required by Stop hooks)
    sys.stdin.read()

    state_file_path = Path(".claude/.token-state")

    # Fast bail
    if not state_file_path.is_file() and not Path(".feature").is_dir():
        return

    # Tracking active: check for stage transition
    if state_file_path.is_file():
        state = read_json_or_shell(state_file_path)
        if not state:
            state_file_path.unlink(missing_ok=True)
            return

        feature_dir = state.get("feature_dir", "")
        cached_stage = state.get("cached_stage", "")
        transcript = state.get("transcript", "")
        try:
            start_line = int(state.get("start_line", 1))
        except (ValueError, TypeError):
            start_line = 1
        timestamp = state.get("timestamp", "")

        status_path = f"{feature_dir}/status.md"
        if not feature_dir or not os.path.isfile(status_path):
            state_file_path.unlink(missing_ok=True)
            return

        current_stage, substage = read_stage_from_status(status_path)

        # Terminal state detection
        is_terminal = f"{current_stage}/{substage}" in ("pr/created", "pr/complete")

        # Same stage check
        if not current_stage or current_stage == cached_stage:
            if is_terminal:
                state_file_path.unlink(missing_ok=True)
            return

        # Stage transition detected
        # Verify transcript is from current session
        current_transcript = find_transcript()
        if current_transcript != transcript or not os.path.isfile(transcript):
            transcript = current_transcript
            start_line = 1

        if transcript and os.path.isfile(transcript):
            usage, total_lines = sum_usage(transcript, start_line)

            # Append to token log
            log_file = f"{feature_dir}/token-log.md"
            try:
                if not os.path.isfile(log_file):
                    with open(log_file, "w") as f:
                        f.write("# Token Usage Log\n\n")
                        f.write("| Phase | Messages | Input | Output | Cache Create | Cache Read | Started | Ended |\n")
                        f.write("|-------|----------|-------|--------|--------------|------------|---------|-------|\n")

                with open(log_file, "a") as f:
                    f.write(
                        f"| {cached_stage} | {usage['messages']} | {usage['input']} | {usage['output']} "
                        f"| {usage['cache_create']} | {usage['cache_read']} "
                        f"| {timestamp or 'unknown'} | {now_utc()} |\n"
                    )
            except OSError:
                pass

            # Update Actual Tokens in status.md
            actual_str = f"{fmt_tokens(usage['input'])} in / {fmt_tokens(usage['output'])} out"
            stage_cap = cached_stage[0].upper() + cached_stage[1:] if cached_stage else ""
            update_status_md_actual_tokens(status_path, stage_cap, actual_str)

            # Terminal state — clean up
            if is_terminal:
                state_file_path.unlink(missing_ok=True)
                return

            # Update state for new stage (line count from sum_usage, no re-read)
            write_state(state_file_path, {
                "feature_dir": feature_dir,
                "cached_stage": current_stage,
                "transcript": transcript,
                "start_line": total_lines + 1,
                "timestamp": now_utc(),
            })

        return

    # Cold start: look for active features
    feature_dir_path = Path(".feature")
    if not feature_dir_path.is_dir():
        return

    for status_file in feature_dir_path.glob("*/status.md"):
        stage, substage = read_stage_from_status(str(status_file))
        if not stage:
            continue
        if f"{stage}/{substage}" in ("pr/complete", "pr/created"):
            continue

        # Found active feature
        feature_dir = str(status_file.parent)
        transcript = find_transcript()
        if not transcript:
            return

        try:
            with open(transcript) as f:
                current_line = sum(1 for _ in f)
        except OSError:
            current_line = 1

        write_state(state_file_path, {
            "feature_dir": feature_dir,
            "cached_stage": stage,
            "transcript": transcript,
            "start_line": current_line + 1,
            "timestamp": now_utc(),
        })
        return


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
