#!/usr/bin/env python3
"""Stage 3 (HTML): Render a Story AST into a standalone HTML narrative.

Consumes the AST produced by parse.py and generates a single-file HTML
document with all CSS inline. No external dependencies (CDN, JS libraries,
web fonts). Uses native <details>/<summary> for collapsible content and
inline SVG for the timeline visualization.

Design principles:
    1. Dark theme — easy on the eyes, matches terminal aesthetics
    2. Phase-colored navigation — each pipeline stage has a distinct accent
    3. Collapsible detail — skim the cards, expand for full content
    4. Standalone — one file, no network requests, works offline
    5. Audit-aware — renders audit pipelines with finding cards and lens views

Usage:
    python render_html.py story-ast.json -o narrative.html
    python render_html.py story-ast.json  # stdout
"""

import argparse
import html
import json
import sys
from typing import Callable

from model import Node, NodeType, Story, TokenUsage


# ---------------------------------------------------------------------------
# Formatting helpers (ported from render_narrative.py)
# ---------------------------------------------------------------------------

def format_duration(ms: int) -> str:
    """Format milliseconds into human-readable duration."""
    if ms == 0:
        return "\u2014"  # em dash for unknown/not measured
    if ms < 0:
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
    """Abbreviate token counts — e.g. '142K in / 38K out'."""
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
    """Abbreviate large numbers: 1500 -> '1.5K', 1500000 -> '1.5M'."""
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    if n >= 1_000:
        return f"{n / 1_000:.1f}K"
    return str(n)


def _cost_value(usage: TokenUsage) -> float:
    """Compute raw cost in dollars from token counts using Opus pricing.

    Pricing (per million tokens, as of 2025):
        Input:        $15.00
        Output:       $75.00
        Cache read:    $1.50
        Cache create: $18.75
    """
    input_cost = (usage.input / 1_000_000) * 15.00
    output_cost = (usage.output / 1_000_000) * 75.00
    cache_read_cost = (usage.cache_read / 1_000_000) * 1.50
    cache_create_cost = (usage.cache_create / 1_000_000) * 18.75
    return input_cost + output_cost + cache_read_cost + cache_create_cost


def _cost_estimate(usage: TokenUsage) -> str:
    """Format API cost estimate as a string."""
    total = _cost_value(usage)
    if total < 0.01:
        return "< $0.01"
    return f"${total:.2f}"


def _esc(text: str) -> str:
    """HTML-escape text content."""
    return html.escape(text, quote=True)


# ---------------------------------------------------------------------------
# Phase colors and metadata
# ---------------------------------------------------------------------------

PHASE_COLORS: dict[str, str] = {
    "scoping": "#4a9eff",
    "domains": "#4a9eff",
    "planning": "#b44aff",
    "testing": "#4aff7a",
    "implementation": "#ffb74a",
    "refactor": "#4af5ff",
    "coordination": "#b44aff",
    "pr": "#4a9eff",
    "retro": "#b44aff",
    "resume": "#ffb74a",
    "curation": "#4af5ff",
    "research": "#4a9eff",
    "architect": "#b44aff",
    "knowledge": "#4af5ff",
    "decisions": "#b44aff",
    # Audit phases
    "audit": "#ff6b4a",
    "scope": "#ff6b4a",
    "suspect": "#ffb74a",
    "prove-fix": "#4aff7a",
    "report": "#4a9eff",
}

PHASE_EMOJI: dict[str, str] = {
    "scoping": "&#x1F50D;",
    "domains": "&#x1F5FA;",
    "planning": "&#x1F3D7;",
    "testing": "&#x1F9EA;",
    "implementation": "&#x2699;",
    "refactor": "&#x1F527;",
    "coordination": "&#x1F500;",
    "pr": "&#x1F4CB;",
    "retro": "&#x1F504;",
    "resume": "&#x1F501;",
    "curation": "&#x1F52C;",
    "research": "&#x1F4DA;",
    "architect": "&#x1F3DB;",
    "knowledge": "&#x1F4D6;",
    "decisions": "&#x2696;",
    "audit": "&#x1F6E1;",
    "scope": "&#x1F50D;",
    "suspect": "&#x26A0;",
    "prove-fix": "&#x2705;",
    "report": "&#x1F4CB;",
}

# Lens colors for audit findings
LENS_COLORS: dict[str, str] = {
    "contracts": "#4a9eff",
    "state": "#b44aff",
    "data-flow": "#4aff7a",
    "transformation": "#ffb74a",
    "concurrency": "#ff6b4a",
    "lifecycle": "#4af5ff",
    "error-handling": "#ff6b4a",
    "boundary": "#b44aff",
}


# ---------------------------------------------------------------------------
# CSS
# ---------------------------------------------------------------------------

