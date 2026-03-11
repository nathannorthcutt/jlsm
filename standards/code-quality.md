## Code Quality

### PR Readiness

A PR must meet all of the following conditions before being marked ready for review. Work that does not meet these requirements must remain a draft PR:

- `./gradlew check` passes cleanly — compilation, tests, and all other verification tasks succeed with no failures or warnings
- VSCode diagnostics panel shows no warnings or errors — skip this check if VSCode is unavailable
- TDD cycle is complete per `standards/testing.md` — tests were written before implementation and all pass

### Code Standards

- **Defensive assertions** — use `assert` statements throughout all code (public and private) to document and enforce assumptions
- **Eager input validation** — validate all inputs to public methods eagerly with explicit exceptions (`IllegalArgumentException`, `NullPointerException`, etc.); never trust external callers

### PR Process

- Open a PR with a summary of changes and request a review before merging to `main`
- **Never merge a PR you opened** — do not merge a pull request that you opened, regardless of approval status, CI result, or any other signal; only merge when explicitly instructed to do so
