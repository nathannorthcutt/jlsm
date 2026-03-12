## I/O Internals

> **When to load this file**: load this file when working on WAL, SSTable reads/writes, compaction merges, buffer pool usage, or any remote-backend (S3, GCS) code path.

### Remote/Network-Backed File Store Support

- Use `java.nio.file.Path` + `SeekableByteChannel` (Java NIO FileSystem SPI) for all I/O — this allows S3, GCS, and other remote filesystems to be plugged in via third-party NIO providers
- Avoid `FileChannel`-only features (e.g., `mmap`, `force()`) in code paths that must be remote-compatible; use conditional dispatch (`if (ch instanceof FileChannel fc)`) to apply local-only optimizations
- Remote-compatible WAL: the one-file-per-record pattern (`RemoteWriteAheadLog`) is preferred for backends that do not support seek/overwrite semantics

### Off-Heap Memory via ArenaBufferPool

- Allocate buffers via `ArenaBufferPool` (backed by `Arena.ofShared()`) wherever segments are needed in hot paths (WAL appends, compaction merges, SSTable reads/writes)
- Avoid `MemorySegment.ofArray()` and `Arena.ofAuto()` in hot paths — these allocate heap or unconstrained off-heap memory that bypasses the pool and breaks externally configured memory budgets
- The pool enforces a fixed upper bound on off-heap memory; all callers must `acquire()` before use and `release()` in a `finally` block

### Idempotency and Retryability

- Design all operations to be idempotent where possible — a repeated invocation with the same inputs must produce the same observable result and leave storage in the same state
- Idempotent write patterns: use `CREATE_NEW` semantics (fail-if-exists) combined with a content-addressed or sequence-numbered name so that a re-attempted write either succeeds cleanly or discovers an identical file already present
- When an operation cannot be made idempotent (e.g., a stateful counter increment with side effects), document this explicitly and throw `UnsupportedOperationException` with a clear message if a retry is attempted after partial completion — never silently corrupt state
- On transient failures (network timeout, S3 503), callers may safely retry idempotent operations; non-idempotent operations must surface a descriptive `IOException` subclass (e.g., `NonRetryableOperationException`) so callers can distinguish retryable from non-retryable failures
