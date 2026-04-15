---
problem: "automatic-backend-detection"
evaluated: "2026-04-14"
candidates:
  - name: "FileStore.getBlockSize() as auto-default"
    source: "java.nio.file.FileStore API"
  - name: "FileStore.getBlockSize() opt-in method"
    source: "java.nio.file.FileStore API"
  - name: "Close (status quo)"
    source: "backend-optimal-block-size ADR"
  - name: "Scheme-based default lookup"
    source: "java.nio.file.FileSystem.provider().getScheme()"
  - name: "Pool-aware block size configuration"
    source: "ArenaBufferPool resource management layer"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 3
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — automatic-backend-detection

## References
- Constraints: [constraints.md](constraints.md)
- Parent ADR: [backend-optimal-block-size](../backend-optimal-block-size/adr.md)

## Constraint Summary
The primary constraints are zero-surprise behavior (wrong detection is worse than
no detection), zero-cost operation (no I/O at writer construction), and fit within
the existing NIO-based architecture. The library is pure Java 25 with no external
dependencies.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Must work across local/remote backends but not the primary concern |
| Resources | 1 | Pure Java, minimal impact |
| Complexity | 3 | Zero-surprise is critical — wrong auto-detection is worse than none |
| Accuracy | 3 | Detected/configured value must be correct or safe-fallback |
| Operational | 3 | No hidden I/O or blocking at construction time |
| Fit | 2 | Should align with existing architecture patterns |

---

## Candidate: FileStore.getBlockSize() as auto-default

**Source:** `java.nio.file.FileStore.getBlockSize()` (Java 10+)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | Works for any NIO provider implementing getBlockSize() |
| Resources | 1 | 5 | 5 | Pure JDK API |
| Complexity | 3 | 3 | 9 | Hidden I/O: Files.getFileStore() performs stat call; may block on remote providers |
|            |   |   |   | **Would be a 2 if:** S3 provider returns 4096 (internal chunk size) but optimal is 8 MiB |
| Accuracy | 3 | 3 | 9 | getBlockSize() returns storage allocation unit, not optimal I/O unit — semantic mismatch |
|          |   |   |   | **Would be a 2 if:** provider returns valid power-of-2 that silently degrades performance |
| Operational | 3 | 2 | 6 | Files.getFileStore() performs I/O — stat on local, potentially HEAD on S3 |
| Fit | 2 | 4 | 8 | Standard NIO API, path already available |
| **Total** | | | **45** | |

**Hard disqualifiers:** Violates zero-cost constraint (performs I/O at construction).

---

## Candidate: FileStore.getBlockSize() opt-in method

**Source:** `java.nio.file.FileStore.getBlockSize()`, gated behind explicit builder call

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | Same coverage as auto-default |
| Resources | 1 | 5 | 5 | Pure JDK API |
| Complexity | 3 | 5 | 15 | Explicit opt-in — caller controls when detection runs |
| Accuracy | 3 | 3 | 9 | Semantic mismatch persists — opt-in doesn't fix what getBlockSize() means |
|          |   |   |   | **Would be a 2 if:** callers use it expecting optimal I/O size, get allocation unit |
| Operational | 3 | 4 | 12 | Caller chooses when to pay stat cost |
| Fit | 2 | 5 | 10 | Extends existing builder API |
| **Total** | | | **59** | |

**Key weakness:** Wrapping an unreliable signal in an opt-in API gives callers
a false sense of having configured correctly.

---

## Candidate: Close (status quo)

**Source:** [backend-optimal-block-size ADR](../backend-optimal-block-size/adr.md)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 3 | 6 | Works everywhere but no help for naive users |
| Resources | 1 | 5 | 5 | No code to add |
| Complexity | 3 | 5 | 15 | Zero complexity |
| Accuracy | 3 | 5 | 15 | Never wrong — caller explicitly sets what they want |
| Operational | 3 | 5 | 15 | No hidden I/O |
| Fit | 2 | 3 | 6 | Doesn't leverage NIO metadata or align block/buffer sizes |
| **Total** | | | **62** | |

---

## Candidate: Scheme-based default lookup

**Source:** `path.getFileSystem().provider().getScheme()` with static mapping

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | Works for known schemes; safe fallback for unknown |
| Resources | 1 | 5 | 5 | Pure string comparison |
| Complexity | 3 | 5 | 15 | Static map lookup, trivially understandable |
| Accuracy | 3 | 4 | 12 | Scheme is a reliable storage class identifier |
|          |   |   |   | **Would be a 2 if:** custom provider reuses "file" scheme for remote backend |
| Operational | 3 | 5 | 15 | Zero I/O — scheme is in-memory string |
| Fit | 2 | 4 | 8 | Uses NIO API; closed set needs maintenance |
| **Total** | | | **63** | |

**Key weakness:** Closed set of known schemes requires updates for new providers.

---

## Candidate: Pool-Aware Block Size Configuration

**Source:** ArenaBufferPool resource management layer + TrieSSTableWriter builder

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 5 | 10 | Works for all backends — user configures once |
|       |   |   |    | **Would be a 2 if:** per-table block size variation is the common case |
| Resources | 1 | 5 | 5 | No new deps; extends existing pool/writer relationship |
| Complexity | 3 | 5 | 15 | Reduces config surface — one place instead of two independent knobs |
|            |   |   |    | **Would be a 2 if:** pool/writer coupling creates circular dependency |
| Accuracy | 3 | 5 | 15 | Always correct — explicit configuration, no guessing |
| Operational | 3 | 5 | 15 | Zero I/O; block+buffer sizes aligned by construction |
| Fit | 2 | 5 | 10 | Natural extension of ArenaBufferPool as resource management layer |
|     |   |   |    | **Would be a 2 if:** writer fundamentally cannot use pooled buffers |
| **Total** | | | **70** | |

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| FileStore auto-default | 8 | 5 | 9 | 9 | 6 | 8 | **45** |
| FileStore opt-in | 8 | 5 | 15 | 9 | 12 | 10 | **59** |
| Close (status quo) | 6 | 5 | 15 | 15 | 15 | 6 | **62** |
| Scheme-based default | 8 | 5 | 15 | 12 | 15 | 8 | **63** |
| **Pool-aware config** | **10** | **5** | **15** | **15** | **15** | **10** | **70** |

## Recommendation
Pool-aware block size configuration wins on weighted total (70 vs 63 next-best).
The framing shift from "detect the backend" to "configure deployment context in
the resource management layer" resolves the original problem while eliminating a
latent misconfiguration vector (independent block/buffer size knobs).

## Risks and Open Questions
- Risk: per-table block size variation may be needed (mitigated by explicit override)
- Risk: writer may need block sizes that don't match pool buffer sizes in edge cases
- Open: exact API shape for pool→writer block size flow (pool accessor vs shared config record)
