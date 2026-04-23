---
type: adversarial-finding
title: "Atomic-Move vs Non-Atomic-Fallback Semantic Divergence in Commit Paths"
topic: "patterns"
category: "resource-management"
tags: ["commit", "atomic-move", "filesystem-semantics", "posix", "remote-backend"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableWriter.java"
related:
  - "destructive-error-recovery"
  - "non-idempotent-close"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources:
  - url: "https://man7.org/linux/man-pages/man2/rename.2.html"
    title: "rename(2) — Linux man page"
    accessed: "2026-04-22"
    type: "docs"
---

# Atomic-Move vs Non-Atomic-Fallback Semantic Divergence in Commit Paths

## What Happens

A commit path uses `Files.move(src, dst, ATOMIC_MOVE)` with a fallback to
`Files.move(src, dst)` on `AtomicMoveNotSupportedException`. POSIX
`rename(2)` silently overwrites an existing `dst`; the non-atomic fallback
(without `REPLACE_EXISTING`) throws `FileAlreadyExistsException`. Behavior
therefore diverges by filesystem, and on POSIX a pre-existing committed
file can be silently destroyed.

Canonical anti-pattern:

```java
try {
    Files.move(partial, finalPath, StandardCopyOption.ATOMIC_MOVE);
    // on POSIX: silently overwrites an existing finalPath
} catch (AtomicMoveNotSupportedException e) {
    Files.move(partial, finalPath);
    // throws FileAlreadyExistsException if finalPath exists
}
```

Consequence: a retry after a transient failure may destroy a legitimate
committed file if the retry targets the same final path.

## Why It Happens

POSIX `rename(2)` was designed to make renames atomic and has done so by
allowing the target to be replaced. Java's `ATOMIC_MOVE` inherits that
semantic. The NIO fallback API does not expose an `ATOMIC_OVERWRITE`
option, so the fallback fails loudly where the atomic path succeeds
silently. Most code authors test only one filesystem.

## Fix Pattern

Add a pre-check (`Files.exists(dst)` or equivalent atomic-check via
`CREATE_NEW` on a handshake file) that throws `FileAlreadyExistsException`
uniformly before either move branch runs:

```java
if (Files.exists(finalPath))
    throw new FileAlreadyExistsException(finalPath.toString());

try {
    Files.move(partial, finalPath, StandardCopyOption.ATOMIC_MOVE);
} catch (AtomicMoveNotSupportedException e) {
    Files.move(partial, finalPath);
}
```

A TOCTOU window exists between the `exists` check and the `move`; to
close it entirely, use a content-addressed final path (unique suffix per
writer/content) so the commit target never collides with a prior commit.
For the local-filesystem case where content-addressing is not viable,
the pre-check narrows the window to microseconds, and the writer-unique
partial path plus `CREATE_NEW` semantics on the handshake ensure the
window cannot extend across process restarts.

## Detection

Data-transformation lens wrote a sentinel byte array to `outputPath`,
then ran the writer's `finish()` targeting the same path, asserted
`FileAlreadyExistsException` AND that the sentinel content remained
intact. Exercised on an ext4 filesystem where the `ATOMIC_MOVE` path is
available.

## Seen In

- `TrieSSTableWriter.commitFromPartial` — audit finding
  F-R1.data_transformation.1.1.

## Test Guidance

- Write a sentinel file at the writer's target path; run `finish()`;
  assert `FileAlreadyExistsException` AND read the sentinel back
  byte-exact after the throw.
- Cover both filesystem cases: use a MemoryFileSystem (or equivalent
  NIO provider) for the non-atomic-fallback path; use the local POSIX
  filesystem for the atomic path. Both must throw.
- Cover the retry case: first call fails mid-commit (inject an
  IOException), leaving a partial file; retry the commit on the same
  target with a fresh writer; assert the retry fails cleanly without
  destroying the still-existing partial from the first attempt.

## Scope

Pattern is a cross-cutting hazard for every commit path in jlsm: WAL
rollover, SSTable commit, manifest update, snapshot finalize. A KB entry
here establishes the defensive idiom for the whole codebase. A uniform
pre-check is the minimum; content-addressed commit is the stronger form
for paths that can accommodate it.
