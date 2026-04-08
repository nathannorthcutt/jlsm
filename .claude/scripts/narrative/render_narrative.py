#!/usr/bin/env python3
"""Stage 3: Render a Story AST into a polished narrative markdown article.

Consumes the AST produced by parse.py and generates a visually rich markdown
document using shields.io badges, Mermaid diagrams, GitHub alerts, progressive
disclosure, and structured conversation turns.

Design principles:
    1. Density gradient — scoping/research compressed, TDD/architecture expandable
    2. Interruptions are the story — escalations, pushback, failures are prominent
    3. Metric cards as section headers — dashboard-level summary before detail
    4. Two reading modes — skim (badges + headlines) and deep dive (<details>)
    5. Color semantics — green=passing, red=failing, amber=escalation, blue=refactor

Primary target: GitHub-flavored markdown (Mermaid native, alerts, <details>).
Static sites work via Mermaid plugins. Medium requires pre-rendered diagrams.

Usage:
    python render_narrative.py story-ast.json -o article.md
    python render_narrative.py story-ast.json  # stdout
"""

import argparse
import re
import sys
from dataclasses import dataclass, field
from typing import Callable
from urllib.parse import quote as url_quote

from model import Node, NodeType, Story, TokenUsage


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------

def format_duration(ms: int) -> str:
    """Format milliseconds into human-readable duration."""
    if ms <= 0:
        return "< 1s"
    seconds = ms / 1000
    if seconds < 60:
        return f"{seconds:.0f}s"
    minutes = seconds / 60
    if minutes < 60:
        secs = int(seconds % 60)
        if secs:
            return f"{int(minutes)}m {secs}s"
        return f"{int(minutes)}m"
    hours = minutes / 60
    mins = int(minutes % 60)
    return f"{int(hours)}h {mins}m"


def format_tokens_short(usage: TokenUsage) -> str:
    """Abbreviate token counts for badges — e.g. '142K in / 38K out'."""
    billable = usage.billable_input
    if not billable and not usage.output:
        return "0"
    return f"{_abbreviate(billable)} in / {_abbreviate(usage.output)} out"


def format_tokens_detail(usage: TokenUsage) -> str:
    """Detailed token string including cache stats."""
    billable = usage.billable_input
    if not billable and not usage.output:
        return ""
    parts = [f"{_abbreviate(billable)} in"]
    if usage.cache_read:
        parts.append(f"({_abbreviate(usage.cache_read)} cached)")
    parts.append(f"/ {_abbreviate(usage.output)} out")
    return " ".join(parts)


def _abbreviate(n: int) -> str:
    """Abbreviate large numbers: 1500 → '1.5K', 1500000 → '1.5M'."""
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    if n >= 1_000:
        return f"{n / 1_000:.1f}K"
    return str(n)


def badge(label: str, message: str, color: str) -> str:
    """Build a shields.io badge image in markdown.

    Uses the for-the-badge style for visual prominence in hero sections.
    Shields.io uses hyphens as field separators, so literal hyphens in
    label/message must be escaped as '--', and underscores as '__'.
    Spaces are encoded as '%20' or '_'.
    """
    # Escape shields.io special characters before URL encoding
    safe_label = url_quote(label.replace("-", "--").replace("_", "__"), safe="")
    safe_msg = url_quote(message.replace("-", "--").replace("_", "__"), safe="")
    return f"![{label}](https://img.shields.io/badge/{safe_label}-{safe_msg}-{color}?style=for-the-badge)"


# ---------------------------------------------------------------------------
# Prose cleaning (ported from render_markdown.py)
# ---------------------------------------------------------------------------

def _is_decoration_line(stripped: str) -> bool:
    """Check if a line is purely decorative (box drawing, arrows, dashes)."""
    if not stripped:
        return False
    if all(c in "─━═│┌┐└┘├┤┬┴┼╔╗╚╝╠╣╦╩╬ ·—-" for c in stripped):
        return True
    box_chars = sum(1 for c in stripped if c in "─━═│┌┐└┘├┤┬┴┼╔╗╚╝╠╣╦╩╬")
    if box_chars > len(stripped) * 0.4 and box_chars > 5:
        return True
    return False


def _has_box_drawing(text: str) -> bool:
    """Check if a string contains box-drawing characters."""
    return bool(re.search(r"[─━═│┌┐└┘├┤┬┴┼╔╗╚╝╠╣╦╩╬→←↑↓]", text))


