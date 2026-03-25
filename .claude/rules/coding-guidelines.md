## Coding Guidelines

#### Embrace Modern Java
- Use the latest Java language features: pattern matching (`switch` expressions with patterns, `instanceof` patterns, record patterns), sealed interfaces, records, text blocks, and unnamed variables (`_`)
- Prefer `MemorySegment` and the Foreign Function & Memory (Panama) API (`java.lang.foreign`) over `byte[]` and `ByteBuffer` in performance-critical code — Panama provides deterministic deallocation via `Arena`, better cache locality, and zero-copy interop with native memory
- Use `Arena.ofConfined()` or `Arena.ofShared()` for explicit lifetime management of off-heap memory; avoid `Arena.ofAuto()` in hot paths (see io-internals.md)
- Prefer `MemorySegment` bulk operations (`copyFrom`, `mismatch`, `fill`) over manual byte-by-byte loops
- Use `VarHandle` or `ValueLayout` with explicit byte order for structured access to `MemorySegment` data — avoid `Unsafe`
- Use `jdk.incubator.vector` (Vector API) for SIMD-accelerated loops where the data is contiguous and the operation is element-wise (e.g., distance computations, batch encoding)
- Use virtual threads (`Thread.ofVirtual()`) for I/O-bound concurrency; reserve platform threads for CPU-bound work
- Prefer `Gatherers` for complex stream pipelines where a single custom intermediate operation replaces multiple chained steps

#### Memory and Resource Discipline
- Assume a constrained memory and disk environment at all times — this is a library that may run in containers, embedded systems, or processes with strict `-Xmx` limits
- Scrutinize every allocation: prefer pooled or reusable buffers (e.g., `ArenaBufferPool`) over fresh allocations in hot paths; avoid `Arena.ofAuto()` and `MemorySegment.ofArray()` where the pool is available
- Bound all in-memory collections — every `Map`, `List`, or queue that grows with input must have a configured capacity or eviction policy; unbounded growth is a crash waiting to happen
- Prefer streaming/iterative processing over materializing full result sets into memory; use `Iterator` patterns rather than collecting into `List` when the consumer processes elements sequentially
- Release resources eagerly — close `Arena` instances, flush and close channels, and return pooled buffers in `finally` blocks; do not rely on GC or finalizers for resource cleanup
- When disk space may be limited, check available space before large writes where feasible and fail with a clear `IOException` rather than producing a corrupt partial file

#### Graceful Error Handling
- Components must not cause a JVM crash — all exceptions must be caught and propagated as checked `IOException` or surfaced to callers
- Validate all inputs at public API boundaries eagerly (`Objects.requireNonNull`, explicit `IllegalArgumentException`) before any I/O begins
- Use `assert` statements throughout (public and private) to document internal invariants; these are for development-time verification and do not replace runtime validation
- Restore the interrupt flag (`Thread.currentThread().interrupt()`) when catching `InterruptedException` before re-throwing as `IOException`
- On `close()`, accumulate exceptions from multiple resources (deferred pattern) and throw after all resources are released; never suppress all exceptions silently in multi-close scenarios
- Detect and skip corrupt records during WAL replay (CRC mismatch); do not abort recovery on partial writes from prior crashes

#### Bounded Iteration and Timeouts
- Every blocking operation must have an explicit upper bound: use `poll(timeout, unit)` not `take()`, and provide configurable timeout via builder
- Every iteration must terminate: bounded loop counts, early termination conditions, or `hasNext()` guards on iterators
- Fail with an informative `IOException` on exhaustion or timeout; never block indefinitely

#### Iterative Over Recursive
- Prefer iterative algorithms throughout — no recursive tree traversals, no recursive merges, no recursive scans
- Recursive approaches risk stack overflow on deep structures (large compaction levels, long WAL segments); iterative state machines are preferred
- Where recursion is unavoidable, enforce an explicit maximum depth parameter; fail with a clear exception (e.g., `IllegalStateException("max recursion depth N exceeded")`) rather than allowing unbounded stack growth; the depth limit must be documented and configurable

#### Minimal Scope
- Prefer method-local variables over class fields whenever a value is not needed beyond the current method; do not promote a value to a field simply to avoid passing it as a parameter
- Class fields and instance variables must be at the lowest visibility level possible: `private` by default, package-private (no modifier) only when necessary for testing and `private` is not viable, never `public` or `protected` unless the field is part of the public API contract
- Prefer `final` fields and local variables wherever the value is not reassigned — this communicates intent and prevents accidental mutation
- Static (class-level) state should be reserved for true constants (`static final`) or thread-safe shared resources with an explicit lifecycle; mutable static fields are disallowed

#### Prefer Records
- Use `record` for any pure value holder: all fields set at construction, never reassigned, identity determined by fields alone
- Prefer records over manually written immutable classes (`private final` fields + `equals`/`hashCode`) — the record form is less code and communicates immutability intent unambiguously
- Exempt: any mutable or stateful type (iterators, caches, writers, WALs, trees, builders), as well as static utility classes, enums, interfaces, and abstract classes
