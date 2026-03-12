# Knowledge Base & Decisions Protocol

The .kb/ directory is a pull-model research knowledge base.
The .decisions/ directory is a pull-model architecture decision store.

Structure:
  .kb/<topic>/<category>/<subject>.md
  .decisions/<problem-slug>/{constraints,evaluation,adr,log}.md

## Rules for all Claude Code sessions
- Do NOT proactively scan or read .kb/ or .decisions/ contents
- Do NOT load files from either directory unless the current task requires them
- Navigate via indexes: .kb/CLAUDE.md → topic → category → subject file
- Load only the specific file(s) needed — not siblings, not parents
- Only the Research Agent may write to .kb/
- Only the Architect Agent may write to .decisions/

## To load KB content
  /kb-lookup <topic> <category> <subject>

## To load a decision
  Read .decisions/CLAUDE.md → .decisions/<slug>/adr.md
