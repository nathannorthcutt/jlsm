#!/usr/bin/env python3
"""Stage 1: Tokenize Claude Code JSONL session logs into clean event streams.

Reads raw JSONL files, filters noise (progress events, file snapshots),
extracts clean tokens with timestamps, content, and metadata. Handles
multi-session stitching for feature stories and subagent discovery.

This module knows about Claude Code's JSONL format but NOT about
vallorcine's pipeline semantics — that's the parser's job.

Usage:
    from tokenizer import tokenize_feature, tokenize_session
    stream = tokenize_feature("encrypt-memory-data", project_dir)
    stream.save("/tmp/tokens.json")
"""

import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

from model import Token, TokenStream, TokenUsage


# ---------------------------------------------------------------------------
# JSONL format guards
# ---------------------------------------------------------------------------

# Fields we expect on JSONL entries. If the first parseable entry is missing
# all of these, the format has likely changed and we should warn rather than
# produce garbage output.
_EXPECTED_FIELDS = {"type", "timestamp", "message"}

# Tracks whether we've already warned about format issues in this process.
# Prevents flooding stderr with repeated warnings on large files.
_format_warned = False


def _check_format(entry: dict, source: str) -> bool:
    """Validate that a JSONL entry has expected structure.

    Returns True if the entry looks valid, False if the format appears
    to have changed. Logs a diagnostic on first failure.
    """
    global _format_warned
    if _format_warned:
        return True  # already warned, don't spam

    # Check that at least one expected field is present
    if not _EXPECTED_FIELDS.intersection(entry.keys()):
        _format_warned = True
        print(
            f"narrative: JSONL format may have changed — entry in {source} "
            f"has none of the expected fields ({', '.join(sorted(_EXPECTED_FIELDS))}). "
            f"Found keys: {', '.join(sorted(entry.keys())[:10])}. "
            f"Report at https://github.com/telefrek/vallorcine/issues",
            file=sys.stderr,
        )
        return False

    # Check message structure if present
    msg = entry.get("message")
    if msg is not None and not isinstance(msg, dict):
        _format_warned = True
        print(
            f"narrative: JSONL format may have changed — 'message' field in {source} "
            f"is {type(msg).__name__}, expected dict. "
            f"Report at https://github.com/telefrek/vallorcine/issues",
            file=sys.stderr,
        )
        return False

    return True


# ---------------------------------------------------------------------------
# JSONL helpers
# ---------------------------------------------------------------------------

def iter_jsonl(path: str):
    """Iterate over a JSONL file, yielding parsed dicts and skipping malformed lines.

    Uses O(1) memory per line instead of materializing the entire file.
    """
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def flatten_tool_result_content(content) -> str:
    """Flatten tool result content to a plain string.

    Tool results in JSONL come in three formats: a plain string, a list
    of content blocks (each with a "text" key), or other types. This
    normalizes all three into a single string for downstream analysis.
    """
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict):
                parts.append(item.get("text", str(item)))
            else:
                parts.append(str(item))
        return "\n".join(parts)
    return str(content)


def extract_command(content: str) -> tuple[Optional[str], Optional[str]]:
    """Extract command name and args from a user message with command tags.

    Claude Code wraps slash command invocations in XML tags within the JSONL:
        <command-name>/feature</command-name>
        <command-args>encrypt-memory-data</command-args>
    Returns (name, args) or (None, None) if no tags found.
    """
    name_match = re.search(r"<command-name>([^<]+)</command-name>", content)
    args_match = re.search(r"<command-args>(.*?)</command-args>", content, re.DOTALL)
    name = name_match.group(1) if name_match else None
    args = args_match.group(1).strip().strip('"') if args_match else None
    return name, args


def extract_usage(entry: dict) -> TokenUsage:
    """Extract token usage from an assistant message."""
    usage = entry.get("message", {}).get("usage", {})
    return TokenUsage(
        input=usage.get("input_tokens", 0),
        output=usage.get("output_tokens", 0),
        cache_read=usage.get("cache_read_input_tokens", 0),
        cache_create=usage.get("cache_creation_input_tokens", 0),
    )