def clean_agent_prose(text: str) -> str:
    """Clean up agent prose for article presentation.

    Removes decorative box-drawing, ASCII art blocks, emoji prefixes,
    and orphaned headings. Keeps the semantic content.
    """
    lines = text.split("\n")
    cleaned = []
    in_banner = False
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if _is_decoration_line(stripped):
            i += 1
            continue

        if stripped == "```" and not in_banner:
            in_banner = True
            i += 1
            continue
        if stripped == "```" and in_banner:
            in_banner = False
            i += 1
            continue
        if in_banner:
            # Strip leading box-drawing/emoji, then trailing box-drawing
            clean = re.sub(r'^[─━═ 🔍🏛️🗺️🏗️🔀📋🔄🔬⚙️🔧✓⚠️]+\s*', '', stripped)
            clean = re.sub(r'\s*[─━═]{2,}\s*$', '', clean)
            if clean:
                cleaned.append(clean)
            i += 1
            continue

        # Detect ASCII art blocks (2+ consecutive box-drawing lines)
        box_count = 0
        end = i
        for j in range(i, min(i + 10, len(lines))):
            if _has_box_drawing(lines[j]) or not lines[j].strip():
                box_count += 1
                end = j + 1
            else:
                break
        if box_count >= 2:
            i = end
            continue

        clean_line = re.sub(r'^[🔍🏛️🗺️🏗️🔀📋🔄🔬⚙️🔧]+\s*', '', stripped)
        clean_line = re.sub(r'\s*[─━═]{2,}\s*$', '', clean_line)
        clean_line = re.sub(r'^[─━═]{2,}\s*', '', clean_line)

        if clean_line:
            cleaned.append(clean_line)
        i += 1

    # Safety: if banner was never closed, the stripping may have corrupted
    # content. Re-process the original lines without banner mode.
    if in_banner:
        cleaned = []
        for line in lines:
            stripped = line.strip()
            if _is_decoration_line(stripped):
                continue
            if stripped == "```":
                continue
            clean_line = re.sub(r'^[🔍🏛️🗺️🏗️🔀📋🔄🔬⚙️🔧]+\s*', '', stripped)
            clean_line = re.sub(r'\s*[─━═]{2,}\s*$', '', clean_line)
            clean_line = re.sub(r'^[─━═]{2,}\s*', '', clean_line)
            if clean_line:
                cleaned.append(clean_line)

    # Remove orphaned headings (heading with no body text following)
    final = []
    for i, line in enumerate(cleaned):
        stripped = line.strip()
        if stripped.startswith("#") and len(stripped) < 80:
            has_body = False
            for j in range(i + 1, len(cleaned)):
                next_stripped = cleaned[j].strip()
                if not next_stripped:
                    continue
                if next_stripped.startswith("#"):
                    break
                has_body = True
                break
            if not has_body:
                continue
        final.append(line)

    return "\n".join(final).strip()


def truncate_prose(text: str, max_lines: int = 30) -> str:
    """Truncate long prose blocks, keeping the first and last parts."""
    lines = text.split("\n")
    if len(lines) <= max_lines:
        return text
    keep = max_lines // 2
    return "\n".join(lines[:keep] + ["", "*(continued...)*", ""] + lines[-keep:])


def smart_truncate(text: str, max_chars: int = 280) -> tuple[str, bool]:
    """Truncate text at a word boundary. Returns (truncated, was_truncated).

    Finds the last space before max_chars and cuts there, avoiding
    mid-word breaks. Returns the original text unchanged if it fits.
    """
    if len(text) <= max_chars:
        return text, False
    # Find last space before the limit
    cut = text.rfind(" ", 0, max_chars)
    if cut == -1:
        cut = max_chars  # no space found, hard cut
    return text[:cut], True


def _styled_div(content: str, full_content: str, speaker: str,
                bg: str, border: str) -> list[str]:
    """Render a speaker turn as a styled div, expandable if content was truncated.

    If full_content is longer than content (truncated), wraps in a
    <details> inside the styled div so the user can expand to read
    the full text while preserving the background color.
    """
    lines = []
    lines.append(f'<div style="background:{bg};border-left:4px solid {border};padding:12px 16px;margin:8px 0;border-radius:4px">')

    if content != full_content:
        # Expandable: show truncated preview, full text on expand
        preview_html = content.replace("\n", "<br>")
        full_html = full_content.replace("\n", "<br>")
        lines.append(f'<details><summary><strong>{speaker}:</strong> {preview_html}...</summary>')
        lines.append(f'<br>{full_html}')
        lines.append("</details>")
    else:
        content_html = content.replace("\n", "<br>")
        lines.append(f'<strong>{speaker}:</strong> {content_html}')

    lines.append("</div>")
    lines.append("")
    return lines


# ---------------------------------------------------------------------------
# AST helpers
# ---------------------------------------------------------------------------

# Phase emoji for visual differentiation in headings
PHASE_EMOJI = {
    "scoping": "🔍",
    "domains": "🗺️",
    "planning": "🏗️",
    "testing": "🧪",
    "implementation": "⚙️",
    "refactor": "🔧",
    "coordination": "🔀",
    "pr": "📋",
    "retro": "🔄",
    "resume": "🔁",
    "curation": "🔬",
    "research": "📚",
    "architect": "🏛️",
    "knowledge": "📖",
    "decisions": "⚖️",
}

# Pipeline stages in canonical order for Mermaid gantt
STAGE_ORDER = [
    "scoping", "domains", "planning", "testing", "implementation",
    "refactor", "coordination", "pr", "retro",
]


