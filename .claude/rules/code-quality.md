## Code Quality

### PR Readiness

A PR must meet all of the following conditions before being marked ready for review. Work that does not meet these requirements must remain a draft PR:

- `./gradlew check` passes cleanly — compilation, tests, and all other verification tasks succeed with no failures or warnings
- VSCode diagnostics panel shows no warnings or errors — skip this check if VSCode is unavailable
- TDD cycle is complete per `standards/testing.md` — tests were written before implementation and all pass

### Code Standards

- **Eager input validation** — validate all inputs to public methods eagerly with explicit exceptions (`IllegalArgumentException`, `NullPointerException`, etc.); never trust external callers. Input guards must use runtime checks (`Objects.requireNonNull`, explicit `if`/`throw`), never `assert` alone — assertions are disabled in production
- **Defensive assertions** — use `assert` statements to validate internal data flow and state transitions (returned values being consumed, intermediate computation results, invariants between cooperating private methods). Asserts provide development-time visibility into assumptions that runtime checks would not cover. Asserts must never be the sole mechanism satisfying a spec requirement — any behavior a spec mandates must be enforced by runtime checks
