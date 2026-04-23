---
type: adversarial-finding
title: "Torn Volatile Publication of Multi-Field State"
topic: "patterns"
category: "concurrency"
tags: ["concurrency", "publication", "happens-before", "volatile", "lifecycle"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "non-atomic-lifecycle-flags"
  - "check-then-act-across-paired-acquire-release"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources:
  - url: "https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html"
    title: "JLS Chapter 17 — Threads and Locks"
    accessed: "2026-04-22"
    type: "docs"
---

# Torn Volatile Publication of Multi-Field State

## What Happens

A lifecycle or failure-state transition publishes two or more independent
`volatile` fields (e.g., `failureCause` plus `failureSection`) without an
ordered-publish contract between them. A concurrent observer performing two
unlocked loads — one per field — can see one field set and the other not:
a "torn" intermediate state.

Typical symptoms:

- Diagnostic rendering contains a literal `"null"` in the non-null-cause
  branch (`"reader failed: null"` when `cause.getMessage()` is present but
  `section` not yet observed).
- A FAILED-state predicate fails to fire because the observer reads a stale
  sibling field ahead of the driver.
- In-process writers may never observe the torn state because the writer's
  program order is a happens-before edge, but reflective observers, tools,
  and out-of-process diagnostics can.

## Why It Happens

`volatile` guarantees that each individual field read sees the most recent
write, but a read of field A happens-before a read of field B only if
serialized through another synchronization action. Two independent volatile
reads in the observer thread are not serialized.

On the writer side, two independent volatile writes create two independent
publication edges. The observer can interleave between them.

## Fix Patterns

1. **Combined reference.** Publish the multi-field state as a single
   reference — a record or `AtomicReference<StateRecord>` holding both
   fields. One CAS/one volatile write publishes the whole state:

   ```java
   record Failure(String section, Throwable cause) {}
   private final AtomicReference<Failure> failure = new AtomicReference<>();

   // writer
   failure.compareAndSet(null, new Failure(section, cause));

   // reader
   Failure f = failure.get();
   if (f != null) throw new IllegalStateException(
       "reader failed: " + f.section(), f.cause());
   ```

2. **Ordered publish + sentinel rendering.** If two fields must remain
   separate (legacy API, existing data layout), establish a strict writer
   ordering — write `failureSection` BEFORE `failureCause`, so an observer
   seeing a non-null cause is guaranteed to see a non-null section.
   Document the read-side sentinel rendering for any field the reader might
   observe as null during the torn window:

   ```java
   // writer — section first, then cause
   this.failureSection = section;  // volatile write #1
   this.failureCause = cause;      // volatile write #2 — publishes the
                                   // entire failure state under R43 rules

   // reader — torn-state-tolerant rendering
   Throwable cause = failureCause;
   if (cause != null) {
       String section = failureSection;
       String rendered = section != null ? section : "<unknown>";
       throw new IllegalStateException("reader failed: " + rendered, cause);
   }
   ```

## Detection

Concurrency lens probe: inject a half-published state via reflection (assign
`failureCause`, leave `failureSection` null) and assert the diagnostic
message does not contain the literal `"null"`. The half-publication models
the happens-before gap between two volatile writes in a single publisher
thread.

## Seen In

- `TrieSSTableReader.checkNotFailed` / `transitionToFailed` / `failureCause` /
  `failureSection` — audit finding F-R1.concurrency.1.4 (torn read rendering
  `"null"`) and F-R1.concurrency.1.6 (missing write-site for R43 FAILED-state
  on normal block-read path). Cross-domain composition XD-R1.5 surfaced the
  same root from both the concurrency lens (read-side torn render) and
  shared_state lens (write-side ordered publish).

## Test Guidance

- Build a reflective-injection helper that installs exactly one of the
  fields and leaves the sibling null.
- Assert that the published diagnostic never contains `"null"` as the
  section name and always carries the injected cause via `getCause()`.
- Include a second test that publishes both fields in reverse order (cause
  before section) and confirm the diagnostic still makes sense — this
  exercises the full torn window across both directions.