def count_new_tests(story: Story) -> int:
    """Count new tests created during the feature.

    Sums test_passed from each TDD cycle — each work unit reports how many
    tests pass after it completes, which represents the tests that WU created.
    We don't count intermediate failures (retry noise) since the implied
    result of a completed feature is all tests passing.
    """
    total = 0

    def _walk(nodes: list[Node]):
        nonlocal total
        for n in nodes:
            if n.node_type == NodeType.TDD_CYCLE:
                total += n.data.get("test_passed", 0)
            if n.children:
                _walk(n.children)

    for phase in story.phases:
        _walk(phase.children)
    return total


# ---------------------------------------------------------------------------
# Render context
# ---------------------------------------------------------------------------

@dataclass
class RenderContext:
    """Carries state across render calls within a single story."""
    story: Story
    current_phase: str = ""
    phase_index: int = 0
    test_total_passed: int = 0
    test_total_failed: int = 0


# ---------------------------------------------------------------------------
# Node renderers — one function per node type
# ---------------------------------------------------------------------------

# User responses that are just pipeline confirmations — not meaningful content
_CONFIRMATION_RESPONSES = frozenset({
    "yes", "y", "no", "n", "ok", "okay", "sure", "continue",
    "create", "auto", "stop", "done", "skip", "proceed",
})


def _is_confirmation(text: str) -> bool:
    """Check if user text is a routine confirmation with no narrative value."""
    stripped = text.strip().rstrip(".!").lower()
    return stripped in _CONFIRMATION_RESPONSES


def _render_conversation(node: Node, ctx: RenderContext) -> list[str]:
    """Render a conversation (2+ exchanges) as blockquote turns.

    In scoping phases, conversations are the main content — render fully.
    In other phases, wrap in <details> for progressive disclosure.
    Routine confirmations (yes, ok, create, etc.) are suppressed.
    """
    lines = []
    is_scoping = ctx.current_phase == "scoping"

    if not is_scoping:
        count = node.data.get("exchange_count", len(node.children))
        lines.append(f"<details><summary><b>Conversation</b> — {count} exchanges</summary>")
        lines.append("")
        lines.append("<br>")
        lines.append("")

    for child in node.children:
        q = child.data.get("question", "")
        a = child.data.get("answer", "")
        if q:
            q_clean = clean_agent_prose(q)
            if not q_clean.strip():
                continue
            q_short, q_truncated = smart_truncate(q_clean)
            lines.extend(_styled_div(
                q_short, q_clean if q_truncated else q_short,
                "vallorcine", "#1e3a5f", "#4184e4",
            ))
        if a and not _is_confirmation(a):
            a_short, a_truncated = smart_truncate(a)
            lines.extend(_styled_div(
                a_short, a if a_truncated else a_short,
                "User", "#1a3a2a", "#3fb950",
            ))

    if not is_scoping:
        lines.append("</details>")
        lines.append("")

    return lines


def _render_exchange(node: Node, ctx: RenderContext) -> list[str]:
    """Render a standalone exchange (user text outside a conversation)."""
    answer = node.data.get("answer", node.content or "")
    if not answer or _is_confirmation(answer):
        return []
    a_short, a_truncated = smart_truncate(answer)
    return _styled_div(
        a_short, answer if a_truncated else a_short,
        "User", "#1a3a2a", "#3fb950",
    )


def _render_tdd_cycle(node: Node, ctx: RenderContext) -> list[str]:
    """Render an individual TDD cycle.

    Interesting cycles (escalations, retries) get expanded with alerts.
    Routine cycles return empty — they're batched into the summary table
    by _render_phase.
    """
    if not node.interesting:
        return []  # handled by batch table

    lines = []
    name = node.data.get("unit_name", "Work unit")
    duration = format_duration(node.data.get("duration_ms", 0))
    summary = node.data.get("summary", "")
    types = node.data.get("interesting_types", [])
    passed = node.data.get("test_passed", 0)
    failed = node.data.get("test_failed", 0)

    # Color based on severity
    if "escalation" in types or failed > 0:
        bg, border = "#3d2f1f", "#d29922"  # amber/warning
    else:
        bg, border = "#1e3a5f", "#4184e4"  # blue/info

    body_parts = [f"<strong>{name}</strong> — {duration}"]
    if failed:
        body_parts.append(f"Tests: {passed} passed, {failed} failed")
    elif passed:
        body_parts.append(f"Tests: {passed} passed")
    if types:
        body_parts.append(f"Signals: {', '.join(types)}")
    if summary:
        parts = [p.strip() for p in summary.split(" — ")]
        for part in parts[1:]:
            body_parts.append(f"• {part}")

    lines.append(f'<div style="background:{bg};border-left:4px solid {border};padding:12px 16px;margin:8px 0;border-radius:4px">')
    lines.append("<br>".join(body_parts))
    lines.append("</div>")
    lines.append("")
    return lines