def _ts_diff_ms(ts1: str, ts2: str) -> int:
    """Calculate milliseconds between two ISO timestamps. Returns 0 on error."""
    try:
        t1 = datetime.fromisoformat(ts1.replace("Z", "+00:00"))
        t2 = datetime.fromisoformat(ts2.replace("Z", "+00:00"))
        return max(0, int((t2 - t1).total_seconds() * 1000))
    except (ValueError, TypeError):
        return 0


def is_bookkeeping_file(path: str) -> bool:
    """Check if a file is internal bookkeeping that should be excluded from tokens.

    Filters out status.md (pipeline stage tracking) and index files
    (.kb/CLAUDE.md, .decisions/CLAUDE.md) — these are written frequently
    but aren't part of the narrative story.
    """
    basename = os.path.basename(path)
    if basename == "status.md":
        return True
    if basename == "CLAUDE.md" and any(d in path for d in [".kb/", ".decisions/"]):
        return True
    return False


# ---------------------------------------------------------------------------
# Interesting moment detection
# ---------------------------------------------------------------------------

# Interest detection patterns — split into literal substrings (fast `in` check)
# and compiled regex (only for patterns that need alternation/wildcards).
# This avoids ~600K regex calls on large sessions where `in` is ~100x faster.

_INTEREST_LITERALS: list[tuple[str, str]] = [
    # (substring, category) — checked with `in` on lowercased text
    ("escalat", "escalation"),
    ("contract conflict", "escalation"),
    ("can't satisfy", "escalation"),
    ("cannot satisfy", "escalation"),
    ("flagged for review", "refactor_finding"),
    ("retry", "retry"),
    ("second attempt", "retry"),
    ("trying again", "retry"),
]

_INTEREST_REGEX: list[tuple[re.Pattern, str]] = [
    # (compiled pattern, category) — only patterns that truly need regex
    (re.compile(r"doesn't match.*contract"), "escalation"),
    (re.compile(r"structural (issue|concern)"), "refactor_finding"),
    (re.compile(r"security (issue|concern|finding)"), "refactor_finding"),
    (re.compile(r"pause.*human"), "refactor_finding"),
    (re.compile(r"checklist item 2[cd]"), "refactor_finding"),
    (re.compile(r"cycle [2-9]"), "retry"),
]


def detect_interest(text: str) -> tuple[bool, Optional[str]]:
    """Check if text contains interesting moment signals.

    Checks literal substrings first (fast path), then falls back to
    compiled regex patterns for those that need alternation or wildcards.
    """
    text_lower = text.lower()
    for substring, category in _INTEREST_LITERALS:
        if substring in text_lower:
            return True, category
    for pattern, category in _INTEREST_REGEX:
        if pattern.search(text_lower):
            return True, category
    return False, None


# ---------------------------------------------------------------------------
# Subagent processing
# ---------------------------------------------------------------------------

