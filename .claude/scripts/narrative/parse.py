#!/usr/bin/env python3
"""Stage 2: Parse token streams into pipeline-aware AST.

Reads a TokenStream (from tokenize.py) and produces a Story AST with
typed nodes representing vallorcine pipeline structure.

This module knows about vallorcine's pipeline semantics — command names,
stage progression, agent roles, file conventions. It does NOT know about
rendering or presentation.

Classification is intentionally loose: unrecognized token sequences
fall back to Prose nodes rather than being dropped. New node types
are added as we encounter new patterns.

Usage:
    from parse import parse_story, enrich_story_with_audit, parse_audit_story
    from model import TokenStream
    stream = TokenStream.load("/tmp/tokens.json")
    story = parse_story(stream, feature_slug="encrypt-memory-data")
    enrich_story_with_audit(story, ".feature/encrypt-memory-data")
    story.save("/tmp/story-ast.json")

    # Standalone audit story (no JSONL needed):
    audit_story = parse_audit_story(".feature/encrypt-memory-data", "encrypt-memory-data")
"""

import os
import re
from datetime import datetime
from typing import Optional

from model import Token, TokenStream, TokenUsage, Node, NodeType, Story


# ---------------------------------------------------------------------------
# Stage mapping
# ---------------------------------------------------------------------------

# Keywords that identify a TDD-style subagent (vs. generic action subagents)
TDD_KEYWORDS = ("test-implement", "tdd", "wu-")

# Maps slash command names to pipeline stage identifiers used in Phase nodes.
# Commands not in this map fall back to the command name with "/" stripped.
COMMAND_TO_STAGE = {
    "/feature": "scoping",
    "/feature-quick": "scoping",
    "/feature-domains": "domains",
    "/feature-plan": "planning",
    "/feature-test": "testing",
    "/feature-implement": "implementation",
    "/feature-refactor": "refactor",
    "/feature-coordinate": "coordination",
    "/feature-pr": "pr",
    "/feature-retro": "retro",
    "/feature-complete": "complete",
    "/feature-resume": "resume",
    "/curate": "curation",
    "/research": "research",
    "/architect": "architect",
    "/kb": "knowledge",
    "/decisions": "decisions",
    "/audit": "audit",
}


def _is_tdd_description(desc: str) -> bool:
    """Check if a subagent description indicates a TDD work unit."""
    desc_lower = desc.lower()
    return any(kw in desc_lower for kw in TDD_KEYWORDS)


def _has_slug(nodes: list[Node], slug: str) -> bool:
    """Recursively check if any node references the given feature slug.

    Checks node content and string values in data dicts directly,
    avoiding str(data) which allocates a temporary string representation
    of the entire dict for every node.
    """
    for n in nodes:
        if slug in (n.content or ""):
            return True
        for v in n.data.values():
            if isinstance(v, str) and slug in v:
                return True
        if n.children and _has_slug(n.children, slug):
            return True
    return False


def detect_story_type(commands: list[str]) -> str:
    """Determine story type from the first recognized command in the stream.

    Priority: /feature* → "feature", /curate → "curation",
    /research → "research", /architect → "architect".
    Defaults to "feature" if no recognized commands found.
    """
    for cmd in commands:
        if cmd.startswith("/feature"):
            return "feature"
        if cmd == "/audit":
            return "audit"
        if cmd == "/curate":
            return "curation"
        if cmd == "/research":
            return "research"
        if cmd == "/architect":
            return "architect"
    return "feature"


# ---------------------------------------------------------------------------
# Duration helpers
# ---------------------------------------------------------------------------

def parse_ts(ts: str) -> Optional[datetime]:
    """Parse an ISO 8601 timestamp, normalizing 'Z' suffix to UTC offset."""
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None


def duration_between(ts1: str, ts2: str) -> int:
    """Calculate duration in ms between two ISO timestamps. Returns 0 if either is unparseable."""
    t1 = parse_ts(ts1)
    t2 = parse_ts(ts2)
    if t1 and t2:
        return max(0, int((t2 - t1).total_seconds() * 1000))
    return 0


# Token types produced by the assistant (vallorcine is working)
_ASSISTANT_TYPES = frozenset({"agent_prose", "tool_call", "subagent_start", "subagent_result"})
# Token types produced by the user (vallorcine is waiting)
_USER_TYPES = frozenset({"user_text", "command"})


