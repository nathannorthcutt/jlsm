#!/usr/bin/env python3
"""Narrative generation orchestrator.

Chains the 3-stage pipeline (tokenize → parse → render) for a given
feature slug and writes a polished markdown article to the feature
directory.

This script is invoked by narrative-wrapper.sh as an enhanced
implementation of Step 6 in /feature-retro. If the pipeline fails
for any reason (missing JSONL, format changes, parse errors), it
logs a diagnostic message to stderr and exits 0 — narrative
generation is optional and must never block the retro.

Usage:
    python3 generate.py <slug> <feature-dir>

    slug         — feature slug (e.g., "encrypt-memory-data")
    feature-dir  — path to .feature/<slug>/ directory

The script derives the Claude Code projects directory from the
current working directory.
"""

import os
import sys
from pathlib import Path


def _find_projects_dir() -> str:
    """Derive the Claude Code session directory from cwd.

    Claude Code stores session JSONL in:
        ~/.claude/projects/-<project-path-with-dashes>/
    where the project path has leading / stripped and / replaced with -.
    """
    project_dir = os.getcwd()
    project_id = project_dir.lstrip("/").replace("/", "-")
    session_dir = Path.home() / ".claude" / "projects" / f"-{project_id}"
    if not session_dir.is_dir():
        return ""
    return str(session_dir)


def _read_vallorcine_version() -> str:
    """Read vallorcine version from .claude/.vallorcine-version."""
    version_paths = [
        Path(os.getcwd()) / ".claude" / ".vallorcine-version",
        # Fallback: VERSION file in the kit repo (for dev mode)
        Path(__file__).resolve().parent.parent.parent / "VERSION",
    ]
    for p in version_paths:
        try:
            return p.read_text().strip()
        except OSError:
            continue
    return ""


def generate(slug: str, feature_dir: str) -> bool:
    """Run the full narrative pipeline for a feature.

    Returns True if narrative.md was written, False otherwise.
    """
    # Add our directory to sys.path so imports work
    script_dir = str(Path(__file__).resolve().parent)
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)

    from tokenizer import tokenize_feature
    from parse import parse_story
    from render_narrative import render_story
    from model import Story

    # --- Resolve paths ---
    projects_dir = _find_projects_dir()
    if not projects_dir:
        print("narrative: no Claude Code session directory found", file=sys.stderr)
        return False

    feature_path = Path(feature_dir)
    if not feature_path.is_dir():
        print(f"narrative: feature directory not found: {feature_dir}", file=sys.stderr)
        return False

    tokens_file = feature_path / ".narrative-tokens.json"
    ast_file = feature_path / ".narrative-ast.json"
    output_file = feature_path / "narrative.md"

    try:
        # --- Stage 1: Tokenize ---
        stream = tokenize_feature(slug, projects_dir)
        if not stream.tokens:
            print(f"narrative: no sessions found for slug '{slug}'", file=sys.stderr)
            return False
        stream.save(str(tokens_file))

        # --- Stage 2: Parse ---
        story = parse_story(stream, feature_slug=slug)
        if not story.phases:
            print(f"narrative: no phases parsed for slug '{slug}'", file=sys.stderr)
            return False

        # --- Inject version metadata ---
        story.vallorcine_version = _read_vallorcine_version()
        story.save(str(ast_file))

        # --- Stage 3: Render ---
        markdown = render_story(story)
        output_file.write_text(markdown)

        # Keep the AST for downstream consumers (e.g., generate_audit --include-feature)
        # Remove only the tokens intermediate (large, not needed after parse)
        return True

    finally:
        try:
            tokens_file.unlink(missing_ok=True)
        except OSError:
            pass


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <slug> <feature-dir>", file=sys.stderr)
        sys.exit(1)

    slug = sys.argv[1]
    feature_dir = sys.argv[2]

    try:
        success = generate(slug, feature_dir)
        if success:
            print(f"narrative: wrote {Path(feature_dir) / 'narrative.md'}", file=sys.stderr)
            sys.exit(0)
        else:
            sys.exit(1)
    except Exception as e:
        print(f"narrative: pipeline failed: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