def _render_tdd_summary_table(cycles: list[Node], ctx: RenderContext) -> list[str]:
    """Render routine TDD cycles as a compact summary table.

    This is the skim-path representation — one row per work unit showing
    duration, test counts, file counts, and percentage of total token usage.
    """
    if not cycles:
        return []

    story_total = (ctx.story.tokens.billable_input + ctx.story.tokens.output) or 1

    lines = [
        "| Work Unit | Duration | Tests | Files | % of total |",
        "|-----------|----------|-------|-------|------------|",
    ]

    for c in cycles:
        name = c.data.get("unit_name", "—")
        duration = format_duration(c.data.get("duration_ms", 0))
        passed = c.data.get("test_passed", 0)
        files = len(c.data.get("files_written", []))
        tests = f"✅ {passed}" if passed else "—"
        wu_total = c.tokens.billable_input + c.tokens.output
        pct = (wu_total / story_total) * 100

        lines.append(f"| {name} | {duration} | {tests} | {files} | {pct:.0f}% |")

    lines.append("")
    return lines


def _render_research(node: Node, ctx: RenderContext) -> list[str]:
    """Render a research commission as a knowledge card."""
    topic = node.data.get("topic", "")
    subject = node.data.get("subject", "")
    kb_path = node.data.get("kb_path", "")
    findings = node.data.get("findings_summary", "")

    title_parts = ["<strong>📚 Research"]
    if topic:
        title_parts.append(f": {topic}")
    title_parts.append("</strong>")

    body_parts = []
    if subject:
        body_parts.append(f"Subject: {subject}")
    if kb_path:
        body_parts.append(f"KB: <code>{kb_path}</code>")
    if findings:
        cleaned = clean_agent_prose(findings)
        if cleaned:
            body_parts.append(truncate_prose(cleaned, 8).replace("\n", "<br>"))

    lines = [
        '<div style="background:#1a3a2a;border-left:4px solid #3fb950;padding:12px 16px;margin:8px 0;border-radius:4px">',
        "".join(title_parts),
    ]
    if body_parts:
        lines.append("<br>" + "<br>".join(body_parts))
    lines.append("</div>")
    lines.append("")
    return lines


def _render_architect(node: Node, ctx: RenderContext) -> list[str]:
    """Render an architecture decision as a decision card."""
    question = node.data.get("question", "")
    decision = node.data.get("decision", "")
    rejected = node.data.get("rejected", [])
    adr_path = node.data.get("adr_path", "")

    body_parts = ["<strong>🏛️ Architecture Decision</strong>"]
    if question:
        body_parts.append(f"<strong>Question:</strong> {question}")
    if decision:
        body_parts.append(f"<strong>Decision:</strong> {decision}")
    if rejected:
        struck = ", ".join(f"<del>{r}</del>" for r in rejected)
        body_parts.append(f"<strong>Rejected:</strong> {struck}")
    if adr_path:
        body_parts.append(f"<strong>ADR:</strong> <code>{adr_path}</code>")

    return [
        '<div style="background:#1e3a5f;border-left:4px solid #4184e4;padding:12px 16px;margin:8px 0;border-radius:4px">',
        "<br>".join(body_parts),
        "</div>",
        "",
    ]


def _render_stage_transition(node: Node, ctx: RenderContext) -> list[str]:
    """Render a stage transition (command invoked mid-phase).

    Only rendered when the transition command differs from the phase's own
    command — otherwise it's redundant with the phase heading.
    """
    cmd = node.data.get("command", "")
    args = node.data.get("args", "")
    # Skip if this matches the current phase (already shown in heading)
    if not cmd:
        return []
    display = f"`{cmd}`"
    if args:
        display += f" {args}"
    return [f"**→ {display}**", ""]


def _render_session_break(node: Node, ctx: RenderContext) -> list[str]:
    """Render a crash/recovery boundary."""
    gap = format_duration(node.data.get("gap_duration_ms", 0))
    return [
        '<div style="background:#3d1f1f;border-left:4px solid #f85149;padding:12px 16px;margin:8px 0;border-radius:4px">',
        f'⚠️ <strong>Session crashed.</strong> Resumed {gap} later.',
        "</div>",
        "",
    ]


# Phases where test results are meaningful (TDD loop stages)
_TDD_PHASES = frozenset({
    "testing", "implementation", "refactor", "coordination", "resume",
})


def _render_test_result(node: Node, ctx: RenderContext) -> list[str]:
    """Render a test result summary. Failed tests get a warning alert.

    Test results are only shown in TDD-relevant phases. In other phases
    (scoping, research, etc.) tool results that happen to contain words
    like 'pass' or 'fail' are false positives from file reads or grep.
    """
    if ctx.current_phase not in _TDD_PHASES:
        return []

    passed = node.data.get("passed", 0)
    failed = node.data.get("failed", 0)
    runs = node.data.get("run_count", 1)

    ctx.test_total_passed += passed
    ctx.test_total_failed += failed

    if failed:
        return [
            '<div style="background:#3d2f1f;border-left:4px solid #d29922;padding:12px 16px;margin:8px 0;border-radius:4px">',
            f'⚠️ Tests: {passed} passed, {failed} failed ({runs} runs)',
            "</div>",
            "",
        ]
    return [f"✅ Tests: {passed} passed ({runs} runs)", ""]