def _compute_idle_time(tokens: list["Token"]) -> int:
    """Compute total idle time in a token list.

    Idle time has four components:
    1. User wait time — gaps where vallorcine finished speaking and waited
       for the user to respond. Detected as an assistant-side token followed
       by a user-initiated token.
    2. Crash gaps — session_end followed by session_start, representing time
       between a crash and the user resuming.
    3. Crashed subagent gaps — a subagent_start with no matching result before
       a session_end means the subagent was running when the session crashed.
       The gap between the last real work token and the session_end is idle.
    4. Subagent internal idle — user wait time that occurred inside subagents
       (e.g., user sleeping while a permission prompt blocks a subagent).
       Propagated via the subagent_result token's user_wait_ms metadata.

    Returns total idle milliseconds to subtract from raw wall-clock duration.
    """
    idle_ms = 0
    prev_type = ""
    prev_ts = ""
    # Track unmatched subagent starts to detect crashed subagents.
    # Keyed by tool_use_id (not description) to avoid collisions when
    # multiple subagents share the same description.
    pending_subagents: dict[str, str] = {}  # tool_use_id -> subagent_start timestamp

    for t in tokens:
        if not t.timestamp:
            continue

        # Crash gap: session_end → session_start
        if prev_type == "session_end" and t.type == "session_start":
            idle_ms += duration_between(prev_ts, t.timestamp)

        # User wait: assistant token → user-initiated token
        elif prev_type in _ASSISTANT_TYPES and t.type in _USER_TYPES:
            idle_ms += duration_between(prev_ts, t.timestamp)

        # Track subagent lifecycle
        if t.type == "subagent_start":
            sa_id = t.metadata.get("tool_use_id", t.metadata.get("description", ""))
            # Use the subagent_start's own timestamp (not prev_ts) to avoid
            # double-counting any user wait gap before the launch
            pending_subagents[sa_id] = t.timestamp
        elif t.type == "subagent_result":
            # Match by description since results may not have tool_use_id
            desc = t.metadata.get("description", "")
            # Remove the first pending subagent with matching description
            for sa_id, sa_ts in list(pending_subagents.items()):
                if desc in sa_id or sa_id in desc:
                    pending_subagents.pop(sa_id)
                    break
            else:
                # No match by ID — try popping any single pending entry
                # (handles cases where description was overridden)
                if len(pending_subagents) == 1:
                    pending_subagents.popitem()
            # Add any user wait time that occurred inside the subagent
            idle_ms += t.metadata.get("user_wait_ms", 0)

        # Crashed subagent: session_end while subagents are still pending
        if t.type == "session_end" and pending_subagents:
            for sa_id, launch_ts in pending_subagents.items():
                idle_ms += duration_between(launch_ts, t.timestamp)
            pending_subagents.clear()

        prev_type = t.type
        prev_ts = t.timestamp

    return idle_ms


# ---------------------------------------------------------------------------
# Token stream segmentation
# ---------------------------------------------------------------------------

def segment_by_command(tokens: list[Token]) -> list[tuple[Token, list[Token]]]:
    """Split token stream into segments, each starting with a command token.

    Returns list of (command_token, following_tokens) tuples.
    Tokens before the first command are grouped under a synthetic 'preamble'.
    """
    segments = []
    current_cmd = Token(type="command", metadata={"name": "_preamble", "args": ""})
    current_tokens = []

    for token in tokens:
        if token.type == "command":
            if current_tokens or current_cmd.metadata.get("name") != "_preamble":
                segments.append((current_cmd, current_tokens))
            current_cmd = token
            current_tokens = []
        else:
            current_tokens.append(token)

    if current_tokens:
        segments.append((current_cmd, current_tokens))

    return segments


# ---------------------------------------------------------------------------
# Pattern recognizers
# ---------------------------------------------------------------------------

def recognize_conversation(tokens: list[Token], start: int) -> tuple[Optional[Node], int]:
    """Recognize a conversation pattern: alternating agent_prose and user_text.

    Returns (node, end_index) or (None, start) if no conversation found.
    """
    exchanges = []
    i = start

    while i < len(tokens):
        # Look for agent_prose followed by user_text
        if tokens[i].type == "agent_prose":
            question = tokens[i].content
            # Check if next meaningful token is user response
            j = i + 1
            # Skip intervening tool calls/results
            while j < len(tokens) and tokens[j].type in ("tool_call", "tool_result"):
                j += 1
            if j < len(tokens) and tokens[j].type == "user_text":
                exchanges.append({
                    "question": question[:500],
                    "answer": tokens[j].content[:500],
                    "timestamp": tokens[i].timestamp,
                })
                i = j + 1
                continue
        break

    if len(exchanges) >= 2:
        node = Node(
            node_type=NodeType.CONVERSATION,
            data={"exchange_count": len(exchanges)},
            children=[
                Node(
                    node_type=NodeType.EXCHANGE,
                    data=ex,
                    content=f"Q: {ex['question'][:100]}\nA: {ex['answer'][:100]}",
                )
                for ex in exchanges
            ],
        )
        node.duration_ms = duration_between(
            exchanges[0].get("timestamp", ""),
            exchanges[-1].get("timestamp", ""),
        )
        return node, i

    return None, start


def recognize_research(tokens: list[Token], start: int) -> tuple[Optional[Node], int]:
    """Recognize a research commission: /research command → prose → KB writes."""
    if start >= len(tokens):
        return None, start

    i = start
    topic = ""
    subject = ""
    kb_path = ""
    findings = []

    while i < len(tokens):
        t = tokens[i]
        if t.type == "agent_prose":
            # Look for research topic indicators
            content = t.content
            if "RESEARCH AGENT" in content or "Pre-flight check" in content:
                topic_match = re.search(r"Topic:\s*(\S+)", content)
                subject_match = re.search(r"Subject:\s*(\S+)", content)
                if topic_match:
                    topic = topic_match.group(1)
                if subject_match:
                    subject = subject_match.group(1)
            if "RESEARCH AGENT complete" in content:
                findings.append(content[:500])
                i += 1
                break
        elif t.type == "tool_call":
            target = t.metadata.get("target", "")
            if ".kb/" in target and t.metadata.get("tool") == "write":
                kb_path = target
        i += 1

    if kb_path or topic:
        return Node(
            node_type=NodeType.RESEARCH,
            data={
                "topic": topic,
                "subject": subject,
                "kb_path": os.path.basename(kb_path) if kb_path else "",
                "findings_summary": findings[0] if findings else "",
            },
            duration_ms=duration_between(
                tokens[start].timestamp if start < len(tokens) else "",
                tokens[min(i-1, len(tokens)-1)].timestamp,
            ),
        ), i

    return None, start


