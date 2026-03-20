## Documentation Requirements

### README.md

The README must be reviewed and updated whenever a feature ships that changes
the project's public API, adds a module, or adds a significant capability.
Specifically, update the README when any of the following are true:

- A new module is added to the build
- A module's purpose or public API changes substantially
- The dependency graph between modules changes
- A new category of functionality is added (e.g., encryption, compression)
- The architecture diagram no longer reflects the current design
- Build or developer setup instructions change

The `/feature-pr` command should check whether the README needs updating as
part of the PR readiness checklist. If unsure, update it — a current README
is always better than a stale one.

### CHANGELOG.md

Every merge to `main` must have a corresponding entry in `CHANGELOG.md`. This
provides a history that is not entirely reliant on git and supports future
library versioning.

**Format:** follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Each entry uses the PR number as its heading (e.g., `## #21 — Short Title`).

**What to include:**
- **Added** — new features, modules, APIs, ADRs, KB entries
- **Changed** — modifications to existing behaviour
- **Performance** — measurable optimizations with a brief description of what improved
- **Fixed** — bug fixes
- **Removed** — removed features or deprecated APIs
- **Known Gaps** — intentional limitations shipped with the change

**What NOT to include:**
- Internal refactors with no user-visible effect (unless they change the public API)
- Chore commits (dependency bumps, formatting) unless they affect build requirements

**When to write it:**
- During `/feature-pr` — the PR draft step should append an `[Unreleased]` entry
- On merge to `main` — move the entry from `[Unreleased]` to a dated heading

Keep entries brief and concise — one line per bullet. Focus on what changed and
why it matters, not implementation details.