CSS = """
:root {
    --bg: #1a1a2e;
    --bg-surface: #16213e;
    --bg-card: #1e2a47;
    --bg-elevated: #253253;
    --text: #e0e0e0;
    --text-dim: #8892a0;
    --text-bright: #ffffff;
    --border: #2a3a5c;
    --accent-blue: #4a9eff;
    --accent-purple: #b44aff;
    --accent-green: #4aff7a;
    --accent-amber: #ffb74a;
    --accent-teal: #4af5ff;
    --accent-red: #ff6b4a;
}

* { box-sizing: border-box; margin: 0; padding: 0; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.6;
    max-width: 1100px;
    margin: 0 auto;
    padding: 2rem 1.5rem;
}

a { color: var(--accent-blue); text-decoration: none; }
a:hover { text-decoration: underline; }
code, .mono {
    font-family: "SF Mono", "Fira Code", "Cascadia Code", Consolas, monospace;
    font-size: 0.9em;
    background: var(--bg-elevated);
    padding: 0.15em 0.4em;
    border-radius: 3px;
}

/* Hero */
.hero {
    text-align: center;
    padding: 2rem 0 1.5rem;
    border-bottom: 1px solid var(--border);
    margin-bottom: 2rem;
}
.hero h1 {
    font-size: 1.8rem;
    font-weight: 700;
    color: var(--text-bright);
    margin-bottom: 1rem;
}
.hero .subtitle {
    color: var(--text-dim);
    font-size: 0.95rem;
    margin-bottom: 1rem;
}

/* Badges */
.badge-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    justify-content: center;
    margin: 1rem 0;
}
.badge {
    display: inline-block;
    font-size: 0.75rem;
    font-weight: 600;
    padding: 0.3em 0.7em;
    border-radius: 4px;
    color: var(--text-bright);
    white-space: nowrap;
}

/* Phase nav */
.phase-nav {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
    margin: 1.5rem 0;
    padding: 0.75rem;
    background: var(--bg-surface);
    border-radius: 8px;
    border: 1px solid var(--border);
}
.phase-nav a {
    font-size: 0.85rem;
    padding: 0.35em 0.8em;
    border-radius: 4px;
    transition: background 0.15s;
}
.phase-nav a:hover {
    background: var(--bg-elevated);
    text-decoration: none;
}

/* Phase sections */
.phase {
    margin: 2rem 0;
    border: 1px solid var(--border);
    border-radius: 8px;
    overflow: hidden;
}
.phase-header {
    padding: 1rem 1.25rem;
    display: flex;
    align-items: center;
    gap: 0.75rem;
}
.phase-header h2 {
    font-size: 1.25rem;
    font-weight: 600;
    color: var(--text-bright);
    flex: 1;
}
.phase-body {
    padding: 0.75rem 1.25rem 1.25rem;
}

/* Metric cards */
.metric-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-bottom: 1rem;
}
.metric-card {
    background: var(--bg-elevated);
    border-radius: 6px;
    padding: 0.6rem 1rem;
    min-width: 120px;
    flex: 1;
}
.metric-card .label {
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-dim);
}
.metric-card .value {
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--text-bright);
}

/* Conversation bubbles */
.bubble {
    margin: 0.5rem 0;
    padding: 0.75rem 1rem;
    border-radius: 8px;
    max-width: 85%;
    line-height: 1.5;
}
.bubble-agent {
    background: #1e3a5f;
    border-left: 3px solid var(--accent-blue);
    margin-right: auto;
}
.bubble-user {
    background: #1a3a2a;
    border-left: 3px solid var(--accent-green);
    margin-left: auto;
}
.bubble .speaker {
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    margin-bottom: 0.25rem;
}
.bubble-agent .speaker { color: var(--accent-blue); }
.bubble-user .speaker { color: var(--accent-green); }

/* Cards (research, architect, finding) */
.card {
    background: var(--bg-card);
    border-radius: 8px;
    border: 1px solid var(--border);
    padding: 1rem 1.25rem;
    margin: 0.75rem 0;
}
.card-title {
    font-weight: 600;
    color: var(--text-bright);
    margin-bottom: 0.5rem;
}

/* Alert boxes */
.alert {
    padding: 0.75rem 1rem;
    border-radius: 6px;
    margin: 0.5rem 0;
    border-left: 4px solid;
}
.alert-warning {
    background: #3d2f1f;
    border-color: #d29922;
}
.alert-error {
    background: #3d1f1f;
    border-color: #f85149;
}
.alert-success {
    background: #1a3a2a;
    border-color: #3fb950;
}
.alert-info {
    background: #1e3a5f;
    border-color: #4184e4;
}

/* Status badges */
.status-badge {
    display: inline-block;
    font-size: 0.75rem;
    font-weight: 600;
    padding: 0.2em 0.6em;
    border-radius: 12px;
    text-transform: uppercase;
    letter-spacing: 0.03em;
}
.status-confirmed { background: #1a3a2a; color: #4aff7a; }
.status-impossible { background: #3d1f1f; color: #f85149; }
.status-fix-impossible { background: #3d2f1f; color: #d29922; }
.status-phase0 { background: #253253; color: #4af5ff; }

/* Lens pills */
.lens-pill {
    display: inline-block;
    font-size: 0.7rem;
    font-weight: 600;
    padding: 0.15em 0.5em;
    border-radius: 10px;
    margin-left: 0.4rem;
}

/* TDD summary table */
table {
    width: 100%;
    border-collapse: collapse;
    margin: 0.75rem 0;
    font-size: 0.9rem;
}
th, td {
    padding: 0.5rem 0.75rem;
    text-align: left;
    border-bottom: 1px solid var(--border);
}
th {
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-dim);
    font-weight: 600;
}

/* Collapsible */
details {
    margin: 0.5rem 0;
}
details > summary {
    cursor: pointer;
    padding: 0.4rem 0;
    font-weight: 500;
    color: var(--text-dim);
    list-style: none;
}
details > summary::before {
    content: "\\25B6\\00A0";
    font-size: 0.7em;
}
details[open] > summary::before {
    content: "\\25BC\\00A0";
}
details > .detail-body {
    padding: 0.5rem 0 0.5rem 1rem;
    border-left: 2px solid var(--border);
    margin: 0.25rem 0;
}

/* Session break */
.session-break {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin: 1.25rem 0;
    color: var(--accent-red);
    font-weight: 500;
}
.session-break::before,
.session-break::after {
    content: "";
    flex: 1;
    height: 1px;
    background: var(--accent-red);
    opacity: 0.4;
}

/* SVG timeline */
.timeline-container {
    margin: 1.5rem 0;
    overflow-x: auto;
}
.timeline-container svg {
    display: block;
    margin: 0 auto;
}

/* Footer */
footer {
    margin-top: 3rem;
    padding-top: 1.5rem;
    border-top: 1px solid var(--border);
    color: var(--text-dim);
    font-size: 0.85rem;
}
footer .cost-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 0.75rem;
    margin: 1rem 0;
}

/* Work plan */
.work-plan ol {
    padding-left: 1.25rem;
    margin: 0.5rem 0;
}
.work-plan li {
    margin: 0.3rem 0;
}
.work-plan .estimate {
    color: var(--text-dim);
    font-size: 0.85rem;
}

/* Action group */
.action-list {
    list-style: none;
    padding: 0;
    font-size: 0.85rem;
}
.action-list li {
    padding: 0.2rem 0;
    color: var(--text-dim);
}
.action-list li code {
    color: var(--text);
}

/* Audit pipeline table */
.pipeline-table {
    margin: 1rem 0;
}
.pipeline-table td:first-child {
    font-weight: 600;
}

/* Finding cards grid */
.findings-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 0.75rem;
    margin: 1rem 0;
}
.finding-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 1rem;
}
.finding-card .finding-id {
    font-family: "SF Mono", Consolas, monospace;
    font-size: 0.8rem;
    color: var(--text-dim);
}
.finding-card .finding-title {
    font-weight: 600;
    color: var(--text-bright);
    margin: 0.25rem 0;
}
.finding-card .construct {
    font-family: "SF Mono", Consolas, monospace;
    font-size: 0.85rem;
    color: var(--accent-teal);
}
.finding-card .file-path {
    font-family: "SF Mono", Consolas, monospace;
    font-size: 0.8rem;
    color: var(--text-dim);
}
.finding-card .fix-desc, .finding-card .impossibility-reason {
    margin-top: 0.5rem;
    font-size: 0.9rem;
    line-height: 1.4;
}

/* Lens summary */
.lens-summary {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin: 1rem 0;
}
.lens-group {
    background: var(--bg-card);
    border-radius: 8px;
    border: 1px solid var(--border);
    padding: 0.75rem 1rem;
    min-width: 150px;
}
.lens-group .lens-name {
    font-weight: 600;
    font-size: 0.85rem;
    margin-bottom: 0.25rem;
}
.lens-group .lens-count {
    font-size: 1.5rem;
    font-weight: 700;
    color: var(--text-bright);
}

/* View toggle */
.view-toggle {
    display: flex;
    gap: 0;
    margin: 1.5rem 0 0;
    justify-content: center;
}
.view-toggle button {
    padding: 0.5rem 1.5rem;
    font-size: 0.9rem;
    font-weight: 600;
    border: 1px solid var(--border);
    background: var(--bg-surface);
    color: var(--text-dim);
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
}
.view-toggle button:first-child { border-radius: 6px 0 0 6px; }
.view-toggle button:last-child { border-radius: 0 6px 6px 0; }
.view-toggle button.active {
    background: var(--accent-blue);
    color: var(--text-bright);
    border-color: var(--accent-blue);
}

/* Replay section */
.replay-container { display: none; margin: 1.5rem 0; }
.replay-container.visible { display: block; }
.replay-controls {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: 8px 8px 0 0;
    flex-wrap: wrap;
}
.replay-controls button {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 0.4rem 0.7rem;
    border-radius: 4px;
    cursor: pointer;
    font-size: 1rem;
    line-height: 1;
    transition: background 0.12s;
}
.replay-controls button:hover { background: var(--border); }
.replay-controls select {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    color: var(--text);
    padding: 0.35rem 0.5rem;
    border-radius: 4px;
    font-size: 0.85rem;
}
.replay-progress {
    color: var(--text-dim);
    font-size: 0.85rem;
    margin-left: auto;
    font-family: "SF Mono", Consolas, monospace;
}
.replay-timeline {
    display: flex;
    gap: 0;
    background: var(--bg-surface);
    border-left: 1px solid var(--border);
    border-right: 1px solid var(--border);
    overflow-x: auto;
    padding: 0.25rem 0.5rem;
}
.replay-timeline button {
    padding: 0.3rem 0.6rem;
    font-size: 0.75rem;
    font-weight: 600;
    border: none;
    background: transparent;
    cursor: pointer;
    white-space: nowrap;
    border-bottom: 2px solid transparent;
    transition: border-color 0.15s, color 0.15s;
}
.replay-timeline button:hover { border-bottom-color: var(--text-dim); }
.replay-timeline button.active { border-bottom-color: currentColor; }
.replay-terminal {
    background: #0d1117;
    border: 1px solid #30363d;
    border-radius: 0 0 8px 8px;
    padding: 1rem;
    font-family: 'SF Mono', 'Cascadia Code', 'JetBrains Mono', Consolas, monospace;
    font-size: 13px;
    line-height: 1.5;
    max-height: 600px;
    overflow-y: auto;
    scroll-behavior: smooth;
    min-height: 200px;
}
.replay-terminal .r-user {
    color: #58a6ff;
    margin: 0.5rem 0;
    padding: 0.4rem 0.6rem;
    border-left: 2px solid #58a6ff;
}
.replay-terminal .r-user .r-label {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    opacity: 0.7;
    display: block;
    margin-bottom: 0.15rem;
}
.replay-terminal .r-assistant {
    color: #e6edf3;
    margin: 0.5rem 0;
    padding: 0.4rem 0.6rem;
    border-left: 2px solid #484f58;
}
.replay-terminal .r-assistant .r-label {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: #8b949e;
    display: block;
    margin-bottom: 0.15rem;
}
.replay-terminal .r-tool {
    color: #7ee787;
    font-size: 12px;
    opacity: 0.7;
    margin: 0.25rem 0 0.25rem 1rem;
    padding: 0.2rem 0.5rem;
    cursor: pointer;
}
.replay-terminal .r-tool::before { content: "\\25B6 "; font-size: 0.7em; }
.replay-terminal .r-tool.expanded::before { content: "\\25BC "; }
.replay-terminal .r-tool .r-tool-detail {
    display: none;
    margin-top: 0.25rem;
    padding: 0.3rem 0.5rem;
    background: #161b22;
    border-radius: 3px;
    color: #8b949e;
    white-space: pre-wrap;
}
.replay-terminal .r-tool.expanded .r-tool-detail { display: block; }
.replay-terminal .r-phase-marker {
    color: #f0883e;
    font-weight: bold;
    border-top: 1px solid #30363d;
    padding-top: 0.5rem;
    margin-top: 1rem;
    font-size: 14px;
}
.replay-terminal .r-escalation {
    color: #f85149;
    margin: 0.5rem 0;
    padding: 0.4rem 0.6rem;
    border-left: 2px solid #f85149;
    background: #1a0f0f;
}
.replay-step-enter {
    animation: stepFadeIn 0.2s ease-out;
}
@keyframes stepFadeIn {
    from { opacity: 0; transform: translateY(4px); }
    to { opacity: 1; transform: translateY(0); }
}

/* ===== Dashboard additions ===== */

/* Hero big-number cards */
.hero-stats {
    display: flex;
    flex-wrap: wrap;
    gap: 1rem;
    justify-content: center;
    margin: 1.5rem 0 1rem;
}
.hero-stat {
    background: var(--bg-elevated);
    border-radius: 10px;
    padding: 1rem 1.5rem;
    min-width: 130px;
    text-align: center;
}
.hero-stat .big-number {
    font-size: 2.2rem;
    font-weight: 800;
    line-height: 1.1;
}
.hero-stat .big-label {
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-dim);
    margin-top: 0.25rem;
}
.hero-tagline {
    font-size: 1.15rem;
    color: var(--text-dim);
    margin: 0.75rem 0;
}
.hero-impact {
    max-width: 700px;
    margin: 1rem auto 0;
    padding: 0.8rem 1.2rem;
    background: var(--bg-card);
    border-left: 3px solid var(--accent-amber);
    border-radius: 4px;
    font-size: 0.95rem;
    color: var(--text);
    line-height: 1.5;
}

/* Executive dashboard */
.exec-dashboard {
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 1.25rem 1.5rem;
    margin: 1.5rem 0;
}
.exec-dashboard h3 {
    font-size: 1rem;
    font-weight: 700;
    color: var(--text-bright);
    margin-bottom: 0.75rem;
    border-bottom: 1px solid var(--border);
    padding-bottom: 0.5rem;
}

/* Error category list */
.error-categories {
    list-style: none;
    padding: 0;
    margin: 0.75rem 0;
}
.error-categories li {
    padding: 0.4rem 0.6rem;
    margin: 0.3rem 0;
    border-radius: 6px;
    font-size: 0.9rem;
    display: flex;
    align-items: center;
    gap: 0.6rem;
}
.error-categories .cat-count {
    font-weight: 700;
    font-size: 1.1rem;
    min-width: 2ch;
    text-align: center;
}
.error-categories .cat-label {
    font-weight: 600;
    color: var(--text-bright);
}
.error-categories .cat-detail {
    color: var(--text-dim);
    font-size: 0.8rem;
}

/* Category colors */
.cat-concurrency { background: #3d1f1f; border-left: 3px solid var(--accent-red); }
.cat-resource { background: #1a3a2a; border-left: 3px solid var(--accent-green); }
.cat-contract { background: #1e3a5f; border-left: 3px solid var(--accent-blue); }
.cat-state { background: #2f1f3d; border-left: 3px solid var(--accent-purple); }
.cat-transformation { background: #3d2f1f; border-left: 3px solid var(--accent-amber); }
.cat-lifecycle { background: #1a3a3a; border-left: 3px solid var(--accent-teal); }
.cat-other { background: var(--bg-elevated); border-left: 3px solid var(--text-dim); }

/* Severity indicators */
.severity-dot {
    display: inline-block;
    width: 10px;
    height: 10px;
    border-radius: 50%;
    margin-right: 0.3rem;
}
.severity-critical { background: #f85149; }
.severity-high { background: #f0883e; }
.severity-medium { background: #d29922; }
.severity-low { background: #6e7681; }

.severity-row {
    display: flex;
    flex-wrap: wrap;
    gap: 1rem;
    margin: 0.75rem 0;
}
.severity-item {
    display: flex;
    align-items: center;
    gap: 0.3rem;
    font-size: 0.85rem;
}
.severity-item .sev-count {
    font-weight: 700;
    color: var(--text-bright);
}

/* Notable findings */
.notable-findings {
    margin: 0.75rem 0;
}
.notable-finding {
    display: flex;
    align-items: flex-start;
    gap: 0.5rem;
    padding: 0.4rem 0;
    border-bottom: 1px solid var(--border);
}
.notable-finding:last-child { border-bottom: none; }
.notable-rank {
    font-weight: 800;
    font-size: 1.1rem;
    color: var(--accent-amber);
    min-width: 1.5rem;
}
.notable-title {
    font-weight: 600;
    color: var(--text-bright);
    font-size: 0.9rem;
}
.notable-desc {
    color: var(--text-dim);
    font-size: 0.8rem;
}

/* Progress bar (spec coverage) */
.progress-bar-container {
    margin: 0.5rem 0;
}
.progress-bar-label {
    font-size: 0.85rem;
    color: var(--text-dim);
    margin-bottom: 0.25rem;
}
.progress-bar-track {
    background: var(--bg-elevated);
    border-radius: 6px;
    height: 20px;
    overflow: hidden;
    position: relative;
}
.progress-bar-fill {
    height: 100%;
    border-radius: 6px;
    transition: width 0.3s;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 0.7rem;
    font-weight: 700;
    color: var(--text-bright);
    min-width: 2rem;
}

/* Cluster grid */
.cluster-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 0.6rem;
    margin: 0.75rem 0;
}
.cluster-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 0.6rem 0.8rem;
}
.cluster-name {
    font-weight: 600;
    font-size: 0.85rem;
    color: var(--text-bright);
    margin-bottom: 0.3rem;
}
.cluster-bar {
    display: flex;
    align-items: center;
    gap: 0.5rem;
}
.cluster-bar .progress-bar-track {
    flex: 1;
    height: 14px;
}
.cluster-bar .cluster-count {
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--text-dim);
    white-space: nowrap;
}

/* Domain lens pills row */
.lens-pills-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin: 0.75rem 0;
}
.lens-pill-lg {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    font-size: 0.8rem;
    font-weight: 600;
    padding: 0.3em 0.7em;
    border-radius: 14px;
}
.lens-pill-lg .pill-count {
    font-weight: 800;
    font-size: 0.9rem;
}

/* Follow-ups section */
.follow-ups {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin: 0.75rem 0;
}
.follow-up-chip {
    background: var(--bg-elevated);
    border-radius: 4px;
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
    color: var(--text-dim);
}

/* Compact finding card (collapsed) */
.finding-compact {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
}
.finding-compact summary {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
}
.finding-compact summary::before { content: none; }
.finding-compact[open] summary::before { content: none; }
.finding-expand-icon::before { content: "\\25B6\\00A0"; font-size: 0.65em; color: var(--text-dim); }
.finding-compact[open] .finding-expand-icon::before { content: "\\25BC\\00A0"; }

/* Phase compact timeline bar (feature) */
.phase-timeline-bar {
    display: flex;
    border-radius: 6px;
    overflow: hidden;
    height: 28px;
    margin: 0.75rem 0;
}
.phase-timeline-segment {
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 0.7rem;
    font-weight: 600;
    color: var(--text-bright);
    overflow: hidden;
    white-space: nowrap;
    min-width: 2px;
}

/* Feature dashboard */
.feature-dashboard {
    background: var(--bg-surface);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 1.25rem 1.5rem;
    margin: 1.5rem 0;
}
.feature-dashboard h3 {
    font-size: 1rem;
    font-weight: 700;
    color: var(--text-bright);
    margin-bottom: 0.75rem;
}
.dashboard-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-bottom: 0.75rem;
}
"""


# ---------------------------------------------------------------------------
# SVG Timeline
# ---------------------------------------------------------------------------