def recognize_architect(tokens: list[Token], start: int) -> tuple[Optional[Node], int]:
    """Recognize an architect session: constraints → evaluation → deliberation → ADR."""
    i = start
    question = ""
    decision = ""
    adr_path = ""
    candidates = []
    has_deliberation = False

    while i < len(tokens):
        t = tokens[i]
        if t.type == "agent_prose":
            content = t.content
            if "ARCHITECT AGENT" in content and "complete" not in content:
                question_match = re.search(r"RECOMMENDATION.*?—\s*(.+?)$", content, re.M)
                if question_match:
                    question = question_match.group(1)
            if "RECOMMENDATION" in content:
                # Extract recommended approach
                rec_match = re.search(r"Recommended approach:\s*(.+?)$", content, re.M)
                rej_match = re.search(r"Rejected:\s*(.+?)$", content, re.M)
                if rec_match:
                    decision = rec_match.group(1).strip()
                if rej_match:
                    candidates = [c.strip() for c in rej_match.group(1).split(",")]
                has_deliberation = True
            if "ARCHITECT AGENT complete" in content:
                i += 1
                break
        elif t.type == "tool_call":
            target = t.metadata.get("target", "")
            if "adr.md" in target:
                adr_path = target
        elif t.type == "user_text":
            # User confirmation or pushback during deliberation
            if has_deliberation:
                pass  # captured in the conversation flow
        i += 1

    if has_deliberation or adr_path:
        return Node(
            node_type=NodeType.ARCHITECT,
            data={
                "question": question,
                "decision": decision,
                "rejected": candidates,
                "adr_path": os.path.basename(adr_path) if adr_path else "",
            },
            interesting=bool(candidates),
            duration_ms=duration_between(
                tokens[start].timestamp,
                tokens[min(i-1, len(tokens)-1)].timestamp,
            ),
        ), i

    return None, start


def recognize_subagent(tokens: list[Token], start: int) -> tuple[Optional[Node], int]:
    """Recognize a subagent start/result pair and classify as TDD_CYCLE or ACTION_GROUP."""
    if start >= len(tokens) or tokens[start].type != "subagent_start":
        return None, start

    desc = tokens[start].metadata.get("description", "")
    node_type = NodeType.TDD_CYCLE if _is_tdd_description(desc) else NodeType.ACTION_GROUP
    # Look for the matching subagent_result
    i = start + 1
    while i < len(tokens):
        t = tokens[i]
        if t.type == "subagent_result" and t.metadata.get("description", "") == desc:
            meta = t.metadata
            return Node(
                node_type=node_type,
                data={
                    "unit_name": desc,
                    "duration_ms": meta.get("duration_ms", 0),
                    "files_written": meta.get("files_written", []),
                    "test_passed": meta.get("test_passed", 0),
                    "test_failed": meta.get("test_failed", 0),
                    "interesting_types": meta.get("interesting_types", []),
                    "summary": meta.get("summary", ""),
                },
                interesting=meta.get("interesting", False),
                duration_ms=meta.get("duration_ms", 0),
                tokens=t.tokens,
            ), i + 1
        # Don't cross command boundaries
        if t.type == "command":
            break
        i += 1

    return None, start


# ---------------------------------------------------------------------------
# Phase builder
# ---------------------------------------------------------------------------

