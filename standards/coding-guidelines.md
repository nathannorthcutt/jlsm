## Coding Guidelines

#### Iterative Over Recursive
- Prefer iterative algorithms throughout — no recursive tree traversals, no recursive merges, no recursive scans
- Recursive approaches risk stack overflow on deep structures (large compaction levels, long WAL segments); iterative state machines are preferred
- Where recursion is unavoidable, enforce an explicit maximum depth parameter; fail with a clear exception (e.g., `IllegalStateException("max recursion depth N exceeded")`) rather than allowing unbounded stack growth; the depth limit must be documented and configurable

#### Bounded Iteration and Timeouts
- Every blocking operation must have an explicit upper bound: use `poll(timeout, unit)` not `take()`, and provide configurable timeout via builder
- Every iteration must terminate: bounded loop counts, early termination conditions, or `hasNext()` guards on iterators
- Fail with an informative `IOException` on exhaustion or timeout; never block indefinitely

#### Graceful Error Handling
- Components must not cause a JVM crash — all exceptions must be caught and propagated as checked `IOException` or surfaced to callers
- Validate all inputs at public API boundaries eagerly (`Objects.requireNonNull`, explicit `IllegalArgumentException`) before any I/O begins
- Use `assert` statements throughout (public and private) to document internal invariants; these are for development-time verification and do not replace runtime validation
- Restore the interrupt flag (`Thread.currentThread().interrupt()`) when catching `InterruptedException` before re-throwing as `IOException`
- On `close()`, accumulate exceptions from multiple resources (deferred pattern) and throw after all resources are released; never suppress all exceptions silently in multi-close scenarios
- Detect and skip corrupt records during WAL replay (CRC mismatch); do not abort recovery on partial writes from prior crashes

#### Minimal Scope
- Prefer method-local variables over class fields whenever a value is not needed beyond the current method; do not promote a value to a field simply to avoid passing it as a parameter
- Class fields and instance variables must be at the lowest visibility level possible: `private` by default, package-private (no modifier) only when necessary for testing and `private` is not viable, never `public` or `protected` unless the field is part of the public API contract
- Prefer `final` fields and local variables wherever the value is not reassigned — this communicates intent and prevents accidental mutation
- Static (class-level) state should be reserved for true constants (`static final`) or thread-safe shared resources with an explicit lifecycle; mutable static fields are disallowed

#### Prefer Records
- Use `record` for any type that is a pure value holder: all fields set at construction, never reassigned, identity determined by fields alone — this eliminates mutation side effects by construction
- Prefer records over manually written immutable classes (`private final` fields + `equals`/`hashCode`) — the record form is less code and communicates immutability intent unambiguously
- Exempt: mutable stateful types (iterators, caches, writers, WALs, trees), builders, static utility classes, enums, interfaces, and abstract classes

#### Idempotency and Retryability
- Design all operations to be idempotent where possible — a repeated invocation with the same inputs must produce the same observable result and leave storage in the same state
- Idempotent write patterns: use `CREATE_NEW` semantics (fail-if-exists) combined with a content-addressed or sequence-numbered name so that a re-attempted write either succeeds cleanly or discovers an identical file already present
- When an operation cannot be made idempotent (e.g., a stateful counter increment with side effects), document this explicitly and throw `UnsupportedOperationException` with a clear message if a retry is attempted after partial completion — never silently corrupt state
- On transient failures (network timeout, S3 503), callers may safely retry idempotent operations; non-idempotent operations must surface a descriptive `IOException` subclass (e.g., `NonRetryableOperationException`) so callers can distinguish retryable from non-retryable failures