def _render_escalation(node: Node, ctx: RenderContext) -> list[str]:
    """Render an escalation as a prominent warning block.

    In the retro phase, escalation nodes typically contain the acceptance
    criteria checklist — not a real escalation. These are split into a
    success card (met items) and an info card (deferred items).
    """
    content = node.content or ""
    cleaned = clean_agent_prose(content) if content else ""

    if ctx.current_phase == "retro" and cleaned:
        return _render_retro_checklist(cleaned)

    body = ""
    if cleaned:
        body = "<br>" + truncate_prose(cleaned, 10).replace("\n", "<br>")
    return [
        '<div style="background:#3d2f1f;border-left:4px solid #d29922;padding:12px 16px;margin:8px 0;border-radius:4px">',
        f'⚠️ <strong>Escalation</strong>{body}',
        "</div>",
        "",
    ]


def _render_retro_checklist(text: str) -> list[str]:
    """Parse retro content into acceptance criteria cards.

    Extracts numbered checklist items and separates them into:
    - Met items → green success card
    - Deferred/unmet items → blue info card
    Remaining prose goes into a collapsed details block.
    """
    met_items = []
    deferred_items = []
    other_lines = []

    for line in text.split("\n"):
        stripped = line.strip()
        # Match numbered checklist items: "1. Description — met (...)"
        # Must have a status marker (— met, — deferred, etc.) to qualify
        if re.match(r"^\d+\.", stripped) and "—" in stripped:
            # Strip markdown bold markers for matching
            lower = re.sub(r"\*\*", "", stripped).lower()
            if "— deferred" in lower or "— not met" in lower or "— skipped" in lower:
                deferred_items.append(stripped)
            elif "— met" in lower or "— yes" in lower or "— passed" in lower:
                met_items.append(stripped)
        else:
            other_lines.append(stripped)

    lines = []

    # Retro summary header — extract scope/drift info from remaining prose
    scope_line = ""
    for line in other_lines:
        if "scope:" in line.lower() or "drift" in line.lower():
            scope_line = line.strip()
            break
    if scope_line:
        lines.append('<div style="background:#1e3a5f;border-left:4px solid #4184e4;padding:12px 16px;margin:8px 0;border-radius:4px">')
        lines.append(f'🔍 <strong>Retrospective</strong><br>{scope_line}')
        lines.append("</div>")
        lines.append("")

    # Success card for met items
    if met_items:
        items_html = "<br>".join(f"✅ {item}" for item in met_items)
        lines.append('<div style="background:#1a3a2a;border-left:4px solid #3fb950;padding:12px 16px;margin:8px 0;border-radius:4px">')
        lines.append(f'<strong>Acceptance Criteria</strong><br><br>{items_html}')
        lines.append("</div>")
        lines.append("")

    # Info card for deferred items
    if deferred_items:
        items_html = "<br>".join(f"⏸️ {item}" for item in deferred_items)
        lines.append('<div style="background:#1e3a5f;border-left:4px solid #4184e4;padding:12px 16px;margin:8px 0;border-radius:4px">')
        lines.append(f'<strong>Deferred</strong><br><br>{items_html}')
        lines.append("</div>")
        lines.append("")

    # Collapse remaining prose — filter out orphaned headings and fragments
    # left behind after checklist extraction (e.g., "Acceptance criteria
    # from brief.md:", truncated lines under 20 chars)
    substantive = [
        l for l in other_lines
        if l.strip()
        and not l.strip().endswith(":")           # orphaned heading labels
        and not re.match(r"^\*\*.*\*\*:?$", l.strip())  # bold markdown headings
        and not re.match(r"^(FEATURE RETRO|SCOPING AGENT|DOMAIN SCOUT|RESEARCH AGENT|ARCHITECT AGENT|COORDINATOR)", l.strip())  # agent banners
        and len(l.strip()) >= 20
        and "let me analyze" not in l.lower()     # retro preamble noise
    ]
    if substantive:
        remaining_clean = clean_agent_prose("\n".join(substantive))
        if remaining_clean.strip():
            summary = remaining_clean.split("\n")[0][:100]
            body_html = remaining_clean.replace("\n", "<br>")
            lines.append(f"<details><summary>📋 <b>{summary}...</b></summary>")
            lines.append("")
            lines.append("<br>")
            lines.append("")
            lines.append(body_html)
            lines.append("")
            lines.append("</details>")
            lines.append("")

    return lines


def _render_action_group(node: Node, ctx: RenderContext) -> list[str]:
    """Render a group of file operations or a subagent action summary.

    Two data shapes:
    - Tool call batch: .data.actions[] with verb/target/summary
    - Subagent result: .data.unit_name with summary/files_written
    """
    # Subagent-style action group
    if "unit_name" in node.data:
        name = node.data.get("unit_name", "Action")
        duration = format_duration(node.data.get("duration_ms", 0))
        summary = node.data.get("summary", "")
        detail = ""
        if summary:
            parts = [p.strip() for p in summary.split(" — ")]
            detail = " — ".join(parts[1:]) if len(parts) > 1 else ""
        if detail:
            return [f"> **{name}** *({duration})* — {detail}", ""]
        return [f"> **{name}** *({duration})*", ""]

    # Tool call batch
    actions = node.data.get("actions", [])
    if not actions:
        return []

    if len(actions) <= 3:
        # Compact inline list for small batches
        items = [a.get("summary", f"{a.get('verb', '?')} {a.get('target', '')}") for a in actions]
        return [f"📝 *{'; '.join(items)}*", ""]

    # Progressive disclosure for larger batches
    lines = [
        f"<details><summary>📝 <b>{len(actions)} file operations</b></summary>",
        "",
        "<br>",
        "",
    ]
    for a in actions:
        verb = a.get("verb", "")
        target = a.get("target", "")
        lines.append(f"- `{verb}` {target}")
    lines.append("")
    lines.append("</details>")
    lines.append("")
    return lines