def build_phase(cmd_token: Token, tokens: list[Token],
                feature_slug: Optional[str] = None) -> Optional[Node]:
    """Build a Phase node from a command and its following tokens.

    Creates a PHASE node containing the command's stage, title, duration,
    aggregated token usage, and parsed children. Duration excludes crash
    gaps (session_end → session_start pairs within the phase).

    Feature filtering: when feature_slug is provided, rejects phases whose
    command args reference a different feature. Phases with no args pass
    through (filtered later by the in_feature state machine in parse_story).

    Returns None if the phase should be filtered out (wrong feature).
    """
    cmd_name = cmd_token.metadata.get("name", "")
    cmd_args = cmd_token.metadata.get("args", "")
    stage = COMMAND_TO_STAGE.get(cmd_name, cmd_name.lstrip("/"))

    # Feature filtering
    if feature_slug:
        # Normalize: "engine clustering" should match slug "engine-clustering"
        slug_variants = [feature_slug, feature_slug.replace("-", " ")]
        args_match = cmd_args and any(v in cmd_args for v in slug_variants)
        if cmd_args and args_match:
            pass  # explicit match
        elif cmd_args and not args_match:
            # Args present but for a different feature — reject
            return None
        elif cmd_name in ("/feature", "/feature-quick") and not cmd_args:
            # Bare /feature — check if the slug appears in agent prose
            # or tool call targets within this phase's tokens
            slug_found = False
            for t in tokens:
                content = t.content or ""
                target = t.metadata.get("target", "")
                if (f"· {feature_slug}" in content
                        or f"— {feature_slug}" in content
                        or f".feature/{feature_slug}" in content
                        or f".feature/{feature_slug}" in target):
                    slug_found = True
                    break
            if not slug_found:
                return None
        elif cmd_name.startswith("/feature-"):
            pass  # pipeline continuation (no args), keep
        elif cmd_name in ("/research", "/architect"):
            pass  # these are invoked during domains, keep
        else:
            return None

    title = f"{stage.title()}"
    if cmd_args:
        title += f" — {cmd_args}"

    phase = Node(
        node_type=NodeType.PHASE,
        data={"stage": stage, "command": cmd_name, "title": title},
    )

    # Set timestamps — subtract crash gaps and user wait time from duration.
    # "Duration" should reflect only time vallorcine was actively working,
    # not time the user spent reading, thinking, or typing.
    if cmd_token.timestamp:
        phase.data["started"] = cmd_token.timestamp
    if tokens:
        last_ts = ""
        for t in reversed(tokens):
            if t.timestamp:
                last_ts = t.timestamp
                break
        if last_ts and cmd_token.timestamp:
            raw_duration = duration_between(cmd_token.timestamp, last_ts)
            # Include the command token so the gap between command invocation
            # and first assistant response is counted as idle (user typed it)
            all_tokens = [cmd_token] + tokens
            idle_ms = _compute_idle_time(all_tokens)
            phase.duration_ms = max(0, raw_duration - idle_ms)

    # Aggregate tokens
    for t in tokens:
        phase.tokens.add(t.tokens)

    # Parse children from token sequence
    children = parse_phase_children(tokens)
    phase.children = children

    return phase


def parse_phase_children(tokens: list[Token]) -> list[Node]:
    """Parse a sequence of tokens within a phase into child AST nodes.

    Walks the token list with pattern recognizers in priority order:
    1. Session boundaries → SESSION_BREAK nodes
    2. Subagent start/result pairs → TDD_CYCLE or ACTION_GROUP nodes
    3. Orphaned subagent results → TDD_CYCLE or ACTION_GROUP
    4. Conversations → CONVERSATION nodes (2+ exchange pairs)
    5. Stage transitions → STAGE_TRANSITION nodes
    6. Test results → TEST_RESULT nodes (batched)
    7. Tool calls → ACTION_GROUP nodes (batched)
    8. Agent prose → ESCALATION or PROSE nodes
    9. User text → EXCHANGE nodes

    Unrecognized tokens are silently skipped (no data loss — the token
    stream file preserves everything).
    """
    children = []
    i = 0

    while i < len(tokens):
        t = tokens[i]

        # Session boundaries
        if t.type == "session_end":
            # Look ahead for session_start to create a break
            if i + 1 < len(tokens) and tokens[i + 1].type == "session_start":
                gap = duration_between(t.timestamp, tokens[i + 1].timestamp)
                children.append(Node(
                    node_type=NodeType.SESSION_BREAK,
                    data={
                        "reason": "crash",
                        "gap_duration_ms": gap,
                    },
                    duration_ms=gap,
                ))
                i += 2  # skip both session_end and session_start
                continue
            i += 1
            continue

        if t.type == "session_start":
            i += 1
            continue

        # Try pattern recognizers in priority order
        # Subagent start/result pairs (TDD or generic)
        if t.type == "subagent_start":
            node, new_i = recognize_subagent(tokens, i)
            if node:
                children.append(node)
                i = new_i
                continue

        # Orphaned subagent results (from background agents without a start token)
        if t.type == "subagent_result":
            meta = t.metadata
            desc = meta.get("description", "")
            children.append(Node(
                node_type=NodeType.TDD_CYCLE if _is_tdd_description(desc) else NodeType.ACTION_GROUP,
                data={
                    "unit_name": desc,
                    "summary": meta.get("summary", ""),
                    "duration_ms": meta.get("duration_ms", 0),
                    "files_written": meta.get("files_written", []),
                    "test_passed": meta.get("test_passed", 0),
                    "test_failed": meta.get("test_failed", 0),
                    "interesting_types": meta.get("interesting_types", []),
                },
                interesting=meta.get("interesting", False),
                duration_ms=meta.get("duration_ms", 0),
                tokens=t.tokens,
            ))
            i += 1
            continue

        # Conversations (agent asks, user answers, repeat)
        if t.type == "agent_prose":
            conv_node, new_i = recognize_conversation(tokens, i)
            if conv_node:
                children.append(conv_node)
                i = new_i
                continue

        # Stage transitions (command invocations via Skill tool)
        if t.type == "command":
            cmd_name = t.metadata.get("name", "")
            children.append(Node(
                node_type=NodeType.STAGE_TRANSITION,
                data={"command": cmd_name, "args": t.metadata.get("args", "")},
            ))
            i += 1
            continue

        # Test results
        if t.type == "tool_result" and t.metadata.get("is_test"):
            # Batch consecutive test results
            results = [t]
            j = i + 1
            while j < len(tokens) and tokens[j].type == "tool_result" and tokens[j].metadata.get("is_test"):
                results.append(tokens[j])
                j += 1

            total_passed = sum(r.metadata.get("passed", 0) for r in results)
            total_failed = sum(r.metadata.get("failed", 0) for r in results)

            children.append(Node(
                node_type=NodeType.TEST_RESULT,
                data={
                    "passed": total_passed,
                    "failed": total_failed,
                    "run_count": len(results),
                },
                interesting=total_failed > 0,
            ))
            i = j
            continue

        # Tool calls — batch consecutive ones
        if t.type == "tool_call":
            actions = [t]
            j = i + 1
            while j < len(tokens) and tokens[j].type == "tool_call":
                actions.append(tokens[j])
                j += 1

            children.append(Node(
                node_type=NodeType.ACTION_GROUP,
                data={
                    "actions": [
                        {
                            "verb": a.metadata.get("tool", ""),
                            "target": os.path.basename(a.metadata.get("target", "")),
                            "summary": a.metadata.get("input_summary", ""),
                        }
                        for a in actions
                    ],
                },
            ))
            i = j
            continue

        # Agent prose (interesting or routine)
        if t.type == "agent_prose":
            interesting = t.metadata.get("interesting", False)
            reason = t.metadata.get("interest_reason", "")

            if interesting and reason == "escalation":
                children.append(Node(
                    node_type=NodeType.ESCALATION,
                    content=t.content[:1000],
                    interesting=True,
                ))
            else:
                children.append(Node(
                    node_type=NodeType.PROSE,
                    content=t.content[:2000],
                    interesting=interesting,
                    tokens=t.tokens,
                ))
            i += 1
            continue

        # User text outside of a conversation pattern
        if t.type == "user_text":
            children.append(Node(
                node_type=NodeType.EXCHANGE,
                data={"answer": t.content[:500]},
                content=t.content[:500],
            ))
            i += 1
            continue

        # Anything else — skip
        i += 1

    return children