def tokenize_subagent(jsonl_path: str, meta_path: str) -> Token:
    """Process a subagent JSONL into a subagent_result token.

    Streams the file line-by-line to avoid materializing large subagent
    sessions into memory.
    """
    meta = {}
    if os.path.exists(meta_path):
        with open(meta_path) as f:
            meta = json.load(f)

    first_ts = ""
    last_ts = ""
    tokens = TokenUsage()
    files_written = []
    interesting_moments = []
    test_passed = 0
    test_failed = 0
    has_entries = False
    # Track user wait time inside subagents — the subagent may block on
    # permission prompts (e.g., directory access) for hours if the user
    # steps away. We detect assistant→user gaps the same way we do for
    # the main session.
    prev_msg_type = ""
    prev_ts = ""
    user_wait_ms = 0

    for entry in iter_jsonl(jsonl_path):
        has_entries = True
        ts = entry.get("timestamp", "")
        if ts and not first_ts:
            first_ts = ts
        if ts:
            last_ts = ts

        msg_type = entry.get("type", "")

        # Detect user wait gaps. Two patterns:
        # 1. assistant→user: vallorcine finished, user is reading/typing
        #    Any gap > 30s is user wait (normal tool results are instant).
        # 2. user→assistant: normally instant (model response), but large
        #    gaps (> 2 min) indicate the system was blocked on a permission
        #    prompt while the user was away.
        if prev_msg_type and msg_type in ("assistant", "user") and prev_ts and ts:
            gap = _ts_diff_ms(prev_ts, ts)
            if prev_msg_type == "assistant" and msg_type == "user" and gap > 30_000:
                user_wait_ms += gap
            elif prev_msg_type == "user" and msg_type == "assistant" and gap > 120_000:
                user_wait_ms += gap

        if msg_type == "assistant":
            tokens.add(extract_usage(entry))
            content_blocks = entry.get("message", {}).get("content", [])
            if not isinstance(content_blocks, list):
                if ts:
                    prev_msg_type = msg_type
                    prev_ts = ts
                continue
            for block in content_blocks:
                if block.get("type") == "text":
                    interesting, reason = detect_interest(block["text"])
                    if interesting:
                        interesting_moments.append(reason)
                elif block.get("type") == "tool_use":
                    name = block.get("name", "")
                    inp = block.get("input", {})
                    if name in ("Write", "Edit"):
                        path = inp.get("file_path", "")
                        if path and not is_bookkeeping_file(path):
                            files_written.append(os.path.basename(path))

        elif msg_type == "user":
            content = entry.get("message", {}).get("content", [])
            if isinstance(content, list):
                for block in content:
                    if block.get("type") == "tool_result":
                        result_text = flatten_tool_result_content(block.get("content", ""))
                        passed = len(re.findall(r"(?:PASS|passed|✓|✅|ok)", result_text, re.I))
                        failed = len(re.findall(r"(?:FAIL|failed|✗|❌|ERROR)", result_text, re.I))
                        test_passed += passed
                        test_failed += failed

        if ts and msg_type in ("assistant", "user"):
            prev_msg_type = msg_type
            prev_ts = ts

    if not has_entries:
        return Token(
            type="subagent_result",
            metadata={
                "agent_type": meta.get("agentType", "unknown"),
                "description": meta.get("description", ""),
                "summary": "(empty subagent session)",
            },
        )

    duration_ms = 0
    if first_ts and last_ts:
        try:
            t1 = datetime.fromisoformat(first_ts.replace("Z", "+00:00"))
            t2 = datetime.fromisoformat(last_ts.replace("Z", "+00:00"))
            duration_ms = max(0, int((t2 - t1).total_seconds() * 1000) - user_wait_ms)
        except (ValueError, TypeError):
            pass

    desc = meta.get("description", "subagent")
    summary_parts = [desc]
    if files_written:
        summary_parts.append(f"{len(files_written)} files written")
    if test_passed or test_failed:
        if test_failed:
            summary_parts.append(f"tests: {test_passed} passed, {test_failed} failed")
        else:
            summary_parts.append(f"{test_passed} tests passing")
    if interesting_moments:
        summary_parts.append(f"{len(interesting_moments)} interesting moments")

    return Token(
        type="subagent_result",
        timestamp=first_ts,
        content=" — ".join(summary_parts),
        metadata={
            "agent_type": meta.get("agentType", "unknown"),
            "description": desc,
            "summary": " — ".join(summary_parts),
            "duration_ms": duration_ms,
            "files_written": files_written,
            "interesting": bool(interesting_moments),
            "interesting_types": interesting_moments,
            "test_passed": test_passed,
            "test_failed": test_failed,
            "detail_file": jsonl_path,
            "user_wait_ms": user_wait_ms,
        },
        tokens=tokens,
    )


# ---------------------------------------------------------------------------
# Session tokenization
# ---------------------------------------------------------------------------