def _render_timeline_svg(story: Story) -> str:
    """Generate an SVG horizontal timeline showing phase bars and session markers."""
    if not story.phases:
        return ""

    total_ms = story.duration_ms or sum(p.duration_ms for p in story.phases) or 1
    width = 900
    bar_height = 28
    top_margin = 30
    label_margin = 20
    session_marker_height = bar_height + 16
    svg_height = top_margin + bar_height + label_margin + 30

    parts: list[str] = []
    parts.append(f'<svg viewBox="0 0 {width} {svg_height}" '
                 f'width="{width}" height="{svg_height}" '
                 f'xmlns="http://www.w3.org/2000/svg" '
                 f'style="font-family: -apple-system, sans-serif;">')

    # Background track
    parts.append(f'  <rect x="0" y="{top_margin}" width="{width}" '
                 f'height="{bar_height}" rx="4" fill="#253253" />')

    # Phase bars
    cumulative_ms = 0
    for i, phase in enumerate(story.phases):
        stage = phase.data.get("stage", "")
        color = PHASE_COLORS.get(stage, "#4a9eff")
        phase_ms = max(phase.duration_ms, total_ms // 100)  # min 1% for visibility
        x = (cumulative_ms / total_ms) * width
        w = max((phase_ms / total_ms) * width, 2)

        parts.append(f'  <rect x="{x:.1f}" y="{top_margin}" '
                     f'width="{w:.1f}" height="{bar_height}" '
                     f'rx="3" fill="{color}" opacity="0.85" />')

        # Label if wide enough
        if w > 50:
            label = stage.title()
            tx = x + w / 2
            ty = top_margin + bar_height / 2 + 4
            parts.append(f'  <text x="{tx:.1f}" y="{ty:.1f}" '
                         f'text-anchor="middle" font-size="11" '
                         f'font-weight="600" fill="#fff">{_esc(label)}</text>')

        # Bottom label
        lx = x + w / 2
        ly = top_margin + bar_height + label_margin
        if w > 30:
            dur_label = format_duration(phase.duration_ms)
            parts.append(f'  <text x="{lx:.1f}" y="{ly:.1f}" '
                         f'text-anchor="middle" font-size="9" '
                         f'fill="#8892a0">{_esc(dur_label)}</text>')

        cumulative_ms += phase_ms

    # Session boundary markers (vertical dashed lines)
    # Walk phases looking for session_break children
    cum = 0
    for phase in story.phases:
        for child in phase.children:
            if child.node_type == NodeType.SESSION_BREAK:
                sx = (cum / total_ms) * width
                parts.append(f'  <line x1="{sx:.1f}" y1="{top_margin - 8}" '
                             f'x2="{sx:.1f}" y2="{top_margin + session_marker_height}" '
                             f'stroke="#f85149" stroke-width="1.5" '
                             f'stroke-dasharray="4,3" opacity="0.7" />')
        cum += max(phase.duration_ms, total_ms // 100)

    # Total duration annotation
    dur_text = format_duration(story.duration_ms)
    parts.append(f'  <text x="{width / 2}" y="16" text-anchor="middle" '
                 f'font-size="12" fill="#8892a0">Total: {_esc(dur_text)}</text>')

    parts.append('</svg>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Render context
# ---------------------------------------------------------------------------

class RenderContext:
    """Carries state across render calls within a single story."""

    def __init__(self, story: Story):
        self.story = story
        self.current_phase = ""
        self.phase_index = 0
        self.test_total_passed = 0
        self.test_total_failed = 0


# ---------------------------------------------------------------------------
# HTML helpers
# ---------------------------------------------------------------------------

def _metric_card(label: str, value: str) -> str:
    return (f'<div class="metric-card">'
            f'<div class="label">{_esc(label)}</div>'
            f'<div class="value">{_esc(value)}</div>'
            f'</div>')


def _status_badge(status: str) -> str:
    """Render an audit finding status badge."""
    s = status.upper().replace("_", " ")
    if "CONFIRMED" in s or s == "FIXED":
        return f'<span class="status-badge status-confirmed">{_esc(s)}</span>'
    if "FIX IMPOSSIBLE" in s:
        return f'<span class="status-badge status-fix-impossible">{_esc(s)}</span>'
    if "IMPOSSIBLE" in s:
        return f'<span class="status-badge status-impossible">{_esc(s)}</span>'
    return f'<span class="status-badge">{_esc(s)}</span>'


def _lens_pill(lens: str) -> str:
    """Render a colored lens pill."""
    color = LENS_COLORS.get(lens, "#8892a0")
    return (f'<span class="lens-pill" '
            f'style="background:{color}22;color:{color};border:1px solid {color}44">'
            f'{_esc(lens)}</span>')


def _truncate(text: str, max_chars: int = 300) -> tuple[str, bool]:
    """Truncate at word boundary."""
    if len(text) <= max_chars:
        return text, False
    cut = text.rfind(" ", 0, max_chars)
    if cut == -1:
        cut = max_chars
    return text[:cut], True


# ---------------------------------------------------------------------------
# Confirmation filter (same as markdown renderer)
# ---------------------------------------------------------------------------

_CONFIRMATION_RESPONSES = frozenset({
    "yes", "y", "no", "n", "ok", "okay", "sure", "continue",
    "create", "auto", "stop", "done", "skip", "proceed",
})


def _is_confirmation(text: str) -> bool:
    stripped = text.strip().rstrip(".!").lower()
    return stripped in _CONFIRMATION_RESPONSES


# TDD phases
_TDD_PHASES = frozenset({
    "testing", "implementation", "refactor", "coordination", "resume",
})


# ---------------------------------------------------------------------------
# Node renderers — one function per node type
# ---------------------------------------------------------------------------

def _render_conversation(node: Node, ctx: RenderContext) -> str:
    """Render a conversation as chat bubbles."""
    parts: list[str] = []
    exchanges = node.children

    is_scoping = ctx.current_phase == "scoping"

    bubbles: list[str] = []
    for child in exchanges:
        q = child.data.get("question", "")
        a = child.data.get("answer", "")
        if q and q.strip():
            q_short, q_trunc = _truncate(q)
            if q_trunc:
                bubbles.append(
                    f'<details class="bubble bubble-agent">'
                    f'<summary><span class="speaker">vallorcine</span> '
                    f'{_esc(q_short)}...</summary>'
                    f'<div class="detail-body">{_esc(q)}</div></details>'
                )
            else:
                bubbles.append(
                    f'<div class="bubble bubble-agent">'
                    f'<div class="speaker">vallorcine</div>'
                    f'{_esc(q)}</div>'
                )
        if a and not _is_confirmation(a):
            a_short, a_trunc = _truncate(a)
            if a_trunc:
                bubbles.append(
                    f'<details class="bubble bubble-user">'
                    f'<summary><span class="speaker">User</span> '
                    f'{_esc(a_short)}...</summary>'
                    f'<div class="detail-body">{_esc(a)}</div></details>'
                )
            else:
                bubbles.append(
                    f'<div class="bubble bubble-user">'
                    f'<div class="speaker">User</div>'
                    f'{_esc(a)}</div>'
                )

    if not bubbles:
        return ""

    content = "\n".join(bubbles)

    if is_scoping:
        return content

    count = node.data.get("exchange_count", len(exchanges))
    return (f'<details><summary>Conversation &mdash; {count} exchanges</summary>'
            f'<div class="detail-body">{content}</div></details>')


def _render_exchange(node: Node, ctx: RenderContext) -> str:
    """Render a standalone exchange."""
    answer = node.data.get("answer", node.content or "")
    if not answer or _is_confirmation(answer):
        return ""
    a_short, a_trunc = _truncate(answer)
    if a_trunc:
        return (f'<details class="bubble bubble-user">'
                f'<summary><span class="speaker">User</span> '
                f'{_esc(a_short)}...</summary>'
                f'<div class="detail-body">{_esc(answer)}</div></details>')
    return (f'<div class="bubble bubble-user">'
            f'<div class="speaker">User</div>'
            f'{_esc(answer)}</div>')


def _render_tdd_cycle(node: Node, ctx: RenderContext) -> str:
    """Render an interesting TDD cycle as a highlighted card."""
    if not node.interesting:
        return ""  # routine cycles handled by summary table

    name = _esc(node.data.get("unit_name", "Work unit"))
    duration = format_duration(node.duration_ms or node.data.get("duration_ms", 0))
    summary = node.data.get("summary", "")
    types = node.data.get("interesting_types", [])
    passed = node.data.get("test_passed", 0)
    failed = node.data.get("test_failed", 0)

    cls = "alert-warning" if ("escalation" in types or failed > 0) else "alert-info"

    lines = [f'<div class="alert {cls}">']
    lines.append(f'<strong>{name}</strong> &mdash; {_esc(duration)}')
    if failed:
        lines.append(f'<br>Tests: {passed} passed, {failed} failed')
    elif passed:
        lines.append(f'<br>Tests: {passed} passed')
    if types:
        lines.append(f'<br>Signals: {_esc(", ".join(types))}')
    if summary:
        parts = [p.strip() for p in summary.split(" — ")]
        for part in parts[1:]:
            lines.append(f'<br>&bull; {_esc(part)}')
    lines.append('</div>')
    return "\n".join(lines)


def _render_tdd_summary_table(cycles: list[Node], ctx: RenderContext) -> str:
    """Render routine TDD cycles as a compact table."""
    if not cycles:
        return ""

    story_total = (ctx.story.tokens.billable_input + ctx.story.tokens.output) or 1

    rows: list[str] = []
    for c in cycles:
        name = _esc(c.data.get("unit_name", "\u2014"))
        duration = format_duration(c.data.get("duration_ms", 0))
        passed = c.data.get("test_passed", 0)
        files = len(c.data.get("files_written", []))
        tests = f'<span style="color:var(--accent-green)">&#x2705; {passed}</span>' if passed else "\u2014"
        wu_total = c.tokens.billable_input + c.tokens.output
        pct = (wu_total / story_total) * 100

        rows.append(f'<tr><td>{name}</td><td>{_esc(duration)}</td>'
                    f'<td>{tests}</td><td>{files}</td><td>{pct:.0f}%</td></tr>')

    return (
        '<table>'
        '<thead><tr><th>Work Unit</th><th>Duration</th>'
        '<th>Tests</th><th>Files</th><th>% of total</th></tr></thead>'
        '<tbody>' + "\n".join(rows) + '</tbody></table>'
    )


def _render_brief(node: Node, ctx: RenderContext) -> str:
    """Render a feature brief as a blockquote card."""
    content = node.content or node.data.get("content", "")
    confirmed = node.data.get("confirmed", None)

    parts = ['<div class="card">']
    parts.append('<div class="card-title">Feature Brief</div>')
    if confirmed is True:
        parts.append('<span class="status-badge status-confirmed">Confirmed</span>')
    elif confirmed is False:
        parts.append('<span class="status-badge status-impossible">Rejected</span>')
    if content:
        short, trunc = _truncate(content, 500)
        if trunc:
            parts.append(f'<details><summary><blockquote>{_esc(short)}...</blockquote></summary>'
                         f'<blockquote>{_esc(content)}</blockquote></details>')
        else:
            parts.append(f'<blockquote>{_esc(content)}</blockquote>')
    parts.append('</div>')
    return "\n".join(parts)


def _render_research(node: Node, ctx: RenderContext) -> str:
    """Render a research card."""
    topic = _esc(node.data.get("topic", ""))
    subject = _esc(node.data.get("subject", ""))
    kb_path = _esc(node.data.get("kb_path", ""))
    findings = node.data.get("findings_summary", "")

    parts = ['<div class="card">']
    title = "&#x1F4DA; Research"
    if topic:
        title += f": {topic}"
    parts.append(f'<div class="card-title">{title}</div>')
    if subject:
        parts.append(f'<div>Subject: {subject}</div>')
    if kb_path:
        parts.append(f'<div>KB: <code>{kb_path}</code></div>')
    if findings:
        short, trunc = _truncate(findings, 400)
        if trunc:
            parts.append(f'<details><summary>{_esc(short)}...</summary>'
                         f'<div class="detail-body">{_esc(findings)}</div></details>')
        else:
            parts.append(f'<div>{_esc(findings)}</div>')
    parts.append('</div>')
    return "\n".join(parts)


def _render_architect(node: Node, ctx: RenderContext) -> str:
    """Render an architecture decision card."""
    question = _esc(node.data.get("question", ""))
    decision = _esc(node.data.get("decision", ""))
    rejected = node.data.get("rejected", [])
    adr_path = _esc(node.data.get("adr_path", ""))

    parts = ['<div class="card">']
    parts.append('<div class="card-title">&#x1F3DB; Architecture Decision</div>')
    if question:
        parts.append(f'<div><strong>Question:</strong> {question}</div>')
    if decision:
        parts.append(f'<div><strong>Decision:</strong> {decision}</div>')
    if rejected:
        struck = ", ".join(f"<del>{_esc(r)}</del>" for r in rejected)
        parts.append(f'<div><strong>Rejected:</strong> {struck}</div>')
    if adr_path:
        parts.append(f'<div><strong>ADR:</strong> <code>{adr_path}</code></div>')
    parts.append('</div>')
    return "\n".join(parts)


def _render_work_plan(node: Node, ctx: RenderContext) -> str:
    """Render a work plan as a numbered list."""
    units = node.data.get("units", [])
    if not units and node.content:
        return (f'<div class="card work-plan">'
                f'<div class="card-title">Work Plan</div>'
                f'<div>{_esc(node.content)}</div></div>')

    parts = ['<div class="card work-plan">']
    parts.append('<div class="card-title">Work Plan</div>')
    parts.append('<ol>')
    for u in units:
        name = _esc(u.get("name", u.get("unit_name", "")))
        estimate = u.get("estimate", "")
        est_html = f' <span class="estimate">({_esc(estimate)})</span>' if estimate else ""
        parts.append(f'<li>{name}{est_html}</li>')
    parts.append('</ol>')
    parts.append('</div>')
    return "\n".join(parts)


def _render_stage_transition(node: Node, ctx: RenderContext) -> str:
    """Render a stage transition command."""
    cmd = node.data.get("command", "")
    if not cmd:
        return ""
    args = _esc(node.data.get("args", ""))
    display = f'<code>{_esc(cmd)}</code>'
    if args:
        display += f" {args}"
    return f'<div style="color:var(--text-dim);margin:0.3rem 0"><strong>&rarr;</strong> {display}</div>'


def _render_session_break(node: Node, ctx: RenderContext) -> str:
    """Render a crash/recovery boundary."""
    gap = format_duration(node.data.get("gap_duration_ms", 0))
    return (f'<div class="session-break">'
            f'&#x26A0;&#xFE0F; Session crashed. Resumed {_esc(gap)} later.</div>')


def _render_test_result(node: Node, ctx: RenderContext) -> str:
    """Render a test result badge."""
    if ctx.current_phase not in _TDD_PHASES:
        return ""

    passed = node.data.get("passed", 0)
    failed = node.data.get("failed", 0)
    runs = node.data.get("run_count", 1)

    ctx.test_total_passed += passed
    ctx.test_total_failed += failed

    if failed:
        return (f'<div class="alert alert-warning">'
                f'&#x26A0;&#xFE0F; Tests: {passed} passed, {failed} failed '
                f'({runs} runs)</div>')
    return (f'<div style="color:var(--accent-green);margin:0.3rem 0">'
            f'&#x2705; Tests: {passed} passed ({runs} runs)</div>')


def _render_escalation(node: Node, ctx: RenderContext) -> str:
    """Render an escalation as a warning alert."""
    content = node.content or ""
    body = ""
    if content.strip():
        short, trunc = _truncate(content, 400)
        if trunc:
            body = (f'<details><summary>{_esc(short)}...</summary>'
                    f'<div class="detail-body">{_esc(content)}</div></details>')
        else:
            body = f'<div>{_esc(content)}</div>'

    return (f'<div class="alert alert-warning">'
            f'&#x26A0;&#xFE0F; <strong>Escalation</strong>{body}</div>')


def _render_action_group(node: Node, ctx: RenderContext) -> str:
    """Render file operations or subagent action summary."""
    # Subagent-style
    if "unit_name" in node.data:
        name = _esc(node.data.get("unit_name", "Action"))
        duration = format_duration(node.duration_ms or node.data.get("duration_ms", 0))
        summary = node.data.get("summary", "")
        detail = ""
        if summary:
            parts = [p.strip() for p in summary.split(" — ")]
            detail = " &mdash; ".join(_esc(p) for p in parts[1:]) if len(parts) > 1 else ""
        suffix = f" &mdash; {detail}" if detail else ""
        return (f'<div style="color:var(--text-dim);margin:0.3rem 0">'
                f'<strong>{name}</strong> <em>({_esc(duration)})</em>{suffix}</div>')

    # Tool call batch
    actions = node.data.get("actions", [])
    if not actions:
        return ""

    if len(actions) <= 3:
        items = [_esc(a.get("summary", f"{a.get('verb', '?')} {a.get('target', '')}"))
                 for a in actions]
        return (f'<div style="color:var(--text-dim);margin:0.3rem 0">'
                f'&#x1F4DD; <em>{"; ".join(items)}</em></div>')

    items = "\n".join(
        f'<li><code>{_esc(a.get("verb", ""))}</code> {_esc(a.get("target", ""))}</li>'
        for a in actions
    )
    return (f'<details><summary>&#x1F4DD; <strong>{len(actions)} file operations'
            f'</strong></summary>'
            f'<ul class="action-list">{items}</ul></details>')


def _render_prose(node: Node, ctx: RenderContext) -> str:
    """Render agent prose."""
    content = node.content or ""
    if not content.strip():
        return ""

    if node.interesting:
        short, trunc = _truncate(content, 600)
        if trunc:
            return (f'<div class="card">{_esc(short)}...'
                    f'<details><summary>Show full</summary>'
                    f'<div class="detail-body">{_esc(content)}</div></details></div>')
        return f'<div class="card">{_esc(content)}</div>'

    lines = content.split("\n")
    if len(lines) <= 5:
        return f'<div style="margin:0.3rem 0;color:var(--text-dim)">{_esc(content)}</div>'

    summary = _esc(lines[0][:120])
    return (f'<details><summary>{summary}...</summary>'
            f'<div class="detail-body">{_esc(content)}</div></details>')


def _render_metric(node: Node, ctx: RenderContext) -> str:
    """Render a computed metric."""
    label = node.data.get("label", "")
    value = node.data.get("value", node.content or "")
    if not label and not value:
        return ""
    return _metric_card(str(label), str(value))


def _render_pr(node: Node, ctx: RenderContext) -> str:
    """Render PR creation."""
    url = node.data.get("url", "")
    title = _esc(node.data.get("title", "Pull Request"))
    parts = ['<div class="card">']
    parts.append(f'<div class="card-title">&#x1F4CB; {title}</div>')
    if url:
        parts.append(f'<div><a href="{_esc(url)}">{_esc(url)}</a></div>')
    if node.content:
        parts.append(f'<div style="margin-top:0.5rem">{_esc(node.content)}</div>')
    parts.append('</div>')
    return "\n".join(parts)


def _render_retro(node: Node, ctx: RenderContext) -> str:
    """Render retrospective summary."""
    content = node.content or ""
    if not content.strip():
        return ""
    short, trunc = _truncate(content, 500)
    parts = ['<div class="card">']
    parts.append('<div class="card-title">&#x1F504; Retrospective</div>')
    if trunc:
        parts.append(f'<details><summary>{_esc(short)}...</summary>'
                     f'<div class="detail-body">{_esc(content)}</div></details>')
    else:
        parts.append(f'<div>{_esc(content)}</div>')
    parts.append('</div>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Audit classification helpers
# ---------------------------------------------------------------------------

# Maps lens/concern keywords to a category for grouping
_CATEGORY_MAP = {
    "concurrency": "concurrency",
    "race": "concurrency",
    "thread": "concurrency",
    "synchron": "concurrency",
    "lock": "concurrency",
    "atomic": "concurrency",
    "resource": "resource",
    "leak": "resource",
    "close": "resource",
    "cleanup": "resource",
    "dispose": "resource",
    "contract": "contract",
    "null": "contract",
    "validation": "contract",
    "guard": "contract",
    "boundary": "contract",
    "input": "contract",
    "precondition": "contract",
    "state": "state",
    "rollback": "state",
    "lifecycle": "lifecycle",
    "init": "lifecycle",
    "shutdown": "lifecycle",
    "transform": "transformation",
    "data-flow": "transformation",
    "conversion": "transformation",
    "encoding": "transformation",
}

_CATEGORY_CSS = {
    "concurrency": "cat-concurrency",
    "resource": "cat-resource",
    "contract": "cat-contract",
    "state": "cat-state",
    "transformation": "cat-transformation",
    "lifecycle": "cat-lifecycle",
}

_CATEGORY_LABELS = {
    "concurrency": "Concurrency",
    "resource": "Resource Management",
    "contract": "Contract Violations",
    "state": "State Management",
    "transformation": "Data Transformation",
    "lifecycle": "Lifecycle",
}

# Severity inference from finding descriptions / concerns
_SEVERITY_CRITICAL_KW = {"corruption", "data loss", "security", "injection", "overflow"}
_SEVERITY_HIGH_KW = {"crash", "exception", "deadlock", "race condition", "null pointer",
                      "oom", "infinite", "denial"}
_SEVERITY_MEDIUM_KW = {"incorrect", "wrong", "silent", "ignored", "missing guard",
                       "contract violation", "validation"}


def _classify_category(finding: Node) -> str:
    """Classify a finding into an error category."""
    lens = (finding.data.get("lens", "") or "").lower()
    concern = (finding.data.get("concern", "") or "").lower()
    title = (finding.data.get("title", "") or "").lower()
    text = f"{lens} {concern} {title}"
    for keyword, cat in _CATEGORY_MAP.items():
        if keyword in text:
            return cat
    return "other"


def _classify_severity(finding: Node) -> str:
    """Infer severity from finding description keywords."""
    title = (finding.data.get("title", "") or "").lower()
    concern = (finding.data.get("concern", "") or "").lower()
    fix = (finding.data.get("fix", "") or "").lower()
    text = f"{title} {concern} {fix}"
    for kw in _SEVERITY_CRITICAL_KW:
        if kw in text:
            return "critical"
    for kw in _SEVERITY_HIGH_KW:
        if kw in text:
            return "high"
    for kw in _SEVERITY_MEDIUM_KW:
        if kw in text:
            return "medium"
    return "low"


# Priority for notable finding selection
_NOTABLE_PRIORITY = {
    "concurrency": 0,
    "resource": 1,
    "state": 2,
    "transformation": 3,
    "contract": 4,
    "lifecycle": 5,
    "other": 6,
}


def _select_notable(findings: list[Node], limit: int = 3) -> list[Node]:
    """Pick the most interesting/impactful confirmed findings."""
    confirmed = [f for f in findings
                 if "CONFIRMED" in (f.data.get("status", "") or "").upper()]
    if not confirmed:
        confirmed = findings[:limit]

    def _priority(f: Node) -> tuple:
        cat = _classify_category(f)
        sev = _classify_severity(f)
        sev_rank = {"critical": 0, "high": 1, "medium": 2, "low": 3}.get(sev, 3)
        cat_rank = _NOTABLE_PRIORITY.get(cat, 6)
        return (sev_rank, cat_rank)

    confirmed.sort(key=_priority)
    return confirmed[:limit]


def _generate_impact_summary(confirmed: list[Node]) -> str:
    """Generate a 2-3 line non-technical summary of what risks were prevented.

    Maps technical bug categories to business-impact language:
    - key/encryption/cipher → "security vulnerabilities that could expose sensitive data"
    - corruption/truncation/overflow → "data corruption that could cause silent data loss"
    - race/concurrent/thread → "concurrency issues that cause failures under load"
    - null/crash/exception → "crash conditions affecting system reliability"
    - validation/guard/check → "input validation gaps that could cause unexpected behavior"
    """
    impacts = set()
    for f in confirmed:
        text = (f.data.get("title", "") + " " +
                f.data.get("fix", "") + " " +
                f.data.get("fix_description", "") + " " +
                f.data.get("fix_summary", "") + " " +
                f.data.get("concern", "")).lower()

        if any(k in text for k in ("key", "encrypt", "cipher", "secret",
                                    "zero", "wipe", "plaintext", "nonce",
                                    "gcm", "siv", "aes", "hmac")):
            impacts.add("security vulnerabilities that could expose encryption keys or sensitive data")
        if any(k in text for k in ("corrupt", "truncat", "overflow", "silent",
                                    "data loss", "wrong value")):
            impacts.add("data corruption that could cause silent data loss")
        if any(k in text for k in ("race", "concurrent", "thread", "lock",
                                    "atomic", "volatile")):
            impacts.add("concurrency issues that cause failures under load")
        if any(k in text for k in ("null", "crash", "exception", "throw",
                                    "leak", "close", "resource")):
            impacts.add("crash conditions and resource leaks affecting reliability")
        if any(k in text for k in ("validat", "guard", "check", "bound",
                                    "assert", "range")):
            impacts.add("missing input validation that could cause unexpected behavior")

    if not impacts:
        return ""

    # Take top 3 most important impacts (security > corruption > concurrency > crash > validation)
    priority = [
        "security vulnerabilities that could expose encryption keys or sensitive data",
        "data corruption that could cause silent data loss",
        "concurrency issues that cause failures under load",
        "crash conditions and resource leaks affecting reliability",
        "missing input validation that could cause unexpected behavior",
    ]
    ordered = [i for i in priority if i in impacts][:3]

    if len(ordered) == 1:
        return f"This audit prevented {ordered[0]}."
    elif len(ordered) == 2:
        return f"This audit prevented {ordered[0]} and {ordered[1]}."
    else:
        return f"This audit prevented {ordered[0]}, {ordered[1]}, and {ordered[2]}."


# ---------------------------------------------------------------------------
# Audit-specific renderers
# ---------------------------------------------------------------------------

def _render_audit_cycle(node: Node, ctx: RenderContext) -> str:
    """Render an audit cycle as a compact section with grouped findings."""
    parts: list[str] = []
    stage = _esc(node.data.get("stage", ""))
    target = _esc(node.data.get("target", ""))

    # Collect findings from children
    cycle_findings = [c for c in node.children
                      if c.node_type == NodeType.AUDIT_FINDING]
    confirmed = [f for f in cycle_findings
                 if "CONFIRMED" in (f.data.get("status", "") or "").upper()]
    impossible = [f for f in cycle_findings
                  if "IMPOSSIBLE" in (f.data.get("status", "") or "").upper()]

    title = "Audit Cycle"
    if stage:
        title += f": {stage}"
    if target:
        title += f" &mdash; {target}"

    parts.append(f'<div class="card">')
    parts.append(f'<div class="card-title">&#x1F6E1; {title}</div>')

    # Compact metric row with cost/bug
    metrics = []
    if cycle_findings:
        metrics.append(_metric_card("Findings", str(len(cycle_findings))))
    if confirmed:
        metrics.append(_metric_card("Fixed", str(len(confirmed))))
    if impossible:
        metrics.append(_metric_card("Impossible", str(len(impossible))))
    if node.duration_ms:
        metrics.append(_metric_card("Duration", format_duration(node.duration_ms)))
    cost = _cost_value(node.tokens)
    if cost >= 0.01:
        metrics.append(_metric_card("Cost", f"${cost:.2f}"))
        if len(confirmed) > 0:
            cpb = cost / len(confirmed)
            metrics.append(_metric_card("Cost/Bug", f"${cpb:.2f}"))
    if metrics:
        parts.append(f'<div class="metric-row">{"".join(metrics)}</div>')

    # Group findings by category
    if cycle_findings:
        by_cat: dict[str, list[Node]] = {}
        for f in cycle_findings:
            cat = _classify_category(f)
            by_cat.setdefault(cat, []).append(f)

        parts.append('<ul class="error-categories">')
        for cat, items in sorted(by_cat.items(),
                                  key=lambda x: -len(x[1])):
            css = _CATEGORY_CSS.get(cat, "cat-other")
            label = _CATEGORY_LABELS.get(cat, cat.title())
            confirmed_in_cat = sum(1 for f in items
                                    if "CONFIRMED" in (f.data.get("status", "") or "").upper())
            examples = []
            for f in items[:3]:
                t = f.data.get("title", "")
                if t:
                    examples.append(t[:60])
            detail = ", ".join(examples) if examples else ""
            parts.append(
                f'<li class="{css}">'
                f'<span class="cat-count">{len(items)}</span>'
                f'<span><span class="cat-label">{_esc(label)}</span>'
                f' ({confirmed_in_cat} fixed)'
                + (f'<br><span class="cat-detail">{_esc(detail)}</span>' if detail else "")
                + '</span></li>'
            )
        parts.append('</ul>')

    # Collapsed findings list
    if cycle_findings:
        parts.append(f'<details><summary>{len(cycle_findings)} findings (expand)</summary>')
        parts.append('<div class="detail-body findings-grid">')
        for f in cycle_findings:
            parts.append(_render_audit_finding(f, ctx))
        parts.append('</div></details>')

    parts.append('</div>')
    return "\n".join(parts)


def _render_audit_finding(node: Node, ctx: RenderContext) -> str:
    """Render an individual audit finding as a compact collapsed card."""
    finding_id = _esc(node.data.get("finding_id", ""))
    title = _esc(node.data.get("title", node.data.get("description", "")))
    status = node.data.get("status", "")
    construct = _esc(node.data.get("construct", ""))
    file_path = _esc(node.data.get("file_path", node.data.get("file", "")))
    lens = node.data.get("lens", "")
    fix_desc = node.data.get("fix_description", node.data.get("fix", ""))
    fix_summary = node.data.get("fix_summary", "")
    impossibility = node.data.get("impossibility_reason",
                                  node.data.get("reason",
                                                (node.data.get("impossibility") or {}).get("reason", "")))
    phase0 = node.data.get("phase0", False)
    severity = _classify_severity(node)

    # Build compact summary line: severity dot + ID + lens pill + status + title
    summary_parts = []
    summary_parts.append(f'<span class="severity-dot severity-{severity}"></span>')
    if finding_id:
        summary_parts.append(f'<span class="finding-id">{finding_id}</span>')
    if lens:
        summary_parts.append(_lens_pill(lens))
    if status:
        summary_parts.append(_status_badge(status))
    if phase0:
        summary_parts.append('<span class="status-badge status-phase0">P0</span>')
    summary_parts.append(f'<span class="finding-title" style="margin:0">{title}</span>')

    summary_line = " ".join(summary_parts)

    # Expanded detail
    detail_parts: list[str] = []
    if construct:
        detail_parts.append(f'<div class="construct">{construct}</div>')
    if file_path:
        detail_parts.append(f'<div class="file-path">{file_path}</div>')
    if fix_desc:
        detail_parts.append(f'<div class="fix-desc">{_esc(str(fix_desc))}</div>')
    if fix_summary and fix_summary != fix_desc:
        detail_parts.append(f'<div class="fix-desc" style="color:var(--text-dim)">'
                           f'{_esc(str(fix_summary))}</div>')
    if impossibility:
        detail_parts.append(f'<div class="impossibility-reason" style="color:var(--accent-red)">'
                           f'{_esc(str(impossibility))}</div>')

    if detail_parts:
        return (f'<details class="finding-card finding-compact">'
                f'<summary><span class="finding-expand-icon"></span>{summary_line}</summary>'
                f'<div class="detail-body">{"".join(detail_parts)}</div>'
                f'</details>')
    else:
        return f'<div class="finding-card finding-compact"><div>{summary_line}</div></div>'


# ---------------------------------------------------------------------------
# Dispatch table
# ---------------------------------------------------------------------------

RENDERERS: dict[str, Callable[[Node, RenderContext], str]] = {
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
    NodeType.METRIC: _render_metric,
    NodeType.BRIEF: _render_brief,
    NodeType.WORK_PLAN: _render_work_plan,
    NodeType.PR: _render_pr,
    NodeType.RETRO: _render_retro,
    NodeType.AUDIT_CYCLE: _render_audit_cycle,
    NodeType.AUDIT_FINDING: _render_audit_finding,
}


def _render_node(node: Node, ctx: RenderContext) -> str:
    """Dispatch a node to its type-specific renderer."""
    renderer = RENDERERS.get(node.node_type)
    if renderer:
        return renderer(node, ctx)
    if node.content:
        return f'<div style="margin:0.3rem 0">{_esc(node.content[:500])}</div>'
    return ""


# ---------------------------------------------------------------------------
# Prominent / background type classification (same as markdown renderer)
# ---------------------------------------------------------------------------

_PROMINENT_TYPES = frozenset({
    NodeType.CONVERSATION, NodeType.EXCHANGE, NodeType.ESCALATION,
    NodeType.SESSION_BREAK, NodeType.RESEARCH, NodeType.ARCHITECT,
    NodeType.AUDIT_CYCLE, NodeType.AUDIT_FINDING,
})

_BACKGROUND_TYPES = frozenset({
    NodeType.PROSE, NodeType.ACTION_GROUP, NodeType.TEST_RESULT,
    NodeType.STAGE_TRANSITION, NodeType.METRIC,
})


# ---------------------------------------------------------------------------
# Phase renderer
# ---------------------------------------------------------------------------

def _render_phase(node: Node, ctx: RenderContext) -> str:
    """Render a complete phase section.

    For audit stories: shows audit cycles with grouped findings.
    For feature stories: compact dashboard with TDD summary tables,
    omitting conversation transcripts (the replay covers those).
    """
    stage = node.data.get("stage", "")
    title = node.data.get("title", stage.title())
    duration = format_duration(node.duration_ms)
    ctx.current_phase = stage
    is_audit = ctx.story.story_type == "audit"

    # Strip feature slug from title
    story_title = ctx.story.title
    if story_title and f"\u2014 {story_title}" in title:
        title = title.replace(f" \u2014 {story_title}", "").strip()
    if story_title and f"- {story_title}" in title:
        title = title.replace(f" - {story_title}", "").strip()

    color = PHASE_COLORS.get(stage, "#4a9eff")
    emoji = PHASE_EMOJI.get(stage, "&#x1F4CC;")
    phase_id = f"phase-{ctx.phase_index}"
    ctx.phase_index += 1

    cost = _cost_estimate(node.tokens)

    parts: list[str] = []
    parts.append(f'<section class="phase" id="{phase_id}">')
    parts.append(f'<div class="phase-header" style="background:{color}15;'
                 f'border-bottom:2px solid {color}">')
    parts.append(f'<h2>{emoji} {_esc(title)}</h2>')
    parts.append(f'<span style="color:var(--text-dim);font-size:0.85rem">'
                 f'{_esc(duration)}'
                 + (f' &middot; {_esc(cost)}' if cost != "< $0.01" else "")
                 + '</span>')
    parts.append('</div>')
    parts.append('<div class="phase-body">')

    # Categorize children — skip CONVERSATION/EXCHANGE in static view
    # (replay covers the full transcript)
    _STATIC_SKIP = frozenset({NodeType.CONVERSATION, NodeType.EXCHANGE})

    routine_cycles: list[Node] = []
    interesting_cycles: list[Node] = []
    audit_cycles: list[Node] = []
    prominent: list[Node] = []
    background: list[Node] = []

    for child in node.children:
        if child.node_type in _STATIC_SKIP:
            continue  # replay covers these
        if child.node_type == NodeType.TDD_CYCLE:
            if child.interesting:
                interesting_cycles.append(child)
            else:
                routine_cycles.append(child)
        elif child.node_type == NodeType.AUDIT_CYCLE:
            audit_cycles.append(child)
        elif child.node_type == NodeType.AUDIT_FINDING:
            # Standalone findings outside a cycle
            prominent.append(child)
        elif child.node_type in (NodeType.ESCALATION, NodeType.SESSION_BREAK,
                                  NodeType.RESEARCH, NodeType.ARCHITECT):
            prominent.append(child)
        elif child.node_type in (NodeType.BRIEF, NodeType.WORK_PLAN,
                                  NodeType.PR, NodeType.RETRO):
            prominent.append(child)
        else:
            background.append(child)

    # Audit cycles (the main content for audit phases)
    for cycle in audit_cycles:
        rendered = _render_audit_cycle(cycle, ctx)
        if rendered:
            parts.append(rendered)

    # Prominent children inline
    for child in prominent:
        rendered = _render_node(child, ctx)
        if rendered:
            parts.append(rendered)

    # Interesting TDD cycles expanded
    for cycle in interesting_cycles:
        rendered = _render_tdd_cycle(cycle, ctx)
        if rendered:
            parts.append(rendered)

    # Routine TDD cycles as summary table
    if routine_cycles:
        parts.append(_render_tdd_summary_table(routine_cycles, ctx))

    # Background narration collapsed
    if background:
        bg_parts: list[str] = []
        for child in background:
            rendered = _render_node(child, ctx)
            if rendered:
                bg_parts.append(rendered)
        if bg_parts:
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

            parts.append(f'<details><summary>&#x1F4CB; Show {_esc(summary_label)}'
                         f'</summary><div class="detail-body">')
            parts.extend(bg_parts)
            parts.append('</div></details>')

    parts.append('</div>')  # phase-body
    parts.append('</section>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Audit summary helpers
# ---------------------------------------------------------------------------

def _collect_audit_findings(story: Story) -> list[Node]:
    """Walk the AST and collect all audit_finding nodes."""
    findings: list[Node] = []

    def _walk(nodes: list[Node]):
        for n in nodes:
            if n.node_type == NodeType.AUDIT_FINDING:
                findings.append(n)
            if n.children:
                _walk(n.children)

    for phase in story.phases:
        _walk(phase.children)
    return findings


def _render_audit_executive_dashboard(findings: list[Node], story: Story,
                                       ctx: RenderContext) -> str:
    """Render the executive dashboard for audit stories.

    Shows error categories, severity breakdown, notable findings,
    spec coverage, cluster grid, and domain lens pills.
    """
    if not findings:
        return ""

    confirmed = [f for f in findings
                 if "CONFIRMED" in (f.data.get("status", "") or "").upper()]
    impossible = [f for f in findings
                  if "IMPOSSIBLE" in (f.data.get("status", "") or "").upper()]

    parts: list[str] = ['<div class="exec-dashboard">']
    parts.append('<h3>Executive Summary</h3>')

    # --- Error categories ---
    by_cat: dict[str, list[Node]] = {}
    for f in findings:
        cat = _classify_category(f)
        by_cat.setdefault(cat, []).append(f)

    parts.append('<ul class="error-categories">')
    for cat, items in sorted(by_cat.items(), key=lambda x: -len(x[1])):
        css = _CATEGORY_CSS.get(cat, "cat-other")
        label = _CATEGORY_LABELS.get(cat, cat.title())
        conf_count = sum(1 for f in items
                         if "CONFIRMED" in (f.data.get("status", "") or "").upper())
        # Gather example descriptions
        examples = []
        for f in items[:3]:
            t = f.data.get("title", "")
            if t:
                examples.append(t[:65])
        detail = ", ".join(examples)
        parts.append(
            f'<li class="{css}">'
            f'<span class="cat-count">{len(items)}</span>'
            f'<span><span class="cat-label">{_esc(label)}</span>'
            f' ({conf_count} fixed)'
            + (f'<br><span class="cat-detail">{_esc(detail)}</span>' if detail else "")
            + '</span></li>'
        )
    parts.append('</ul>')

    # --- Severity breakdown ---
    severity_counts: dict[str, int] = {"critical": 0, "high": 0, "medium": 0, "low": 0}
    for f in findings:
        sev = _classify_severity(f)
        severity_counts[sev] = severity_counts.get(sev, 0) + 1

    sev_colors = {"critical": "#f85149", "high": "#f0883e",
                  "medium": "#d29922", "low": "#6e7681"}
    parts.append('<div class="severity-row">')
    for sev in ("critical", "high", "medium", "low"):
        count = severity_counts[sev]
        if count > 0:
            parts.append(
                f'<span class="severity-item">'
                f'<span class="severity-dot severity-{sev}"></span>'
                f'<span class="sev-count">{count}</span> {sev}'
                f'</span>'
            )
    parts.append('</div>')

    # --- Notable findings (top 3) ---
    notables = _select_notable(findings, 3)
    if notables:
        parts.append('<div class="notable-findings">')
        parts.append('<div style="font-size:0.85rem;font-weight:600;'
                     'color:var(--text-bright);margin-bottom:0.3rem">'
                     'Notable Findings</div>')
        for i, f in enumerate(notables, 1):
            fid = f.data.get("finding_id", "")
            title = f.data.get("title", "")
            fix = f.data.get("fix", f.data.get("fix_summary", ""))
            desc = fix if fix else title
            parts.append(
                f'<div class="notable-finding">'
                f'<span class="notable-rank">#{i}</span>'
                f'<span>'
                f'<span class="notable-title">{_esc(title[:80])}</span>'
                + (f' <span class="finding-id">{_esc(fid)}</span>' if fid else "")
                + (f'<br><span class="notable-desc">{_esc(str(desc)[:120])}</span>'
                   if desc and desc != title else "")
                + '</span></div>'
            )
        parts.append('</div>')

    # --- Spec coverage ---
    all_specs: set[str] = set()
    for f in findings:
        spec_str = f.data.get("spec", "")
        if spec_str:
            for s in spec_str.replace(",", " ").split():
                s = s.strip()
                if s:
                    all_specs.add(s)
    if all_specs:
        # We know how many specs were exercised; total comes from story metadata
        # if available, otherwise show just the count
        exercised = len(all_specs)
        parts.append('<div class="progress-bar-container">')
        parts.append(f'<div class="progress-bar-label">'
                     f'{exercised} spec requirements exercised</div>')
        parts.append(f'<div style="font-size:0.8rem;color:var(--text-dim)">'
                     f'Specs: {_esc(", ".join(sorted(all_specs)[:15]))}'
                     + (" ..." if len(all_specs) > 15 else "")
                     + '</div>')
        parts.append('</div>')

    # --- Domain lens pills ---
    by_lens: dict[str, list[Node]] = {}
    for f in findings:
        lens = f.data.get("lens", "unknown")
        by_lens.setdefault(lens, []).append(f)

    parts.append('<div class="lens-pills-row">')
    for lens, items in sorted(by_lens.items(), key=lambda x: -len(x[1])):
        color = LENS_COLORS.get(lens, "#8892a0")
        conf_count = sum(1 for i in items
                         if "CONFIRMED" in (i.data.get("status", "") or "").upper())
        parts.append(
            f'<span class="lens-pill-lg" '
            f'style="background:{color}22;color:{color};border:1px solid {color}44">'
            f'<span class="pill-count">{conf_count}/{len(items)}</span> {_esc(lens)}'
            f'</span>'
        )
    parts.append('</div>')

    # --- Cluster grid ---
    # Group by construct (file or class) to show cluster density
    by_construct: dict[str, list[Node]] = {}
    for f in findings:
        key = f.data.get("construct", f.data.get("file_path", "unknown"))
        if key:
            by_construct.setdefault(key, []).append(f)

    # Show clusters with 2+ findings
    clusters = {k: v for k, v in by_construct.items() if len(v) >= 2}
    if clusters:
        parts.append('<div style="font-size:0.85rem;font-weight:600;'
                     'color:var(--text-bright);margin:0.75rem 0 0.3rem">'
                     'Bug Clusters</div>')
        parts.append('<div class="cluster-grid">')
        for construct, items in sorted(clusters.items(),
                                        key=lambda x: -len(x[1])):
            total = len(items)
            fixed = sum(1 for f in items
                        if "CONFIRMED" in (f.data.get("status", "") or "").upper())
            pct = (fixed / total * 100) if total > 0 else 0
            bar_color = "var(--accent-green)" if pct > 70 else "var(--accent-amber)"
            # Shorten construct name
            short_name = construct.split(".")[-1] if "." in construct else construct
            if "/" in short_name:
                short_name = short_name.rsplit("/", 1)[-1]
            parts.append(
                f'<div class="cluster-card">'
                f'<div class="cluster-name" title="{_esc(construct)}">'
                f'{_esc(short_name)}</div>'
                f'<div class="cluster-bar">'
                f'<div class="progress-bar-track">'
                f'<div class="progress-bar-fill" '
                f'style="width:{pct:.0f}%;background:{bar_color}">'
                f'{fixed}/{total}</div></div>'
                f'<span class="cluster-count">{fixed} fixed</span>'
                f'</div></div>'
            )
        parts.append('</div>')

    # --- Follow-ups ---
    follow_ups: list[str] = []
    # Check for spec update suggestions, KB patterns in findings
    spec_updates = set()
    kb_patterns = set()
    for f in findings:
        if f.data.get("spec"):
            spec_updates.add(f.data["spec"])
        lens = f.data.get("lens", "")
        if lens:
            kb_patterns.add(lens)
    if spec_updates:
        follow_ups.append(f"{len(spec_updates)} spec requirements touched")
    if kb_patterns:
        follow_ups.append(f"{len(kb_patterns)} KB lens patterns identified")
    if impossible:
        follow_ups.append(f"{len(impossible)} findings marked impossible")

    if follow_ups:
        parts.append('<div class="follow-ups">')
        for fu in follow_ups:
            parts.append(f'<span class="follow-up-chip">{_esc(fu)}</span>')
        parts.append('</div>')

    parts.append('</div>')  # exec-dashboard
    return "\n".join(parts)


def _render_audit_findings_grid(findings: list[Node], ctx: RenderContext) -> str:
    """Render all findings in a compact collapsed grid."""
    if not findings:
        return ""
    # Group by category for organized browsing
    by_cat: dict[str, list[Node]] = {}
    for f in findings:
        cat = _classify_category(f)
        by_cat.setdefault(cat, []).append(f)

    parts = ['<h3>All Findings by Category</h3>']
    for cat, items in sorted(by_cat.items(), key=lambda x: -len(x[1])):
        label = _CATEGORY_LABELS.get(cat, cat.title())
        css = _CATEGORY_CSS.get(cat, "cat-other")
        parts.append(f'<details><summary><strong>{_esc(label)}</strong>'
                     f' ({len(items)} findings)</summary>')
        parts.append(f'<div class="detail-body findings-grid">')
        for f in items:
            parts.append(_render_audit_finding(f, ctx))
        parts.append('</div></details>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Count helpers
# ---------------------------------------------------------------------------

def count_new_tests(story: Story) -> int:
    """Count new tests from TDD cycles."""
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
# Hero section
# ---------------------------------------------------------------------------

def _render_hero(story: Story, ctx: RenderContext) -> str:
    """Render the hero header section.

    Audit stories get big-number hero cards (bugs fixed, cost/bug, etc.).
    Feature stories get outcome-focused hero (duration, cost, work units).
    """
    parts: list[str] = []
    is_audit = story.story_type == "audit"

    title = story.title or "Session"
    story_type = story.story_type

    parts.append('<header class="hero">')

    # Subtitle line (project, branch, date) — shown first for context
    subtitle_parts = []
    if story.project:
        subtitle_parts.append(_esc(story.project))
    if story.branch:
        subtitle_parts.append(f'<code>{_esc(story.branch)}</code>')
    if story.started:
        subtitle_parts.append(_esc(story.started))

    if is_audit:
        # --- AUDIT HERO ---
        parts.append(f'<h1>Audit: <code>{_esc(title)}</code></h1>')
        if subtitle_parts:
            parts.append(f'<div class="subtitle">{" &middot; ".join(subtitle_parts)}</div>')

        parts.append('<div class="hero-tagline">'
                     'Automated bug detection, adversarial test generation, and verified fixes'
                     '</div>')

        # Collect audit findings for big numbers
        findings = _collect_audit_findings(story)
        confirmed = [f for f in findings
                     if "CONFIRMED" in (f.data.get("status", "") or "").upper()]
        impossible = [f for f in findings
                      if "IMPOSSIBLE" in (f.data.get("status", "") or "").upper()
                      and "CONFIRMED" not in (f.data.get("status", "") or "").upper()]

        # Get cost from cycle data (populated by generate_audit.py from JSONL)
        # Falls back to story.tokens if available
        total_cost = _cost_value(story.tokens)
        cost_per_bug_str = ""
        for phase in story.phases:
            for child in phase.children:
                if child.node_type == NodeType.AUDIT_CYCLE:
                    if child.data.get("cost_estimate") and child.data["cost_estimate"] != "unknown":
                        try:
                            total_cost = float(child.data["cost_estimate"])
                        except (ValueError, TypeError):
                            pass
                    if child.data.get("cost_per_bug"):
                        cost_per_bug_str = child.data["cost_per_bug"]
                    break

        cost_per_bug = float(cost_per_bug_str) if cost_per_bug_str else (
            total_cost / len(confirmed) if confirmed and total_cost > 0 else 0)
        fix_rate = (len(confirmed) / len(findings) * 100) if findings else 0

        parts.append('<div class="hero-stats">')

        # Big number: bugs fixed
        parts.append(f'<div class="hero-stat">'
                     f'<div class="big-number" style="color:var(--accent-green)">'
                     f'{len(confirmed)}</div>'
                     f'<div class="big-label">bugs found &amp; fixed</div></div>')

        # Cost per bug
        if confirmed and cost_per_bug > 0:
            parts.append(f'<div class="hero-stat">'
                         f'<div class="big-number" style="color:var(--accent-amber)">'
                         f'${cost_per_bug:.2f}</div>'
                         f'<div class="big-label">cost per bug</div></div>')

        # Total cost
        if total_cost > 0:
            parts.append(f'<div class="hero-stat">'
                         f'<div class="big-number" style="color:var(--text-dim)">'
                         f'${total_cost:.2f}</div>'
                         f'<div class="big-label">total cost</div></div>')

        # Fix rate
        if findings:
            rate_color = ("var(--accent-green)" if fix_rate >= 50
                          else "var(--accent-amber)")
            parts.append(f'<div class="hero-stat">'
                         f'<div class="big-number" style="color:{rate_color}">'
                         f'{fix_rate:.0f}%</div>'
                         f'<div class="big-label">confirmation rate</div></div>')

        # Total findings
        parts.append(f'<div class="hero-stat">'
                     f'<div class="big-number" style="color:var(--accent-blue)">'
                     f'{len(findings)}</div>'
                     f'<div class="big-label">total findings</div></div>')

        parts.append('</div>')  # hero-stats

        # Non-technical impact summary — 2-3 lines covering main risks prevented
        if confirmed:
            impact_lines = _generate_impact_summary(confirmed)
            if impact_lines:
                parts.append('<div class="hero-impact">'
                             f'<p>{_esc(impact_lines)}</p>'
                             '</div>')

        # Severity summary in badge row
        severity_counts: dict[str, int] = {"critical": 0, "high": 0, "medium": 0, "low": 0}
        for f in findings:
            sev = _classify_severity(f)
            severity_counts[sev] = severity_counts.get(sev, 0) + 1

        parts.append('<div class="badge-row">')
        sev_bg = {"critical": "#5f1e1e", "high": "#5f3a1e",
                  "medium": "#5f5f1e", "low": "#3a3a3a"}
        for sev in ("critical", "high", "medium", "low"):
            c = severity_counts[sev]
            if c > 0:
                parts.append(f'<span class="badge" style="background:{sev_bg[sev]}">'
                             f'{c} {sev}</span>')
        # Duration and model badges
        duration = format_duration(story.duration_ms)
        parts.append(f'<span class="badge" style="background:#1e3a5f">'
                     f'{_esc(duration)}</span>')
        if story.model:
            parts.append(f'<span class="badge" style="background:#3a3a3a">'
                         f'{_esc(story.model)}</span>')
        sessions = len(story.sessions)
        if sessions > 1:
            parts.append(f'<span class="badge" style="background:#5f5f1e">'
                         f'{sessions} sessions</span>')
        if story.vallorcine_version:
            parts.append(f'<span class="badge" style="background:#5f3a1e">'
                         f'vallorcine v{_esc(story.vallorcine_version)}</span>')
        parts.append('</div>')

    elif story_type == "feature":
        # --- FEATURE HERO ---
        parts.append(f'<h1>Building <code>{_esc(title)}</code></h1>')
        if subtitle_parts:
            parts.append(f'<div class="subtitle">{" &middot; ".join(subtitle_parts)}</div>')

        duration = format_duration(story.duration_ms)
        total_cost = _cost_value(story.tokens)
        new_tests = count_new_tests(story)
        sessions = len(story.sessions)

        # Count TDD cycles (work units)
        work_units = 0
        escalation_count = 0

        def _count_wu(nodes: list[Node]):
            nonlocal work_units, escalation_count
            for n in nodes:
                if n.node_type == NodeType.TDD_CYCLE:
                    work_units += 1
                elif n.node_type == NodeType.ESCALATION:
                    escalation_count += 1
                if n.children:
                    _count_wu(n.children)

        for phase in story.phases:
            _count_wu(phase.children)

        parts.append('<div class="hero-stats">')

        # Duration
        parts.append(f'<div class="hero-stat">'
                     f'<div class="big-number" style="color:var(--accent-blue)">'
                     f'{_esc(duration)}</div>'
                     f'<div class="big-label">duration</div></div>')

        # Total cost
        parts.append(f'<div class="hero-stat">'
                     f'<div class="big-number" style="color:var(--accent-amber)">'
                     f'${total_cost:.2f}</div>'
                     f'<div class="big-label">total cost</div></div>')

        # Work units
        if work_units:
            parts.append(f'<div class="hero-stat">'
                         f'<div class="big-number" style="color:var(--accent-green)">'
                         f'{work_units}</div>'
                         f'<div class="big-label">work units</div></div>')

        # Tests
        if new_tests:
            parts.append(f'<div class="hero-stat">'
                         f'<div class="big-number" style="color:var(--accent-teal)">'
                         f'{new_tests}</div>'
                         f'<div class="big-label">tests passing</div></div>')

        # Sessions
        if sessions > 1:
            parts.append(f'<div class="hero-stat">'
                         f'<div class="big-number" style="color:var(--accent-red)">'
                         f'{sessions}</div>'
                         f'<div class="big-label">sessions</div></div>')

        parts.append('</div>')  # hero-stats

        # Badge row for model/version info
        parts.append('<div class="badge-row">')
        if story.model:
            parts.append(f'<span class="badge" style="background:#3a3a3a">'
                         f'{_esc(story.model)}</span>')
        if story.cli_version:
            parts.append(f'<span class="badge" style="background:#3a3a3a">'
                         f'Claude Code v{_esc(story.cli_version)}</span>')
        if story.vallorcine_version:
            parts.append(f'<span class="badge" style="background:#5f3a1e">'
                         f'vallorcine v{_esc(story.vallorcine_version)}</span>')
        if escalation_count:
            parts.append(f'<span class="badge" style="background:#5f1e1e">'
                         f'{escalation_count} escalations</span>')
        parts.append('</div>')

        # Compact phase timeline bar
        if len(story.phases) > 1:
            total_ms = story.duration_ms or sum(p.duration_ms for p in story.phases) or 1
            parts.append('<div class="phase-timeline-bar">')
            for phase in story.phases:
                stage = phase.data.get("stage", "")
                color = PHASE_COLORS.get(stage, "#4a9eff")
                pms = max(phase.duration_ms, total_ms // 100)
                pct = (pms / total_ms) * 100
                label = stage.title() if pct > 8 else ""
                parts.append(
                    f'<div class="phase-timeline-segment" '
                    f'style="width:{pct:.1f}%;background:{color}" '
                    f'title="{_esc(stage.title())} - {format_duration(phase.duration_ms)}">'
                    f'{_esc(label)}</div>'
                )
            parts.append('</div>')

    else:
        # --- OTHER TYPES ---
        if story_type == "curation":
            heading = f"Curating <code>{_esc(title)}</code>"
        elif story_type == "research":
            heading = f"Researching <code>{_esc(title)}</code>"
        elif story_type == "architect":
            heading = f"Architecture: <code>{_esc(title)}</code>"
        else:
            heading = f"<code>{_esc(title)}</code>"

        parts.append(f'<h1>{heading}</h1>')
        if subtitle_parts:
            parts.append(f'<div class="subtitle">{" &middot; ".join(subtitle_parts)}</div>')

        # Standard badge row
        duration = format_duration(story.duration_ms)
        parts.append('<div class="badge-row">')

        def _badge(label: str, value: str, color: str) -> str:
            return (f'<span class="badge" style="background:{color}">'
                    f'{_esc(label)}: {_esc(value)}</span>')

        parts.append(_badge("Duration", duration, "#1e3a5f"))
        cost = _cost_estimate(story.tokens)
        parts.append(_badge("Est. Cost", cost, "#5f3a1e"))
        if story.model:
            parts.append(_badge("Model", story.model, "#3a3a3a"))
        if story.vallorcine_version:
            parts.append(_badge("vallorcine", f"v{story.vallorcine_version}", "#5f3a1e"))
        parts.append('</div>')

    parts.append('</header>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Phase navigation
# ---------------------------------------------------------------------------

def _render_phase_nav(story: Story) -> str:
    """Render clickable phase navigation tabs."""
    if len(story.phases) <= 1:
        return ""

    parts = ['<nav class="phase-nav">']
    for i, phase in enumerate(story.phases):
        stage = phase.data.get("stage", "")
        title = phase.data.get("title", stage.title())
        # Strip feature slug
        if story.title and f"\u2014 {story.title}" in title:
            title = title.replace(f" \u2014 {story.title}", "").strip()
        if story.title and f"- {story.title}" in title:
            title = title.replace(f" - {story.title}", "").strip()
        color = PHASE_COLORS.get(stage, "#4a9eff")
        emoji = PHASE_EMOJI.get(stage, "")
        duration = format_duration(phase.duration_ms)
        parts.append(f'<a href="#phase-{i}" style="color:{color}">'
                     f'{emoji} {_esc(title)} '
                     f'<span style="font-size:0.75rem;color:var(--text-dim)">'
                     f'({_esc(duration)})</span></a>')
    parts.append('</nav>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Phase breakdown table
# ---------------------------------------------------------------------------

def _render_phase_breakdown(story: Story) -> str:
    """Render the phase breakdown table, aggregated by stage type.

    Instead of one row per phase (which can be 87 rows), groups by stage
    and shows: stage name, session count, total duration, tokens, cost, %.
    """
    if not story.phases:
        return ""

    total_tokens = (story.tokens.billable_input + story.tokens.output) or 1

    # Aggregate by stage
    from collections import OrderedDict
    stage_agg: OrderedDict[str, dict] = OrderedDict()
    for phase in story.phases:
        stage = phase.data.get("stage", "other")
        if stage == "resume":
            continue
        if stage not in stage_agg:
            stage_agg[stage] = {
                "count": 0, "duration_ms": 0,
                "input": 0, "output": 0, "cost": 0.0,
            }
        a = stage_agg[stage]
        a["count"] += 1
        a["duration_ms"] += phase.duration_ms
        a["input"] += phase.tokens.billable_input
        a["output"] += phase.tokens.output
        a["cost"] += _cost_value(phase.tokens)

    # Sort by canonical stage order
    def _sort_key(stage: str) -> int:
        try:
            return _STAGE_ORDER.index(stage)
        except ValueError:
            return len(_STAGE_ORDER)

    sorted_stages = sorted(stage_agg.keys(), key=_sort_key)

    parts: list[str] = []
    parts.append('<details open><summary><strong>Cost Breakdown by Phase</strong></summary>')
    parts.append('<div class="detail-body">')
    parts.append('<table><thead><tr>')
    parts.append('<th>Phase</th><th>Sessions</th><th>Duration</th>'
                 '<th>Tokens (in)</th><th>Tokens (out)</th>'
                 '<th>Est. Cost</th><th>% of total</th>')
    parts.append('</tr></thead><tbody>')

    for stage in sorted_stages:
        a = stage_agg[stage]
        color = PHASE_COLORS.get(stage, "#4a9eff")
        title = stage.replace("_", " ").title()
        dur = format_duration(a["duration_ms"])
        inp = _abbreviate(a["input"])
        out = _abbreviate(a["output"])
        cost = f'${a["cost"]:.2f}' if a["cost"] > 0 else "\u2014"
        phase_total = a["input"] + a["output"]
        pct = (phase_total / total_tokens) * 100

        count_str = f'{a["count"]}' if a["count"] > 1 else "1"

        parts.append(
            f'<tr><td><a href="#stage-{_esc(stage)}" style="color:{color}">'
            f'{_esc(title)}</a></td>'
            f'<td style="text-align:center">{count_str}</td>'
            f'<td>{_esc(dur)}</td><td>{_esc(inp)}</td><td>{_esc(out)}</td>'
            f'<td>{_esc(cost)}</td><td>{pct:.0f}%</td></tr>'
        )

    parts.append('</tbody></table>')
    parts.append('</div></details>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Aggregated phase rendering (feature stories with many phases)
# ---------------------------------------------------------------------------

# Canonical stage display order
_STAGE_ORDER = [
    "scoping", "research", "domains", "architect", "decisions",
    "planning", "testing", "implementation", "refactor",
    "coordination", "pr", "retro", "complete", "resume",
    "curation", "knowledge", "audit",
]


def _render_aggregated_phases(story: Story, ctx: RenderContext) -> str:
    """Aggregate phases by stage and render one section per stage type.

    For features with 87 phases across 13 stage types, this produces 13
    compact sections instead of 87 full sections. Each section shows
    aggregated metrics and only expands to show interesting children.
    """
    from collections import OrderedDict

    if not story.phases:
        return ""

    # Group phases by stage
    stage_groups: OrderedDict[str, list[Node]] = OrderedDict()
    for phase in story.phases:
        stage = phase.data.get("stage", "other")
        if stage == "resume":
            continue  # skip resume phases — they're session bookkeeping
        if stage not in stage_groups:
            stage_groups[stage] = []
        stage_groups[stage].append(phase)

    # Sort by canonical order
    def _stage_sort_key(stage: str) -> int:
        try:
            return _STAGE_ORDER.index(stage)
        except ValueError:
            return len(_STAGE_ORDER)

    sorted_stages = sorted(stage_groups.keys(), key=_stage_sort_key)

    parts: list[str] = []

    for stage in sorted_stages:
        phases = stage_groups[stage]
        color = PHASE_COLORS.get(stage, "#4a9eff")
        total_dur = sum(p.duration_ms for p in phases)
        total_in = sum(p.tokens.billable_input for p in phases)
        total_out = sum(p.tokens.output for p in phases)
        total_cost_val = sum(_cost_value(p.tokens) for p in phases)

        # Count interesting children across all phases for this stage
        interesting_children = []
        tdd_count = 0
        test_pass = 0
        test_fail = 0
        escalation_count = 0
        for phase in phases:
            for child in phase.children:
                if child.node_type == NodeType.ESCALATION:
                    escalation_count += 1
                    interesting_children.append(child)
                elif child.node_type == NodeType.TDD_CYCLE:
                    tdd_count += 1
                    test_pass += child.data.get("test_passed", 0)
                    test_fail += child.data.get("test_failed", 0)
                    if child.interesting:
                        interesting_children.append(child)
                elif child.node_type == NodeType.AUDIT_CYCLE:
                    interesting_children.append(child)

        # Section header with metrics
        stage_title = stage.replace("_", " ").title()
        session_count = len(phases)

        parts.append(f'<section class="phase" id="stage-{_esc(stage)}">')
        parts.append(f'<div class="phase-header" style="border-left-color:{color}">')
        parts.append(f'<h2 style="color:{color}">{_esc(stage_title)}</h2>')

        # Compact metric row
        metrics = []
        if session_count > 1:
            metrics.append(f'{session_count} sessions')
        if total_dur:
            metrics.append(format_duration(total_dur))
        if total_cost_val > 0:
            metrics.append(f'${total_cost_val:.2f}')
        if tdd_count:
            metrics.append(f'{tdd_count} work units')
        if test_pass or test_fail:
            metrics.append(f'{test_pass} passed / {test_fail} failed')
        if escalation_count:
            metrics.append(f'{escalation_count} escalations')
        if metrics:
            parts.append(f'<div style="color:var(--text-dim);font-size:0.85rem">'
                         f'{" &middot; ".join(metrics)}'
                         f'</div>')

        parts.append('</div>')  # phase-header

        # Body: only show interesting children
        if interesting_children:
            parts.append('<div class="phase-body">')
            # Escalations shown prominently
            for child in interesting_children:
                if child.node_type == NodeType.ESCALATION:
                    parts.append(_render_node(child, ctx))
            # Interesting TDD cycles
            interesting_tdd = [c for c in interesting_children
                               if c.node_type == NodeType.TDD_CYCLE]
            if interesting_tdd:
                parts.append(f'<details><summary>{len(interesting_tdd)} '
                             f'notable work units</summary>')
                for child in interesting_tdd:
                    parts.append(_render_node(child, ctx))
                parts.append('</details>')
            # Audit cycles
            for child in interesting_children:
                if child.node_type == NodeType.AUDIT_CYCLE:
                    parts.append(_render_node(child, ctx))
            parts.append('</div>')  # phase-body

        parts.append('</section>')

    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Footer
# ---------------------------------------------------------------------------

def _render_footer(story: Story) -> str:
    """Render the footer with token breakdown and metadata."""
    parts: list[str] = []
    parts.append('<footer>')

    # Token breakdown — skip if no token data (audit stories from reports)
    u = story.tokens
    has_tokens = u.billable_input > 0 or u.output > 0
    if has_tokens:
        parts.append('<h3>Token &amp; Cost Breakdown</h3>')
        parts.append('<div class="cost-grid">')
        parts.append(_metric_card("Billable Input", _abbreviate(u.billable_input)))
        parts.append(_metric_card("Output", _abbreviate(u.output)))
        parts.append(_metric_card("Cache Read", _abbreviate(u.cache_read)))
        parts.append(_metric_card("Cache Create", _abbreviate(u.cache_create)))
        parts.append(_metric_card("Total Context", _abbreviate(u.total_context)))
        parts.append(_metric_card("Est. Total Cost", _cost_estimate(u)))
        parts.append('</div>')

    # Metadata
    meta_parts = []
    if story.model:
        meta_parts.append(f"Model: {_esc(story.model)}")
    if story.cli_version:
        meta_parts.append(f"Claude Code: v{_esc(story.cli_version)}")
    if story.vallorcine_version:
        meta_parts.append(f"vallorcine: v{_esc(story.vallorcine_version)}")
    if story.sessions:
        meta_parts.append(f"Sessions: {len(story.sessions)}")
    if story.started:
        meta_parts.append(f"Started: {_esc(story.started)}")
    if meta_parts:
        parts.append(f'<div style="margin-top:1rem">{" &middot; ".join(meta_parts)}</div>')

    parts.append('<div style="margin-top:1rem;font-size:0.8rem">')
    parts.append('Generated by <a href="https://github.com/telefrek/vallorcine">vallorcine</a> '
                 'showcase tools.')
    parts.append('</div>')
    parts.append('</footer>')
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Replay section
# ---------------------------------------------------------------------------

def _extract_replay_steps(story: Story) -> list[dict]:
    """Walk the Story AST and extract an ordered list of replay steps.

    Each step is a dict with:
        type:      user | assistant | tool_call | phase_change | escalation
        content:   display text
        phase:     current phase slug
        label:     (phase_change only) display label
        tool:      (tool_call only) tool name
        timestamp: (if available)
    """
    steps: list[dict] = []
    current_phase = ""

    def _walk_children(children: list[Node], phase: str):
        for child in children:
            _walk_node(child, phase)

    def _walk_node(node: Node, phase: str):
        nt = node.node_type

        if nt == NodeType.CONVERSATION:
            for ex in node.children:
                _walk_exchange(ex, phase)

        elif nt == NodeType.EXCHANGE:
            _walk_exchange(node, phase)

        elif nt == NodeType.TDD_CYCLE:
            name = node.data.get("unit_name", "Work unit")
            dur = format_duration(node.duration_ms or node.data.get("duration_ms", 0))
            summary = node.data.get("summary", "")
            text = f"[subagent] {name} ({dur})"
            if summary:
                text += f" — {summary}"
            steps.append({"type": "assistant", "content": text, "phase": phase})
            # Walk children for any nested exchanges
            _walk_children(node.children, phase)

        elif nt == NodeType.STAGE_TRANSITION:
            cmd = node.data.get("command", "")
            args = node.data.get("args", "")
            if cmd:
                content = f"/{cmd}"
                if args:
                    content += f" {args}"
                steps.append({"type": "user", "content": content, "phase": phase})

        elif nt == NodeType.SESSION_BREAK:
            gap = format_duration(node.data.get("gap_duration_ms", 0))
            steps.append({
                "type": "escalation",
                "content": f"Session crashed. Resumed {gap} later.",
                "phase": phase,
            })

        elif nt == NodeType.ESCALATION:
            content = node.content or "Escalation"
            short, _ = _truncate(content, 300)
            steps.append({
                "type": "escalation", "content": short, "phase": phase,
            })

        elif nt == NodeType.ACTION_GROUP:
            if "unit_name" in node.data:
                name = node.data.get("unit_name", "Action")
                dur = format_duration(node.duration_ms or node.data.get("duration_ms", 0))
                steps.append({
                    "type": "tool_call",
                    "tool": "Agent",
                    "content": f"{name} ({dur})",
                    "phase": phase,
                })
            else:
                actions = node.data.get("actions", [])
                if actions:
                    summaries = [a.get("summary", f"{a.get('verb', '?')} {a.get('target', '')}")
                                 for a in actions[:5]]
                    text = "; ".join(summaries)
                    if len(actions) > 5:
                        text += f" (+{len(actions) - 5} more)"
                    steps.append({
                        "type": "tool_call",
                        "tool": actions[0].get("verb", "Edit"),
                        "content": text,
                        "phase": phase,
                    })

        elif nt == NodeType.TEST_RESULT:
            passed = node.data.get("passed", 0)
            failed = node.data.get("failed", 0)
            if failed:
                steps.append({
                    "type": "tool_call", "tool": "Test",
                    "content": f"{passed} passed, {failed} failed",
                    "phase": phase,
                })
            elif passed:
                steps.append({
                    "type": "tool_call", "tool": "Test",
                    "content": f"{passed} passed",
                    "phase": phase,
                })

        elif nt == NodeType.RESEARCH:
            topic = node.data.get("topic", "")
            findings = node.data.get("findings_summary", "")
            text = f"Research: {topic}" if topic else "Research"
            if findings:
                short, _ = _truncate(findings, 200)
                text += f" — {short}"
            steps.append({"type": "assistant", "content": text, "phase": phase})

        elif nt == NodeType.ARCHITECT:
            question = node.data.get("question", "")
            decision = node.data.get("decision", "")
            text = f"Architecture: {question}" if question else "Architecture Decision"
            if decision:
                short, _ = _truncate(decision, 200)
                text += f" => {short}"
            steps.append({"type": "assistant", "content": text, "phase": phase})

        elif nt == NodeType.BRIEF:
            content = node.content or node.data.get("content", "")
            if content:
                short, _ = _truncate(content, 300)
                steps.append({"type": "assistant", "content": f"Brief: {short}", "phase": phase})

        elif nt == NodeType.WORK_PLAN:
            units = node.data.get("units", [])
            if units:
                names = [u.get("name", u.get("unit_name", "?")) for u in units[:6]]
                text = "Work Plan: " + ", ".join(names)
                if len(units) > 6:
                    text += f" (+{len(units) - 6} more)"
                steps.append({"type": "assistant", "content": text, "phase": phase})

        elif nt == NodeType.PR:
            url = node.data.get("url", "")
            title = node.data.get("title", "Pull Request")
            text = f"PR: {title}"
            if url:
                text += f" — {url}"
            steps.append({"type": "assistant", "content": text, "phase": phase})

        elif nt == NodeType.RETRO:
            content = node.content or ""
            if content.strip():
                short, _ = _truncate(content, 300)
                steps.append({"type": "assistant", "content": f"Retro: {short}", "phase": phase})

        elif nt == NodeType.AUDIT_CYCLE:
            stage = node.data.get("stage", "")
            target = node.data.get("target", "")
            label = "Audit"
            if stage:
                label += f": {stage}"
            if target:
                label += f" — {target}"
            steps.append({"type": "assistant", "content": label, "phase": phase})
            _walk_children(node.children, phase)

        elif nt == NodeType.AUDIT_FINDING:
            fid = node.data.get("finding_id", "")
            title = node.data.get("title", node.data.get("description", ""))
            status = node.data.get("status", "")
            text = f"Finding {fid}: {title}" if fid else f"Finding: {title}"
            if status:
                text += f" [{status}]"
            steps.append({"type": "assistant", "content": text, "phase": phase})

        elif nt == NodeType.PROSE:
            content = node.content or ""
            if content.strip():
                short, _ = _truncate(content, 200)
                steps.append({"type": "assistant", "content": short, "phase": phase})

        elif nt == NodeType.METRIC:
            # Skip metrics in replay — they're visual noise
            pass

        else:
            # Unknown node with content
            if node.content:
                short, _ = _truncate(node.content, 200)
                steps.append({"type": "assistant", "content": short, "phase": phase})

    def _walk_exchange(node: Node, phase: str):
        q = node.data.get("question", "")
        a = node.data.get("answer", "")
        if q and q.strip():
            short, _ = _truncate(q, 400)
            steps.append({"type": "assistant", "content": short, "phase": phase})
        if a and a.strip() and not _is_confirmation(a):
            short, _ = _truncate(a, 400)
            steps.append({"type": "user", "content": short, "phase": phase})

    for phase_node in story.phases:
        stage = phase_node.data.get("stage", "")
        title = phase_node.data.get("title", stage.title())
        # Emit phase_change marker
        steps.append({
            "type": "phase_change",
            "phase": stage,
            "label": title,
            "content": "",
        })
        current_phase = stage
        _walk_children(phase_node.children, stage)

    return steps


def _render_replay_section(story: Story) -> str:
    """Generate the interactive replay section HTML + embedded JS."""
    steps = _extract_replay_steps(story)
    if not steps:
        return ""

    # Build phase list for timeline
    phases: list[dict] = []
    for i, step in enumerate(steps):
        if step["type"] == "phase_change":
            color = PHASE_COLORS.get(step["phase"], "#4a9eff")
            phases.append({
                "index": i,
                "label": step["label"],
                "phase": step["phase"],
                "color": color,
            })

    # Sanitize steps for JSON embedding — only keep safe keys
    safe_steps = []
    for s in steps:
        safe = {
            "type": s["type"],
            "content": s.get("content", ""),
            "phase": s.get("phase", ""),
        }
        if "label" in s:
            safe["label"] = s["label"]
        if "tool" in s:
            safe["tool"] = s["tool"]
        safe_steps.append(safe)

    total = len(safe_steps)

    # Timeline buttons
    timeline_html = ""
    for p in phases:
        color = p["color"]
        label = _esc(p["label"])
        idx = p["index"]
        timeline_html += (
            f'<button onclick="replayJumpTo({idx})" '
            f'style="color:{color}" data-phase="{_esc(p["phase"])}">'
            f'{label}</button>'
        )

    return f"""
<section id="replay" class="replay-container">
  <div class="replay-controls">
    <button id="replay-prev" title="Previous step">&#x23EE;</button>
    <button id="replay-play" title="Play / Pause">&#x25B6;</button>
    <button id="replay-next" title="Next step">&#x23ED;</button>
    <select id="replay-speed" title="Playback speed">
      <option value="2000">0.5x</option>
      <option value="1000" selected>1x</option>
      <option value="500">2x</option>
      <option value="250">4x</option>
    </select>
    <span class="replay-progress" id="replay-progress">0 / {total}</span>
  </div>
  <div class="replay-timeline">{timeline_html}</div>
  <div class="replay-terminal" id="replay-terminal"></div>
</section>
<script>
(function() {{
  var STEPS = {json.dumps(safe_steps, separators=(',', ':'))};
  var term = document.getElementById('replay-terminal');
  var progressEl = document.getElementById('replay-progress');
  var playBtn = document.getElementById('replay-play');
  var prevBtn = document.getElementById('replay-prev');
  var nextBtn = document.getElementById('replay-next');
  var speedSel = document.getElementById('replay-speed');
  var idx = 0, playing = false, timer = null;
  var total = STEPS.length;

  function esc(s) {{
    var d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }}

  function renderStep(step) {{
    var el = document.createElement('div');
    el.className = 'replay-step-enter';
    if (step.type === 'phase_change') {{
      el.className += ' r-phase-marker';
      el.textContent = '\\u2501\\u2501 ' + (step.label || step.phase) + ' \\u2501\\u2501';
    }} else if (step.type === 'user') {{
      el.className += ' r-user';
      el.innerHTML = '<span class="r-label">User</span>' + esc(step.content);
    }} else if (step.type === 'assistant') {{
      el.className += ' r-assistant';
      el.innerHTML = '<span class="r-label">Claude</span>' + esc(step.content);
    }} else if (step.type === 'tool_call') {{
      el.className += ' r-tool';
      var toolLabel = step.tool ? ('[' + step.tool + '] ') : '';
      el.innerHTML = toolLabel + esc(step.content);
      el.onclick = function() {{ el.classList.toggle('expanded'); }};
    }} else if (step.type === 'escalation') {{
      el.className += ' r-escalation';
      el.innerHTML = '\\u26A0 ' + esc(step.content);
    }}
    return el;
  }}

  function showUpTo(n) {{
    term.innerHTML = '';
    for (var i = 0; i <= n && i < total; i++) {{
      var el = renderStep(STEPS[i]);
      if (i === n) el.className += ' replay-step-enter';
      else {{
        // Remove animation for already-shown steps
        el.className = el.className.replace('replay-step-enter', '');
      }}
      term.appendChild(el);
    }}
    term.scrollTop = term.scrollHeight;
    progressEl.textContent = (n + 1) + ' / ' + total;
    // Highlight active phase in timeline
    var phase = STEPS[n].phase;
    var btns = document.querySelectorAll('.replay-timeline button');
    var lastMatch = null;
    for (var i = 0; i < btns.length; i++) {{
      btns[i].classList.remove('active');
      if (btns[i].getAttribute('data-phase') === phase) lastMatch = btns[i];
    }}
    if (lastMatch) lastMatch.classList.add('active');
  }}

  function stepForward() {{
    if (idx < total - 1) {{
      idx++;
      showUpTo(idx);
    }} else {{
      pause();
    }}
  }}

  function stepBack() {{
    if (idx > 0) {{
      idx--;
      showUpTo(idx);
    }}
  }}

  function play() {{
    if (playing) return;
    playing = true;
    playBtn.innerHTML = '&#x23F8;';
    tick();
  }}

  function pause() {{
    playing = false;
    playBtn.innerHTML = '&#x25B6;';
    if (timer) {{ clearTimeout(timer); timer = null; }}
  }}

  function tick() {{
    if (!playing) return;
    stepForward();
    if (idx >= total - 1) {{ pause(); return; }}
    var delay = parseInt(speedSel.value, 10) || 1000;
    // Phase changes get a longer pause
    if (STEPS[idx] && STEPS[idx].type === 'phase_change') delay *= 1.5;
    timer = setTimeout(tick, delay);
  }}

  playBtn.onclick = function() {{ playing ? pause() : play(); }};
  nextBtn.onclick = function() {{ pause(); stepForward(); }};
  prevBtn.onclick = function() {{ pause(); stepBack(); }};
  speedSel.onchange = function() {{
    if (playing) {{ pause(); play(); }}
  }};

  window.replayJumpTo = function(n) {{
    pause();
    idx = Math.min(n, total - 1);
    showUpTo(idx);
  }};

  // Show initial state — first phase marker
  if (total > 0) showUpTo(0);
}})();
</script>
"""


# ---------------------------------------------------------------------------
# Feature dashboard
# ---------------------------------------------------------------------------

def _render_feature_dashboard(story: Story, ctx: RenderContext) -> str:
    """Render a compact dashboard for feature stories.

    Shows phase summary, work unit summary, escalations, session count.
    """
    parts: list[str] = ['<div class="feature-dashboard">']
    parts.append('<h3>Session Dashboard</h3>')

    # Collect summary stats
    work_units = 0
    interesting_units = 0
    escalation_count = 0
    escalation_summaries: list[str] = []
    total_passed = 0
    total_failed = 0

    def _walk(nodes: list[Node]):
        nonlocal work_units, interesting_units, escalation_count, total_passed, total_failed
        for n in nodes:
            if n.node_type == NodeType.TDD_CYCLE:
                work_units += 1
                if n.interesting:
                    interesting_units += 1
                total_passed += n.data.get("test_passed", 0)
                total_failed += n.data.get("test_failed", 0)
            elif n.node_type == NodeType.ESCALATION:
                escalation_count += 1
                content = n.content or ""
                if content.strip():
                    escalation_summaries.append(content[:100])
            if n.children:
                _walk(n.children)

    for phase in story.phases:
        _walk(phase.children)

    # Metric row
    parts.append('<div class="dashboard-row">')
    parts.append(_metric_card("Phases", str(len(story.phases))))
    if work_units:
        parts.append(_metric_card("Work Units", str(work_units)))
    if total_passed or total_failed:
        test_str = f"{total_passed} passed"
        if total_failed:
            test_str += f", {total_failed} failed"
        parts.append(_metric_card("Tests", test_str))
    sessions = len(story.sessions)
    if sessions > 1:
        parts.append(_metric_card("Sessions", f"{sessions} (crash/resume)"))
    parts.append('</div>')

    # Escalations
    if escalation_count:
        parts.append(f'<div style="margin:0.5rem 0">'
                     f'<span style="color:var(--accent-red);font-weight:600">'
                     f'{escalation_count} escalation{"s" if escalation_count != 1 else ""}'
                     f'</span>')
        if escalation_summaries:
            for es in escalation_summaries[:3]:
                parts.append(f'<div style="font-size:0.8rem;color:var(--text-dim);'
                             f'margin-left:0.5rem">&bull; {_esc(es)}</div>')
        parts.append('</div>')

    parts.append('</div>')  # feature-dashboard
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Main render function
# ---------------------------------------------------------------------------

def render_story(story: Story) -> str:
    """Render a Story AST into a complete standalone HTML document."""
    ctx = RenderContext(story)
    is_audit = story.story_type == "audit"

    parts: list[str] = []

    # Document head
    title_text = _esc(story.title or "Narrative")
    parts.append('<!DOCTYPE html>')
    parts.append('<html lang="en">')
    parts.append('<head>')
    parts.append(f'<meta charset="utf-8">')
    parts.append(f'<meta name="viewport" content="width=device-width, initial-scale=1">')
    parts.append(f'<title>{title_text} \u2014 vallorcine narrative</title>')
    parts.append(f'<style>{CSS}</style>')
    parts.append('</head>')
    parts.append('<body>')

    # Hero
    parts.append(_render_hero(story, ctx))

    # View toggle (Static | Replay)
    parts.append("""
<div class="view-toggle">
  <button id="toggle-static" class="active" onclick="switchView('static')">Static View</button>
  <button id="toggle-replay" onclick="switchView('replay')">Replay</button>
</div>
""")

    # Replay section (hidden by default)
    parts.append(_render_replay_section(story))

    # Main content (static view)
    parts.append('<main id="static-view">')

    # Token/cost breakdown — show early for combined reports so users
    # see what was spent before scrolling through stages
    has_tokens = story.tokens.billable_input > 0 or story.tokens.output > 0
    if has_tokens:
        parts.append(_render_phase_breakdown(story))

    if is_audit:
        # --- AUDIT STATIC VIEW ---
        # Executive dashboard (always visible, right after hero)
        findings = _collect_audit_findings(story)
        if findings:
            parts.append(_render_audit_executive_dashboard(findings, story, ctx))

        # Phase sections with grouped findings
        for phase in story.phases:
            parts.append(_render_phase(phase, ctx))

        # Full findings index (collapsed, by category)
        if findings:
            parts.append('<details class="findings-index">')
            parts.append(f'<summary>All {len(findings)} findings (expand to browse)</summary>')
            parts.append(_render_audit_findings_grid(findings, ctx))
            parts.append('</details>')

    else:
        # --- FEATURE / OTHER STATIC VIEW ---
        # Feature dashboard (compact summary)
        if story.story_type == "feature":
            parts.append(_render_feature_dashboard(story, ctx))

        # Aggregate phases by stage for compact display
        parts.append(_render_aggregated_phases(story, ctx))

    parts.append('</main>')

    # Footer
    parts.append(_render_footer(story))

    # View switching script
    parts.append("""
<script>
function switchView(view) {
  var staticEl = document.getElementById('static-view');
  var replayEl = document.getElementById('replay');
  var btnStatic = document.getElementById('toggle-static');
  var btnReplay = document.getElementById('toggle-replay');
  if (view === 'replay') {
    staticEl.style.display = 'none';
    if (replayEl) replayEl.classList.add('visible');
    btnStatic.classList.remove('active');
    btnReplay.classList.add('active');
  } else {
    staticEl.style.display = '';
    if (replayEl) replayEl.classList.remove('visible');
    btnStatic.classList.add('active');
    btnReplay.classList.remove('active');
  }
}
</script>
""")

    parts.append('</body>')
    parts.append('</html>')

    return "\n".join(parts)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    """CLI entry point: load a Story AST JSON and render to HTML."""
    parser = argparse.ArgumentParser(
        description="Render a Story AST into a standalone HTML narrative."
    )
    parser.add_argument("story_file", help="Path to story AST JSON file")
    parser.add_argument("--output", "-o", help="Output file (default: stdout)")
    args = parser.parse_args()

    story = Story.load(args.story_file)
    html_output = render_story(story)

    if args.output:
        with open(args.output, "w") as f:
            f.write(html_output)
        print(f"Wrote {len(html_output)} bytes to {args.output}", file=sys.stderr)
    else:
        print(html_output)


if __name__ == "__main__":
    main()