# ---------------------------------------------------------------------------
# Story assembly
# ---------------------------------------------------------------------------

def parse_story(stream: TokenStream, feature_slug: Optional[str] = None,
                story_type: Optional[str] = None) -> Story:
    """Parse a token stream into a Story AST.

    Pipeline:
    1. Segment token stream by command boundaries
    2. Auto-detect story type from commands (feature/curation/research/architect)
    3. Build Phase nodes from each segment (with feature filtering)
    4. For feature stories, run a second-pass in_feature state machine to
       remove pipeline continuation phases that belong to a different feature
       (handles crash boundaries where a new feature starts in the same session)
    5. Aggregate metadata, duration, and token usage from all phases

    Args:
        stream: Token stream from the tokenizer (stage 1 output).
        feature_slug: For feature stories, the slug to filter phases by.
            Phases with args for a different slug are excluded.
        story_type: Override auto-detection. One of: feature, curation,
            research, architect.
    """

    # Segment by commands
    segments = segment_by_command(stream.tokens)

    # Detect story type if not provided
    if not story_type:
        commands = [seg[0].metadata.get("name", "") for seg in segments]
        story_type = detect_story_type(commands)

    # Build phases
    phases = []
    for cmd_token, seg_tokens in segments:
        if cmd_token.metadata.get("name") == "_preamble":
            continue  # skip pre-command tokens
        phase = build_phase(cmd_token, seg_tokens, feature_slug)
        if phase:
            phases.append(phase)

    # Filter: for feature stories, remove phases that don't belong.
    # build_phase already rejects commands with args for a different feature.
    # This second pass handles the in_feature state machine — pipeline
    # continuation phases (no args) are kept only while in_feature is True,
    # and session breaks inside phases reset in_feature so a different
    # feature after a crash doesn't bleed through.
    if feature_slug and story_type == "feature":
        filtered = []
        in_feature = False
        for phase in phases:
            stage = phase.data.get("stage", "")
            title = phase.data.get("title", "")

            if feature_slug in title:
                # Explicit slug match in command args
                in_feature = True
            elif stage == "scoping":
                # Scoping without explicit slug — check all descendants
                if _has_slug(phase.children, feature_slug):
                    in_feature = True
                else:
                    in_feature = False
                    continue
            elif stage == "resume":
                # Resume without slug in title — different feature resumed
                in_feature = False
                continue
            elif stage in ("research", "architect", "knowledge", "decisions", "audit"):
                # These are invoked mid-pipeline, keep if we're in the feature
                pass
            elif in_feature and stage in ("domains", "planning", "testing",
                                          "implementation", "refactor",
                                          "coordination", "pr",
                                          "retro", "complete"):
                # Pipeline continuation (retro/complete handled below)
                pass
            else:
                in_feature = False
                continue

            # Terminal stages — include but reset in_feature so subsequent
            # commands aren't swept in. Checked after slug match so that
            # `/feature-complete slug` still gets included.
            if in_feature and stage in ("retro", "complete"):
                filtered.append(phase)
                in_feature = False
                continue

            if in_feature:
                filtered.append(phase)
        phases = filtered

    # Extract metadata
    story = Story(
        story_type=story_type,
        title=feature_slug or "",
        sessions=stream.sessions,
        project=stream.project,
    )

    # Fill metadata from session_start tokens
    for t in stream.tokens:
        if t.type == "session_start":
            if not story.branch:
                story.branch = t.metadata.get("branch", "")
            if not story.model:
                story.model = t.metadata.get("model", "")
            if not story.cli_version:
                story.cli_version = t.metadata.get("cli_version", "")
            if not story.started:
                story.started = t.timestamp

    # Aggregate
    story.phases = phases
    story.duration_ms = sum(p.duration_ms for p in phases)
    for p in phases:
        story.tokens.add(p.tokens)

    # Filter sessions to only those that contributed phases.
    # Build a set of session IDs that contain phase timestamps.
    if phases:
        phase_timestamps = {p.data.get("started", "") for p in phases if p.data.get("started")}
        # Map each timestamp to its session by scanning session boundaries
        active_sessions = set()
        current_session_id = ""
        for t in stream.tokens:
            if t.type == "session_start":
                current_session_id = t.metadata.get("session_id", "")
            if t.timestamp in phase_timestamps and current_session_id:
                active_sessions.add(current_session_id)
        if active_sessions:
            story.sessions = [s for s in story.sessions if s in active_sessions]

    return story


