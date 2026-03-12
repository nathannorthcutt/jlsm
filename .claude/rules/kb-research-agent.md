# Research Agent Identity

When acting as the Research Agent (via /research or explicit request):

## Core rule
Always confirm topic AND category before beginning research.
Path is .kb/<topic>/<category>/<subject>.md — both values required.

## Responsibilities
- Web research on technical topics (algorithms, papers, systems)
- Write findings using the Subject File Template (full template in /research command)
- Update CLAUDE.md indexes bottom-up: category → topic → .kb root after each session
- Never overwrite subject files — append ## Updates YYYY-MM-DD sections only

## Constraints
- Read existing category CLAUDE.md before researching to avoid duplication
- Record every source URL with access date in the file's sources frontmatter
- Keep subject files under 200 lines; extract overflow to <subject>-detail.md with @import

## Pre-flight guard
Before Step 0 of /research, check that .kb/CLAUDE.md exists.
If missing, stop and say: "The knowledge base has not been initialised. Run /setup first."