def tokenize_session(jsonl_path: str, subagent_dir: Optional[str] = None) -> list[Token]:
    """Tokenize a single session JSONL into clean events.

    Streams the JSONL file line-by-line to avoid materializing the entire
    file (which can be hundreds of MB for long sessions) into memory.
    Tracks last_ts during the forward pass instead of scanning backward.
    """
    session_id = Path(jsonl_path).stem
    tokens_out = []
    pending_agents = {}  # tool_use_id -> description
    meta_index = None  # lazy: description -> (jsonl_path, meta_path)
    session_start_emitted = False
    last_ts = ""

    format_checked = False
    for entry in iter_jsonl(jsonl_path):
        # Format guard: validate structure on first real entry
        if not format_checked:
            _check_format(entry, jsonl_path)
            format_checked = True

        msg_type = entry.get("type", "")
        ts = entry.get("timestamp", "")
        if ts:
            last_ts = ts

        # Emit session_start from the first entry with a timestamp
        if not session_start_emitted and ts:
            branch = entry.get("gitBranch", "")
            model = entry.get("message", {}).get("model", "")
            cli_version = entry.get("version", "")
            tokens_out.append(Token(
                type="session_start",
                timestamp=ts,
                metadata={
                    "session_id": session_id,
                    "branch": branch or "",
                    "model": model or "",
                    "cli_version": cli_version or "",
                },
            ))
            session_start_emitted = True

        # Backfill model from assistant messages (first entry often lacks it)
        if (session_start_emitted and msg_type == "assistant"
                and not tokens_out[0].metadata.get("model")):
            model = entry.get("message", {}).get("model", "")
            if model:
                tokens_out[0].metadata["model"] = model

        # Skip noise
        if msg_type in ("progress", "file-history-snapshot", "last-prompt",
                        "pr-link", "queue-operation", "system"):
            continue

        if msg_type == "user":
            msg = entry.get("message", {})
            content = msg.get("content", "")

            # Command invocation
            if isinstance(content, str) and "<command-name>" in content:
                cmd_name, cmd_args = extract_command(content)
                if cmd_name:
                    tokens_out.append(Token(
                        type="command",
                        timestamp=ts,
                        content=f"{cmd_name} {cmd_args}" if cmd_args else cmd_name,
                        metadata={"name": cmd_name, "args": cmd_args or ""},
                    ))
                continue

            # User text (not a command)
            if isinstance(content, str) and content.strip():
                if "<system-reminder>" in content:
                    continue
                if "<task-notification>" in content:
                    continue
                tokens_out.append(Token(
                    type="user_text",
                    timestamp=ts,
                    content=content[:2000],
                ))
                continue

            # Tool results
            if isinstance(content, list):
                for block in content:
                    if block.get("type") != "tool_result":
                        continue
                    tool_use_id = block.get("tool_use_id", "")
                    result_content = flatten_tool_result_content(block.get("content", ""))

                    # Subagent result
                    if tool_use_id in pending_agents and subagent_dir:
                        agent_desc = pending_agents.pop(tool_use_id)
                        # Try to find subagent JSONL by agent ID
                        agent_id_match = re.search(r"agentId[:\s]+['\"]?([a-f0-9]+)['\"]?",
                                                   result_content)
                        sa_token = None

                        if agent_id_match:
                            raw_id = agent_id_match.group(1)
                            sa_jsonl = os.path.join(subagent_dir, f"agent-{raw_id}.jsonl")
                            sa_meta = os.path.join(subagent_dir, f"agent-{raw_id}.meta.json")
                            if os.path.exists(sa_jsonl):
                                sa_token = tokenize_subagent(sa_jsonl, sa_meta)

                        # Fallback: lazy description→path index (built once)
                        if not sa_token and subagent_dir:
                            if meta_index is None:
                                meta_index = {}
                                for meta_file in Path(subagent_dir).glob("*.meta.json"):
                                    try:
                                        with open(meta_file) as mf:
                                            m = json.load(mf)
                                        meta_index[m.get("description", "")] = str(meta_file)
                                    except (json.JSONDecodeError, OSError):
                                        continue  # skip corrupt/truncated meta files
                            meta_path = meta_index.get(agent_desc)
                            if meta_path:
                                sa_id = Path(meta_path).stem.replace(".meta", "")
                                sa_jsonl = os.path.join(subagent_dir, f"{sa_id}.jsonl")
                                if os.path.exists(sa_jsonl):
                                    sa_token = tokenize_subagent(sa_jsonl, meta_path)

                        if sa_token:
                            # Override description with the parent's agent_desc
                            # which has the correct name (meta files sometimes
                            # have generic descriptions like "subagent")
                            sa_token.metadata["description"] = agent_desc
                            tokens_out.append(sa_token)
                        else:
                            tokens_out.append(Token(
                                type="subagent_result",
                                timestamp=ts,
                                content=result_content[:500],
                                metadata={
                                    "description": agent_desc,
                                    "summary": result_content[:300],
                                },
                            ))
                        continue

                    # Test results
                    if any(kw in result_content.lower() for kw in ["pass", "fail", "error"]):
                        passed = len(re.findall(r"(?:PASS|passed|✓|✅|ok)", result_content, re.I))
                        failed = len(re.findall(r"(?:FAIL|failed|✗|❌|ERROR)", result_content, re.I))
                        if passed or failed:
                            tokens_out.append(Token(
                                type="tool_result",
                                timestamp=ts,
                                content=f"{passed} passed, {failed} failed" if failed else f"{passed} passed",
                                metadata={
                                    "tool_use_id": tool_use_id,
                                    "is_test": True,
                                    "passed": passed,
                                    "failed": failed,
                                },
                            ))

        elif msg_type == "assistant":
            msg = entry.get("message", {})
            usage = extract_usage(entry)
            content_blocks = msg.get("content", [])
            if not isinstance(content_blocks, list):
                continue

            # Track whether usage has been assigned to avoid double-counting
            # when an assistant message has multiple text blocks
            usage_assigned = False

            for block in content_blocks:
                block_type = block.get("type", "")

                if block_type == "text":
                    text = block.get("text", "")
                    if not text.strip():
                        continue
                    interesting, reason = detect_interest(text)
                    tokens_out.append(Token(
                        type="agent_prose",
                        timestamp=ts,
                        content=text[:2000],
                        metadata={
                            "interesting": interesting,
                            "interest_reason": reason or "",
                        },
                        tokens=usage if not usage_assigned else TokenUsage(),
                    ))
                    usage_assigned = True

                elif block_type == "tool_use":
                    tool_name = block.get("name", "")
                    tool_input = block.get("input", {})
                    tool_id = block.get("id", "")

                    # Track Agent calls
                    if tool_name == "Agent":
                        desc = tool_input.get("description", "")
                        pending_agents[tool_id] = desc
                        tokens_out.append(Token(
                            type="subagent_start",
                            timestamp=ts,
                            metadata={
                                "description": desc,
                                "agent_type": tool_input.get("subagent_type", "general-purpose"),
                                "tool_use_id": tool_id,
                            },
                        ))
                        continue

                    # Skill invocation
                    if tool_name == "Skill":
                        skill = tool_input.get("skill", "")
                        tokens_out.append(Token(
                            type="command",
                            timestamp=ts,
                            content=f"/{skill}",
                            metadata={"name": f"/{skill}", "args": ""},
                        ))
                        continue

                    # File writes/edits (skip bookkeeping)
                    if tool_name in ("Write", "Edit"):
                        path = tool_input.get("file_path", "")
                        if path and not is_bookkeeping_file(path):
                            tokens_out.append(Token(
                                type="tool_call",
                                timestamp=ts,
                                metadata={
                                    "tool": tool_name.lower(),
                                    "target": path,
                                    "input_summary": f"{'Wrote' if tool_name == 'Write' else 'Edited'} {os.path.basename(path)}",
                                },
                            ))
                        continue

                    # Bash commands (only interesting ones)
                    if tool_name == "Bash":
                        cmd = tool_input.get("command", "")
                        desc = tool_input.get("description", "")
                        if any(kw in cmd for kw in ["git commit", "git push", "npm test",
                                                     "pytest", "go test", "cargo test",
                                                     "make test", "gh pr"]):
                            tokens_out.append(Token(
                                type="tool_call",
                                timestamp=ts,
                                metadata={
                                    "tool": "bash",
                                    "target": cmd[:120],
                                    "input_summary": desc or f"Ran: {cmd[:80]}",
                                },
                            ))
                        elif desc and any(kw in desc.lower() for kw in
                                          ["test", "commit", "push", "pr"]):
                            tokens_out.append(Token(
                                type="tool_call",
                                timestamp=ts,
                                metadata={
                                    "tool": "bash",
                                    "target": cmd[:120],
                                    "input_summary": desc,
                                },
                            ))

    # Session end — last_ts tracked during forward pass
    if last_ts:
        tokens_out.append(Token(
            type="session_end",
            timestamp=last_ts,
            metadata={"session_id": session_id, "reason": "end"},
        ))

    return tokens_out