# ---------------------------------------------------------------------------
# Audit pipeline parsing
# ---------------------------------------------------------------------------

# Lens abbreviation → full lens name mapping for finding IDs like F-R1.dt.1.1
_LENS_ABBREVS = {
    "dt": "data_transformation",
    "ss": "shared_state",
    "ct": "contracts",
    "lc": "lifecycle",
    "rs": "resource",
    "cc": "concurrency",
    "er": "error_handling",
    "bd": "boundary",
}


def _parse_lens_from_finding_id(finding_id: str) -> str:
    """Extract the lens name from a finding ID like F-R1.dt.1.1.

    The second dot-separated component is the lens abbreviation.
    Returns the full lens name or the raw abbreviation if unknown.
    """
    parts = finding_id.split(".")
    if len(parts) >= 2:
        abbrev = parts[1]
        return _LENS_ABBREVS.get(abbrev, abbrev)
    return ""


def parse_prove_fix_files(run_dir: str) -> dict:
    """Parse all prove-fix-*.md files in a run directory.

    Returns a dict mapping finding_id → {
        verdict, test_method, test_class, fix_description,
        impossibility_proof, phase0_result
    }.
    """
    results = {}
    try:
        entries = os.listdir(run_dir)
    except OSError:
        return results

    for fname in sorted(entries):
        if not fname.startswith("prove-fix-") or not fname.endswith(".md"):
            continue
        fpath = os.path.join(run_dir, fname)
        try:
            with open(fpath) as f:
                content = f.read()
        except OSError:
            continue

        finding_id = ""
        verdict = ""
        test_method = ""
        test_class = ""
        fix_description = ""
        impossibility_proof = ""
        phase0_result = ""

        for line in content.split("\n"):
            line_stripped = line.strip()

            # Finding ID from heading: # F-R1.dt.1.1: Title
            if line_stripped.startswith("# F-") or line_stripped.startswith("# Finding "):
                id_match = re.search(r"(F-R\d+\.\S+?)(?::|$)", line_stripped)
                if id_match:
                    finding_id = id_match.group(1)

            # Result/Verdict line
            if line_stripped.startswith("**Result:**") or line_stripped.startswith("- **Result:**"):
                verdict_match = re.search(
                    r"(CONFIRMED_AND_FIXED|IMPOSSIBLE|FIX_IMPOSSIBLE)", line_stripped
                )
                if verdict_match:
                    verdict = verdict_match.group(1)

            # Test method
            if line_stripped.startswith("**Test method:**") or line_stripped.startswith("- **Test method:**"):
                test_method = re.sub(r"^\*\*Test method:\*\*\s*", "", line_stripped.lstrip("- "))

            # Test class
            if line_stripped.startswith("**Test class:**") or line_stripped.startswith("- **Test class:**"):
                test_class = re.sub(r"^\*\*Test class:\*\*\s*", "", line_stripped.lstrip("- "))

            # Fix description
            if line_stripped.startswith("**Fix:**") or line_stripped.startswith("- **Fix:**"):
                fix_description = re.sub(r"^\*\*Fix:\*\*\s*", "", line_stripped.lstrip("- "))

            # Impossibility proof
            if line_stripped.startswith("**Impossibility proof:**") or \
                    line_stripped.startswith("- **Impossibility proof:**"):
                impossibility_proof = re.sub(
                    r"^\*\*Impossibility proof:\*\*\s*", "", line_stripped.lstrip("- ")
                )

            # Phase 0 result
            if line_stripped.startswith("**Phase 0:**") or line_stripped.startswith("- **Phase 0:**"):
                phase0_result = re.sub(r"^\*\*Phase 0:\*\*\s*", "", line_stripped.lstrip("- "))

        if finding_id:
            results[finding_id] = {
                "verdict": verdict,
                "test_method": test_method,
                "test_class": test_class,
                "fix_description": fix_description,
                "impossibility_proof": impossibility_proof,
                "phase0_result": phase0_result,
            }

    return results