def _render_prose(node: Node, ctx: RenderContext) -> list[str]:
    """Render agent prose. Interesting prose shown fully, routine wrapped in <details>.

    Short blocks (<=5 lines) render inline. Longer blocks use progressive
    disclosure: a short summary label in the <details> toggle, with the
    full content (minus the summary line) in the expanded body, formatted
    as a blockquote for visual separation.
    """
    content = node.content or ""
    if not content.strip():
        return []

    cleaned = clean_agent_prose(content)
    if not cleaned.strip():
        return []

    if node.interesting:
        return [truncate_prose(cleaned, 30), ""]

    # Routine prose — show short blocks inline, wrap long ones
    cleaned_lines = cleaned.split("\n")
    if len(cleaned_lines) <= 5:
        return [cleaned, ""]

    # Summary is the first line; body is the rest (no duplication)
    summary = cleaned_lines[0][:120]
    body_lines = cleaned_lines[1:]
    # Strip leading empty lines from body
    while body_lines and not body_lines[0].strip():
        body_lines.pop(0)
    body = truncate_prose("\n".join(body_lines), 40)

    lines = [
        f"<details><summary>{summary}...</summary>",
        "",
        "<br>",
        "",
    ]
    # Render body as blockquote for visual separation from surrounding content
    for bl in body.split("\n"):
        lines.append(f"> {bl}" if bl.strip() else ">")
    lines.append("")
    lines.append("</details>")
    lines.append("")
    return lines


# ---------------------------------------------------------------------------
# Dispatch table
# ---------------------------------------------------------------------------

RENDERERS: dict[str, Callable[[Node, RenderContext], list[str]]] = {
    NodeType.CONVERSATION: _render_conversation,
    NodeType.EXCHANGE: _render_exchange,
    NodeType.TDD_CYCLE: _render_tdd_cycle,
    NodeType.RESEARCH: _render_research,
    NodeType.ARCHITECT: _render_architect,
    NodeType.STAGE_TRANSITION: _render_stage_transition,
    NodeType.SESSION_BREAK: _render_session_break,
    NodeType.TEST_RESULT: _render_test_result,
    NodeType.ESCALATION: _render_escalation,
    NodeType.ACTION_GROUP: _render_action_group,
    NodeType.PROSE: _render_prose,
}


def _render_node(node: Node, ctx: RenderContext) -> list[str]:
    """Dispatch a node to its type-specific renderer. Falls back to content."""
    renderer = RENDERERS.get(node.node_type)
    if renderer:
        return renderer(node, ctx)
    # Unknown node type — render content if present
    if node.content:
        return [node.content[:500], ""]
    return []


# ---------------------------------------------------------------------------
# Phase renderer
# ---------------------------------------------------------------------------

# Node types that represent user interaction or notable events — shown inline
_PROMINENT_TYPES = frozenset({
    NodeType.CONVERSATION, NodeType.EXCHANGE, NodeType.ESCALATION,
    NodeType.SESSION_BREAK, NodeType.RESEARCH, NodeType.ARCHITECT,
})

# Node types that are internal narration — collapsed into a details block
_BACKGROUND_TYPES = frozenset({
    NodeType.PROSE, NodeType.ACTION_GROUP, NodeType.TEST_RESULT,
    NodeType.STAGE_TRANSITION,
})