# ---------------------------------------------------------------------------
# Multi-session stitching
# ---------------------------------------------------------------------------

def find_project_dir(project_hint: str) -> Optional[str]:
    """Find the Claude Code project directory matching a hint.

    Session logs live under ~/.claude/projects/<encoded-path>/. The hint
    is matched against directory names — e.g., "vallorcine" matches
    "-home-user-Code-vallorcine". Case-insensitive fallback if exact fails.
    """
    claude_dir = Path.home() / ".claude" / "projects"
    if not claude_dir.exists():
        return None
    for d in claude_dir.iterdir():
        if d.is_dir() and project_hint in d.name:
            return str(d)
    hint_lower = project_hint.lower()
    for d in claude_dir.iterdir():
        if d.is_dir() and hint_lower in d.name.lower():
            return str(d)
    return None


def find_sessions_for_feature(project_dir: str, slug: str) -> list[str]:
    """Find all session JSONL files that reference a feature slug.

    Line-scans each file and breaks on first match to avoid reading
    entire multi-hundred-MB session files into memory. Uses boundary-aware
    matching to avoid false positives (e.g., slug "log" matching "logging").
    """
    # Require the slug to appear with boundary context — surrounded by
    # quotes, path separators, whitespace, or line boundaries
    slug_pattern = re.compile(
        r'(?:["\'/_\s-]|^)' + re.escape(slug) + r'(?:["\'/_\s,.\-\]}]|$)'
    )
    sessions = []
    for f in sorted(Path(project_dir).glob("*.jsonl")):
        with open(f) as fh:
            for line in fh:
                if slug in line and slug_pattern.search(line):
                    sessions.append(str(f))
                    break

    def first_timestamp(path):
        with open(path) as fh:
            for line in fh:
                try:
                    d = json.loads(line)
                    ts = d.get("timestamp", "")
                    if ts:
                        return ts
                except json.JSONDecodeError:
                    continue
        return ""

    sessions.sort(key=first_timestamp)
    return sessions