def parse_audit_report(report_path: str) -> Optional[Node]:
    """Parse an audit-report.md file into an AUDIT_CYCLE node with finding children.

    Extracts:
    - Header metadata (date, round, scope)
    - Pipeline summary table (constructs, clusters, finding counts)
    - Bugs Fixed sections → AUDIT_FINDING nodes with verdict CONFIRMED_AND_FIXED
    - Removed Tests sections → AUDIT_FINDING nodes with verdict IMPOSSIBLE

    Returns None if the file cannot be read or has no parseable content.
    """
    try:
        with open(report_path) as f:
            content = f.read()
    except OSError:
        return None

    if not content.strip():
        return None

    # --- Header metadata ---
    date = ""
    round_num = 0
    scope = ""

    date_match = re.search(r"\*\*Date:\*\*\s*(.+)", content)
    if date_match:
        date = date_match.group(1).strip()

    round_match = re.search(r"\*\*Round:\*\*\s*(\d+)", content)
    if round_match:
        round_num = int(round_match.group(1))

    scope_match = re.search(r"\*\*Scope:\*\*\s*(.+)", content)
    if scope_match:
        scope = scope_match.group(1).strip()

    # --- Pipeline summary table ---
    total_findings = 0
    fixed = 0
    impossible = 0
    fix_impossible = 0
    constructs = 0
    clusters = 0
    lenses = set()

    # Parse the pipeline summary table for key metrics
    # Scope row: "| Scope | ... | N constructs, N clusters | ... |"
    scope_row = re.search(
        r"\|\s*Scope\s*\|[^|]*\|\s*(\d+)\s*constructs?,\s*(\d+)\s*clusters?\s*\|", content
    )
    if scope_row:
        constructs = int(scope_row.group(1))
        clusters = int(scope_row.group(2))

    # Suspect row: "| Suspect | ... | N findings, N cleared | ... |"
    suspect_row = re.search(
        r"\|\s*Suspect\s*\|[^|]*\|\s*(\d+)\s*findings?", content
    )
    if suspect_row:
        total_findings = int(suspect_row.group(1))

    # Prove-Fix row: "| Prove-Fix | N findings | N fixed, N impossible | ... |"
    provefix_row = re.search(
        r"\|\s*Prove-Fix\s*\|[^|]*\|\s*(\d+)\s*fixed,?\s*(\d+)\s*impossible\s*\|", content
    )
    if provefix_row:
        fixed = int(provefix_row.group(1))
        impossible = int(provefix_row.group(2))

    # --- Parse finding sections ---
    finding_nodes = []

    # Split into lines for section-based parsing
    lines = content.split("\n")
    i = 0
    current_section = ""  # "bugs_fixed" or "removed_tests"

    while i < len(lines):
        line = lines[i]

        # Detect section headers
        if line.startswith("## Bugs Fixed"):
            current_section = "bugs_fixed"
            i += 1
            continue
        elif line.startswith("## Removed Tests"):
            current_section = "removed_tests"
            i += 1
            continue
        elif line.startswith("## ") and current_section:
            # New top-level section ends the current one
            current_section = ""
            i += 1
            continue

        # Parse individual findings: ### F-R1.dt.1.1: Title
        finding_match = re.match(
            r"^###\s+(F-R\S+):\s*(.+)", line
        )
        if finding_match and current_section:
            finding_id = finding_match.group(1)
            title = finding_match.group(2).strip()
            lens = _parse_lens_from_finding_id(finding_id)
            if lens:
                lenses.add(lens)

            # Collect detail lines until next ### or ## or EOF
            details = {}
            i += 1
            while i < len(lines) and not lines[i].startswith("##"):
                detail_line = lines[i].strip()

                # Parse "- **Key:** Value" lines
                detail_match = re.match(r"^-\s+\*\*(\w+):\*\*\s*(.+)", detail_line)
                if detail_match:
                    key = detail_match.group(1).lower()
                    value = detail_match.group(2).strip()
                    details[key] = value
                i += 1

            if current_section == "bugs_fixed":
                # Extract construct and file from "Name (file:lines)"
                construct = ""
                file_path = ""
                construct_raw = details.get("construct", "")
                construct_match = re.match(r"(.+?)\s*\(([^)]+)\)", construct_raw)
                if construct_match:
                    construct = construct_match.group(1).strip()
                    file_path = construct_match.group(2).strip()
                else:
                    construct = construct_raw

                # Extract spec refs from "R41, R40" or similar
                spec_refs = []
                spec_raw = details.get("spec", "")
                if spec_raw:
                    spec_refs = [s.strip() for s in re.findall(r"R\d+", spec_raw)]

                finding_nodes.append(Node(
                    node_type=NodeType.AUDIT_FINDING,
                    data={
                        "finding_id": finding_id,
                        "title": title,
                        "construct": construct,
                        "file_path": file_path,
                        "lens": lens,
                        "concern": details.get("concern", ""),
                        "fix": details.get("fix", ""),
                        "spec_refs": spec_refs,
                        "verdict": "CONFIRMED_AND_FIXED",
                    },
                    interesting=True,
                ))

            elif current_section == "removed_tests":
                # Parse classification from "IMPOSSIBLE (reason)"
                classification = ""
                class_raw = details.get("classification", "")
                class_match = re.match(r"IMPOSSIBLE\s*\(([^)]+)\)", class_raw)
                if class_match:
                    classification = class_match.group(1).strip()
                elif "IMPOSSIBLE" in class_raw:
                    classification = class_raw.replace("IMPOSSIBLE", "").strip(" ()")

                finding_nodes.append(Node(
                    node_type=NodeType.AUDIT_FINDING,
                    data={
                        "finding_id": finding_id,
                        "title": title,
                        "verdict": "IMPOSSIBLE",
                        "classification": classification,
                        "reasoning": details.get("reasoning", ""),
                    },
                ))

            continue

        i += 1

    # If no findings were parsed from sections, fall back to summary counts
    if not finding_nodes and total_findings == 0 and fixed == 0:
        return None

    # Recount from parsed findings if summary table wasn't found
    if not total_findings:
        total_findings = len(finding_nodes)
    if not fixed:
        fixed = sum(1 for n in finding_nodes if n.data.get("verdict") == "CONFIRMED_AND_FIXED")
    if not impossible:
        impossible = sum(1 for n in finding_nodes if n.data.get("verdict") == "IMPOSSIBLE")

    # Count FIX_IMPOSSIBLE from prove-fix files (not in report sections)
    # — will be enriched by enrich_story_with_audit if prove-fix files exist

    cycle = Node(
        node_type=NodeType.AUDIT_CYCLE,
        data={
            "date": date,
            "round": round_num,
            "scope": scope,
            "total_findings": total_findings,
            "fixed": fixed,
            "impossible": impossible,
            "fix_impossible": fix_impossible,
            "lenses": sorted(lenses),
            "constructs": constructs,
            "clusters": clusters,
        },
        children=finding_nodes,
        interesting=fixed > 0,
    )

    return cycle