def _render_phase(node: Node, ctx: RenderContext) -> list[str]:
    """Render a pipeline phase with its heading, metrics, and children.

    Children are split into three groups:
    - Prominent: user interactions, escalations, research/architect cards,
      session breaks — rendered inline, always visible.
    - Background: internal narration (prose, file writes, test results) —
      collected and collapsed into a single expandable "Details" block.
    - TDD cycles: routine ones in a summary table, interesting ones expanded.
    """
    stage = node.data.get("stage", "")
    title = node.data.get("title", stage.title())
    duration = format_duration(node.duration_ms)
    ctx.current_phase = stage

    # Strip the feature slug from phase titles — it's redundant in context
    story_title = ctx.story.title
    if story_title and f"— {story_title}" in title:
        title = title.replace(f" — {story_title}", "").strip()

    emoji = PHASE_EMOJI.get(stage, "📌")
    anchor_id = f"phase-{ctx.phase_index}"
    ctx.phase_index += 1
    lines = [f'<a id="{anchor_id}"></a>', "", f"## {emoji} {title}", ""]

    # Phase metric strip
    metrics = [f"*Time: {duration}*"]
    token_str = format_tokens_detail(node.tokens)
    if token_str:
        metrics.append(f"*Tokens: {token_str}*")
    lines.append(" | ".join(metrics))
    lines.append("")

    # Categorize children
    routine_cycles = []
    interesting_cycles = []
    prominent = []
    background = []

    for child in node.children:
        if child.node_type == NodeType.TDD_CYCLE:
            if child.interesting:
                interesting_cycles.append(child)
            else:
                routine_cycles.append(child)
        elif child.node_type in _PROMINENT_TYPES:
            prominent.append(child)
        else:
            background.append(child)

    # Render prominent children inline (interactions, escalations, etc.)
    for child in prominent:
        lines.extend(_render_node(child, ctx))

    # Render interesting TDD cycles as expanded alerts
    for cycle in interesting_cycles:
        lines.extend(_render_tdd_cycle(cycle, ctx))

    # Render routine TDD cycles as a summary table
    if routine_cycles:
        lines.extend(_render_tdd_summary_table(routine_cycles, ctx))

    # Collapse background narration into a single expandable block
    if background:
        bg_lines = []
        for child in background:
            rendered = _render_node(child, ctx)
            if rendered:
                bg_lines.extend(rendered)
        if bg_lines:
            # Build a short summary of what's inside
            file_ops = sum(1 for c in background
                          if c.node_type == NodeType.ACTION_GROUP)
            prose_count = sum(1 for c in background
                            if c.node_type == NodeType.PROSE)
            summary_parts = []
            if prose_count:
                summary_parts.append(f"{prose_count} steps")
            if file_ops:
                summary_parts.append(f"{file_ops} file operations")
            summary_label = ", ".join(summary_parts) if summary_parts else "details"

            lines.append(f"<details><summary>📋 <b>Show {summary_label}</b></summary>")
            lines.append("")
            lines.append("<br>")
            lines.append("")
            lines.extend(bg_lines)
            lines.append("</details>")
            lines.append("")

    return lines


# ---------------------------------------------------------------------------
# Hero section
# ---------------------------------------------------------------------------