def tokenize_feature(slug: str, project_dir: str) -> TokenStream:
    """Tokenize all sessions for a feature into a single merged token stream.

    Finds every session JSONL that mentions the slug, orders them by
    timestamp, and concatenates their tokens. Each session contributes
    a session_start/session_end pair, so the parser can detect crash
    boundaries where one session ends and another begins.
    """
    session_paths = find_sessions_for_feature(project_dir, slug)
    if not session_paths:
        return TokenStream()

    stream = TokenStream(project=project_dir)

    for path in session_paths:
        session_id = Path(path).stem
        stream.sessions.append(session_id)

        subagent_dir = os.path.join(os.path.dirname(path), session_id, "subagents")
        if not os.path.isdir(subagent_dir):
            subagent_dir = None

        try:
            session_tokens = tokenize_session(path, subagent_dir)
            stream.tokens.extend(session_tokens)
        except Exception as e:
            # Guard: if a session fails to tokenize (format change, corrupt
            # file, etc.), log the error and continue with other sessions.
            print(
                f"narrative: failed to tokenize session {session_id}: {e}. "
                f"Skipping this session. "
                f"Report at https://github.com/telefrek/vallorcine/issues",
                file=sys.stderr,
            )

    return stream


def tokenize_single_session(session_id: str, project_dir: str) -> TokenStream:
    """Tokenize a single session by ID.

    Use this for non-feature stories (curation, research, architect)
    where the story is contained in one session and slug-based multi-session
    stitching isn't needed.
    """
    path = os.path.join(project_dir, f"{session_id}.jsonl")
    if not os.path.exists(path):
        return TokenStream()

    subagent_dir = os.path.join(project_dir, session_id, "subagents")
    if not os.path.isdir(subagent_dir):
        subagent_dir = None

    stream = TokenStream(
        sessions=[session_id],
        project=project_dir,
        tokens=tokenize_session(path, subagent_dir),
    )
    return stream