def enrich_findings_with_prove_fix(cycle: Node, run_dir: str):
    """Enrich AUDIT_FINDING nodes with data from prove-fix files.

    Merges test_method, test_class, fix_description, and impossibility_proof
    from prove-fix-*.md files into the corresponding finding nodes.
    Updates the cycle's fix_impossible count.
    """
    pf_data = parse_prove_fix_files(run_dir)
    if not pf_data:
        return

    fix_impossible_count = 0
    for child in cycle.children:
        fid = child.data.get("finding_id", "")
        if fid in pf_data:
            pf = pf_data[fid]
            # Override verdict if prove-fix has a different one
            if pf["verdict"]:
                child.data["verdict"] = pf["verdict"]
            if pf["test_method"]:
                child.data["test_method"] = pf["test_method"]
            if pf["test_class"]:
                child.data["test_class"] = pf["test_class"]
            if pf["fix_description"]:
                child.data["fix_description"] = pf["fix_description"]
            if pf["impossibility_proof"]:
                child.data["impossibility_proof"] = pf["impossibility_proof"]
            if pf["phase0_result"]:
                child.data["phase0_result"] = pf["phase0_result"]
            if pf["verdict"] == "FIX_IMPOSSIBLE":
                fix_impossible_count += 1

    if fix_impossible_count:
        cycle.data["fix_impossible"] = fix_impossible_count


def enrich_story_with_audit(story: Story, feature_dir: str):
    """Append audit phases to a story from on-disk audit data.

    Scans <feature_dir>/audit/ for run-NNN/ directories. For each run,
    parses the audit report and prove-fix files, builds an "audit" PHASE
    node containing the AUDIT_CYCLE, and appends it to story.phases.

    Safe to call when no audit directory exists — returns silently.
    """
    audit_dir = os.path.join(feature_dir, "audit")
    if not os.path.isdir(audit_dir):
        return

    try:
        run_dirs = sorted(
            d for d in os.listdir(audit_dir)
            if d.startswith("run-") and os.path.isdir(os.path.join(audit_dir, d))
        )
    except OSError:
        return

    for run_name in run_dirs:
        run_path = os.path.join(audit_dir, run_name)
        report_path = os.path.join(run_path, "audit-report.md")

        if not os.path.isfile(report_path):
            continue

        cycle = parse_audit_report(report_path)
        if not cycle:
            continue

        # Enrich with prove-fix detail
        enrich_findings_with_prove_fix(cycle, run_path)

        # Build phase wrapper
        round_num = cycle.data.get("round", 0)
        phase = Node(
            node_type=NodeType.PHASE,
            data={
                "stage": "audit",
                "command": "/audit",
                "title": f"Audit — Round {round_num}" if round_num else "Audit",
                "run_dir": run_name,
            },
            children=[cycle],
            interesting=cycle.data.get("fixed", 0) > 0,
        )

        story.phases.append(phase)


def parse_audit_story(feature_dir: str, feature_slug: str = "") -> Optional[Story]:
    """Create a standalone audit Story directly from on-disk audit data.

    Unlike parse_story() which requires JSONL session data, this builds
    a Story purely from audit-report.md and prove-fix-*.md files. Useful
    for generating audit narratives without a corresponding JSONL session
    (e.g., standalone audit runs or retrospective analysis).

    Args:
        feature_dir: Path to .feature/<slug>/ directory.
        feature_slug: Feature slug for the story title.

    Returns a Story with story_type="audit" and audit phases, or None
    if no audit data was found.
    """
    story = Story(
        story_type="audit",
        title=feature_slug,
    )

    enrich_story_with_audit(story, feature_dir)

    if not story.phases:
        return None

    return story