def _render_hero(story: Story, ctx: RenderContext) -> list[str]:
    """Render the hero section: title, badge row, and Mermaid pipeline gantt."""
    lines = []

    # Title
    title = story.title or "Session"
    story_type = story.story_type
    if story_type == "feature":
        lines.append(f"# Building `{title}` with vallorcine")
    elif story_type == "curation":
        lines.append(f"# Curating `{title}`")
    elif story_type == "research":
        lines.append(f"# Researching `{title}`")
    elif story_type == "architect":
        lines.append(f"# Architecture: `{title}`")
    else:
        lines.append(f"# `{title}`")
    lines.append("")

    # Badge row
    duration = format_duration(story.duration_ms)
    tokens_short = format_tokens_short(story.tokens)
    new_tests = count_new_tests(story)
    model = story.model or "unknown"
    sessions = len(story.sessions)

    cli_version = story.cli_version or ""
    vallorcine_version = story.vallorcine_version or ""

    badges = [
        badge("duration", duration, "blue"),
        badge("tokens", tokens_short, "blueviolet"),
        badge("model", model, "lightgrey"),
    ]
    if cli_version:
        badges.append(badge("claude code", f"v{cli_version}", "lightgrey"))
    if vallorcine_version:
        badges.append(badge("vallorcine", f"v{vallorcine_version}", "orange"))
    if new_tests:
        badges.append(badge("new tests", str(new_tests), "brightgreen"))
    if sessions > 1:
        badges.append(badge("sessions", f"{sessions} (crash recovery)", "yellow"))

    lines.append(" ".join(badges))
    lines.append("")

    # Mermaid gantt chart of pipeline phases
    if len(story.phases) > 1:
        # Use dark theme with muted bar colors for readability on both
        # light and dark backgrounds. Section colors differentiate sessions.
        lines.append("```mermaid")
        lines.append("%%{init: {'theme': 'dark', 'themeVariables': {"
                      "'sectionBkgColor': '#2d333b', 'sectionBkgColor2': '#1c2128',"
                      "'taskBkgColor': '#4184e4', 'activeTaskBkgColor': '#4184e4',"
                      "'taskTextColor': '#e6edf3', 'taskTextDarkColor': '#e6edf3',"
                      "'altSectionBkgColor': '#1c2128'"
                      "}}}%%")
        lines.append("gantt")
        lines.append("    title Pipeline (h:mm)")
        lines.append("    dateFormat YYYY-MM-DD HH:mm")
        lines.append("    axisFormat %H:%M")

        # Group phases into logical sections by work type:
        #   Discovery — understanding the problem (scoping, domains, research, architect)
        #   Execution — building the solution (planning, coordination + work units)
        #   Delivery  — shipping and reflecting (PR, retro)
        # Resume is a boundary marker, placed in whichever group follows it.
        _DISCOVERY_STAGES = frozenset({"scoping", "domains", "research", "architect", "knowledge", "decisions"})
        _EXECUTION_STAGES = frozenset({"planning", "testing", "implementation", "refactor", "coordination"})
        _DELIVERY_STAGES = frozenset({"pr", "retro", "complete"})

        current_section = ""
        cumulative_min = 0

        for i, phase in enumerate(story.phases):
            stage = phase.data.get("stage", f"phase-{i}")

            # Determine which section this phase belongs to
            if stage in _DISCOVERY_STAGES:
                section = "Discovery"
            elif stage in _EXECUTION_STAGES:
                section = "Execution"
            elif stage in _DELIVERY_STAGES:
                section = "Delivery"
            else:
                # Resume and unknown stages stay in whatever section follows
                section = current_section or "Execution"

            if section != current_section:
                lines.append(f"    section {section}")
                current_section = section

            label = stage.title()
            duration_min = max(1, phase.duration_ms // 60_000)  # min 1m for visibility

            def _gantt_ts(minutes: int) -> str:
                """Convert cumulative minutes to YYYY-MM-DD HH:mm for Mermaid."""
                days, remaining = divmod(minutes, 1440)
                hours, mins = divmod(remaining, 60)
                return f"2000-01-{1 + days:02d} {hours:02d}:{mins:02d}"

            lines.append(f"    {label} :p{i}, {_gantt_ts(cumulative_min)}, {_gantt_ts(cumulative_min + duration_min)}")

            # Add work unit bars inside coordination/planning phases
            tdd_cycles = [c for c in phase.children
                          if c.node_type == NodeType.TDD_CYCLE]
            if tdd_cycles and stage in ("coordination", "planning"):
                wu_offset = cumulative_min
                for j, wu in enumerate(tdd_cycles):
                    # Extract short name: "WU-1 test-implement-refactor" → "WU-1"
                    wu_name = wu.data.get("unit_name", f"WU-{j+1}")
                    short_name = wu_name.split()[0] if " " in wu_name else wu_name
                    wu_dur = max(1, wu.data.get("duration_ms", 0) // 60_000)
                    lines.append(f"    {short_name} :p{i}w{j}, {_gantt_ts(wu_offset)}, {_gantt_ts(wu_offset + wu_dur)}")
                    wu_offset += wu_dur

            cumulative_min += duration_min

        lines.append("```")
        lines.append("")

    # Phase breakdown table — between gantt and stage content
    if story.phases:
        total_in = story.tokens.billable_input or 1  # avoid div by zero
        total_out = story.tokens.output or 1

        lines.append("### Phase Breakdown")
        lines.append("")
        lines.append("| Phase | Duration | Tokens (in) | Tokens (out) | % of total |")
        lines.append("|-------|----------|-------------|--------------|------------|")
        for i, phase in enumerate(story.phases):
            stage = phase.data.get("stage", "—")
            emoji = PHASE_EMOJI.get(stage, "")
            title = phase.data.get("title", stage.title())
            # Strip redundant feature slug from title
            if story.title and f"— {story.title}" in title:
                title = title.replace(f" — {story.title}", "").strip()
            dur = format_duration(phase.duration_ms)
            inp = _abbreviate(phase.tokens.billable_input)
            out = _abbreviate(phase.tokens.output)
            phase_total = phase.tokens.billable_input + phase.tokens.output
            story_total = total_in + (story.tokens.output or 1)
            pct = (phase_total / story_total) * 100
            # Link using explicit anchor ID — avoids emoji/duplicate issues
            lines.append(f"| [{emoji} {title}](#phase-{i}) | {dur} | {inp} | {out} | {pct:.0f}% |")
        lines.append("")

    lines.append("---")
    lines.append("")
    return lines


# ---------------------------------------------------------------------------
# Footer
# ---------------------------------------------------------------------------

def _render_footer() -> list[str]:
    """Render the attribution footer."""
    return [
        "---",
        "",
        "*Generated by [vallorcine](https://github.com/telefrek/vallorcine) showcase tools.*",
    ]


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def render_story(story: Story) -> str:
    """Render a Story AST into a complete narrative markdown document.

    This is the main entry point for stage 3 of the showcase pipeline.
    Produces a self-contained markdown document with hero section,
    phase-by-phase narrative, and metrics footer.
    """
    ctx = RenderContext(story=story)

    lines = []
    lines.extend(_render_hero(story, ctx))

    for phase in story.phases:
        lines.extend(_render_phase(phase, ctx))
        lines.append("")

    lines.extend(_render_footer())

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    """CLI entry point: load a Story AST JSON and render to markdown."""
    parser = argparse.ArgumentParser(
        description="Render a Story AST into a polished narrative markdown article."
    )
    parser.add_argument("story_file", help="Path to story AST JSON file")
    parser.add_argument("--output", "-o", help="Output file (default: stdout)")
    args = parser.parse_args()

    story = Story.load(args.story_file)
    markdown = render_story(story)

    if args.output:
        with open(args.output, "w") as f:
            f.write(markdown)
        print(f"Wrote {len(markdown)} bytes to {args.output}", file=sys.stderr)
    else:
        print(markdown)


if __name__ == "__main__":
    main()
