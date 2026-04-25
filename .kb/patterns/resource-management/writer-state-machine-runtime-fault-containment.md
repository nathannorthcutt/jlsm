---
title: "Writer State Machine — RuntimeException Fault Containment in finish()"
aliases:
  - "writer finish() RuntimeException catch chain"
  - "callback NPE leaks past finish"
  - "writer-state OPEN-after-throw"
  - "FAILED-state guard bypassed by unchecked"
type: adversarial-finding
topic: "patterns"
category: "resource-management"
tags:
  - "writer-state-machine"
  - "exception-handling"
  - "RuntimeException"
  - "NullPointerException"
  - "callback"
  - "FAILED-state"
  - "fault-containment"
  - "one-shot-API"
research_status: "active"
confidence: "high"
last_researched: "2026-04-25"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableWriter.java"
related:
  - "patterns/resource-management/non-idempotent-close.md"
  - "patterns/resource-management/partial-init-no-rollback.md"
  - "patterns/validation/one-shot-api-missing-guard.md"
  - "patterns/concurrency/non-atomic-lifecycle-flags.md"
  - "patterns/resource-management/fan-out-dispatch-deferred-exception-pattern.md"
decision_refs: []
source_audit: "implement-encryption-lifecycle--wd-02"
sources:
  - url: "https://docs.oracle.com/javase/specs/jls/se21/html/jls-11.html"
    title: "JLS §11.1 — Kinds of Exceptions: checked vs unchecked"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://wiki.sei.cmu.edu/confluence/display/java/ERR07-J.+Do+not+throw+RuntimeException%2C+Exception%2C+or+Throwable"
    title: "SEI CERT ERR07-J — Do not throw RuntimeException, Exception, or Throwable"
    accessed: "2026-04-25"
    type: "docs"
  - title: "F-R1.resource_lifecycle.1.3 — TrieSSTableWriter.finish leaks RuntimeException from commitHook.acquire"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.resource_lifecycle.1.4 — TrieSSTableWriter.finish related RuntimeException path (already fixed in earlier prove-fix iteration; re-surfaced)"
    accessed: "2026-04-25"
    type: "audit-finding"
---

# Writer State Machine — RuntimeException Fault Containment in finish()

## summary

A long-lived writer (SSTable writer, WAL writer, segment writer) typically
declares `throws IOException` and a state machine with explicit
OPEN / FAILED / CLOSED states. Fault containment in `finish()` (or
`close()`) usually catches `IOException` and `ClosedByInterruptException`,
but the catch chain frequently omits `RuntimeException`. When a downstream
component — a commit hook, a listener, a fresh-scope re-resolver, a
caller-supplied callback — throws a RuntimeException (including a
`NullPointerException` from a callback that returns `null` where the
writer dereferences the result), the unchecked exception leaks past
`finish()` with state still `==OPEN`. Subsequent calls to `finish()` then
re-run on a writer holding inconsistent intermediate state, and the
FAILED-state defence (the one-shot-after-FAILED guard) is bypassed because
no transition to FAILED ever occurred.

The fix is to broaden `finish()`'s catch chain to `RuntimeException`,
transition to FAILED before rethrowing, and wrap the unchecked exception
in `IOException` preserving the original cause.

## problem

A typical writer:

```java
public final class TrieSSTableWriter {
    enum State { OPEN, FAILED, CLOSED }
    private volatile State state = State.OPEN;

    public Footer finish(String tableNameForLock) throws IOException {
        requireOpen();
        try {
            flushDataBlocks();
            writeIndex();
            writeBloomFilter();
            CommitToken token = commitHook.acquire(tableNameForLock);  // user-supplied
            Footer footer = writeFooter(token);                         // (A)
            state = State.CLOSED;
            return footer;
        } catch (IOException e) {
            state = State.FAILED;
            throw e;
        }
    }

    private void requireOpen() {
        if (state == State.FAILED) throw new IllegalStateException("writer is FAILED — abandon");
        if (state == State.CLOSED) throw new IllegalStateException("writer is CLOSED");
    }
}
```

Three failure modes follow:

1. **Callback returns null.** `commitHook.acquire(...)` returns `null`;
   the next line dereferences it (or unwraps it via
   `Objects.requireNonNull` later). Either way, an NPE is thrown. The
   catch chain matches only `IOException`, so the NPE escapes with
   `state == OPEN`. A retrying caller calls `finish()` again, passes
   `requireOpen()`, and re-runs `flushDataBlocks` on a writer that
   already wrote some data blocks — corrupting the file.
2. **Callback throws a domain RuntimeException.** A commit hook decides
   the table is locked by another writer and throws
   `IllegalStateException`. Same leakage; same state-machine corruption.
3. **Downstream IO library throws unchecked.** Some channel
   implementations (notably `S3SeekableByteChannel` wrappers) throw
   `RuntimeException` for transient SDK errors rather than `IOException`.
   Same outcome.

In all three cases, the writer's FAILED-state defence — the `requireOpen`
guard at the top of `finish` and at the top of every other public
method — relies on the writer transitioning to FAILED. The transition
only happens inside the `catch (IOException e)` block. Unchecked
exceptions bypass it.

## symptoms

- A regression test that injects a null-returning commit hook and asserts
  that a subsequent `finish()` call is rejected with the
  FAILED-state guard fails — instead, the second `finish()` proceeds and
  produces either corrupt output or a different exception that hides the
  original NPE.
- An audit's resource_lifecycle lens flags the construct because the
  cause-preservation contract ("the original RuntimeException is wrapped
  and presented to the caller") is satisfied only for IOException.
- Production logs show an NPE escape from a writer's `finish()` followed
  by a successful retry that produced a different error several minutes
  later — a clear signature that state was not transitioned to FAILED on
  the first attempt.
- A call to a sibling method (`abort()` or `discardAndRelease()`) on the
  failed writer succeeds when it should be rejected — because the writer
  thinks it is still OPEN, the abort path may try to write a final
  cleanup record on top of partial data and corrupt the file further.

## root-cause

Two cooperating misconceptions:

- **`throws IOException` covers all relevant failure modes.** Authors
  implicitly assume that any failure inside `finish()` will be an
  `IOException` because everything inside is I/O. But callbacks,
  validators, hash builders, codec lookups, and even `Objects.requireNonNull`
  on a callback's return value can throw unchecked exceptions that the
  declared method signature does not advertise.
- **`RuntimeException` is "programmer error" — it should propagate
  unmodified.** SEI CERT ERR07-J encourages methods not to declare
  `RuntimeException` in their `throws` clauses. But that is about the
  signature; it does not say the method should not catch unchecked
  exceptions. Inside a finish() that owns a state machine, *all*
  exceptions invalidate the state — checked or not — and need to drive
  the transition to FAILED.

## fix

Broaden the catch chain to include `RuntimeException` and `Error`
(subject to the Error caveat), transition to FAILED, and wrap the cause
in IOException:

```java
public Footer finish(String tableNameForLock) throws IOException {
    requireOpen();
    try {
        flushDataBlocks();
        writeIndex();
        writeBloomFilter();
        CommitToken token = commitHook.acquire(tableNameForLock);
        Objects.requireNonNull(token, "commitHook.acquire returned null");
        Footer footer = writeFooter(token);
        state = State.CLOSED;
        return footer;
    } catch (IOException e) {
        state = State.FAILED;
        throw e;
    } catch (RuntimeException e) {
        state = State.FAILED;
        throw new IOException(
            "writer finish() failed with unchecked exception (state=FAILED)", e);
    }
}
```

Rules for the fix:

1. **Single state transition before rethrow.** The transition to FAILED
   must occur before the throw on the catch path. If the throw happens
   first and the transition runs in a `finally`, a thread observing the
   exception can see `state == OPEN` racing with the not-yet-run
   `finally`. Order: `state = FAILED; throw new IOException(...)`.
2. **Preserve the cause.** `new IOException(message, cause)` preserves
   the original RuntimeException as `getCause()`. Callers logging the
   exception capture the full chain. Never use `new IOException(message)`
   alone in the catch — the original cause is lost.
3. **Catch `RuntimeException`, not `Throwable`.** Catching `Throwable`
   would also catch `Error` (`OutOfMemoryError`,
   `StackOverflowError`) which are typically not recoverable and where
   wrapping in `IOException` is misleading. The standard Java guidance
   is to let `Error` propagate. If the writer must clean up off-heap or
   external resources even on `Error`, use a `finally` block that
   transitions state to FAILED but does not catch.
4. **Enforce the same discipline in companion methods.** `abort()`,
   `close()`, `discardAndRelease()` — every public method on the writer
   that may catch IOException must equally catch RuntimeException to
   keep the state machine consistent. A test that checks finish() but
   not close() leaves the same hole one method over.
5. **Document the contract change.** The `finish()` Javadoc must say
   "throws IOException — including IOException wrapping a RuntimeException
   from a callback". Without this, a caller may add a separate
   catch (RuntimeException) higher in the call stack to handle "internal
   errors" and accidentally silence the wrapped form.

## verification

Three adversarial tests:

1. **Null-returning callback.** Inject a commit hook that returns `null`.
   Assert: `finish()` throws `IOException` with cause
   `NullPointerException`; subsequent `finish()` call rejected with
   "writer is FAILED"; subsequent `abort()` rejected too (or accepted with
   an explicit FAILED-tolerant abort path); the writer's underlying
   resources were released exactly once.
2. **Throwing callback.** Inject a commit hook that throws
   `IllegalStateException("table locked")`. Same assertions.
3. **Downstream library unchecked.** Inject a writeBloomFilter
   implementation that throws `UncheckedIOException` (e.g., from a
   network-backed bloom store). Same assertions; verify cause chain
   preserves the wrapped IOException via `((IOException) e.getCause()).getCause()`.

Each test must verify all three observable consequences:

- (a) the original RuntimeException's information is preserved (cause
  chain, message);
- (b) the writer state is FAILED, not OPEN;
- (c) a subsequent `finish()` is rejected by the state guard.

## relationship to other patterns

- `one-shot-api-missing-guard`: that pattern enforces the FAILED-state
  guard in `requireOpen`. This pattern ensures the FAILED state is
  *reached* in the first place — the guard is meaningful only if the
  state transitions correctly.
- `non-idempotent-close`: the CLOSED-state guard mirrors the FAILED-state
  guard; the same broadening of the catch chain applies in `close()`.
- `partial-init-no-rollback`: the writer's state machine *is* the
  rollback story for `finish()` — entering FAILED is the rollback marker.
  Without the catch-chain broadening, partial init has no rollback for
  unchecked exceptions.
- `fan-out-dispatch-deferred-exception-pattern`: in `close()` paths that
  must release multiple resources, the deferred-exception accumulator
  must include unchecked exceptions, not just IOException.

## tradeoffs

**Strengths.** Closes the entire class of "callback throws unchecked,
state stays OPEN" bugs in five lines per writer. Cause preservation via
`new IOException(message, cause)` keeps debugging information intact.
Aligns the writer's contract with what callers expect: any failure in
finish() puts the writer in FAILED.

**Weaknesses.** Catching RuntimeException is widely flagged as a code
smell by static analysis tools (SpotBugs `REC_CATCH_EXCEPTION` and
similar). The discipline must be applied selectively — only at
state-machine boundaries, never as a general-purpose catch — and
documented inline so reviewers do not "fix" it. The wrapper IOException
hides the unchecked nature of the underlying problem from callers who
specifically catch RuntimeException; if any caller depends on that
distinction, they must catch IOException and inspect the cause chain.

## when-to-apply

Any writer/closer/finalizer that:

- Owns an explicit state machine with a FAILED state, and
- Calls user-supplied or third-party callbacks in its `finish()` or
  `close()` body, and
- Declares only checked exceptions in its signature.

Specifically:

- SSTable writers with commit hooks.
- WAL writers with sync listeners.
- Compaction-output writers with manifest-update callbacks.
- Any writer wrapping a remote-backed channel where the channel may
  throw unchecked.

**When not to apply.** Pure I/O methods with no callbacks, no validators,
no derived computations — the chance of an unchecked exception is
genuinely low and the wrapping adds noise. (In practice, most writers
have at least one callback or validator, so this exception is narrow.)

## reference-implementation

`modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableWriter.java`
— `finish(String tableNameForLock)` includes a `catch (RuntimeException
e)` clause that transitions to FAILED and rethrows wrapped in IOException.

Audit findings that surfaced the pattern (2 findings on the same
construct, on subtly different RuntimeException paths through the same
finish() body):

- `F-R1.resource_lifecycle.1.3` — RuntimeException from
  `commitHook.acquire(tableNameForLock)` previously leaked past the
  try/catch chain, leaving the writer at `state == OPEN` (confirmed-and-fixed
  in this prove-fix iteration).
- `F-R1.resource_lifecycle.1.4` — same construct; a related
  RuntimeException path was self-cleared as a duplicate of 1.3 once the
  catch chain broadened.

WD-02 ciphertext-format audit, 2026-04-25.

## sources

1. [JLS §11.1 — Kinds of Exceptions](https://docs.oracle.com/javase/specs/jls/se21/html/jls-11.html) — checked vs unchecked taxonomy; `RuntimeException` does not need to appear in `throws` clauses, which is the structural reason this bug class exists.
2. [SEI CERT ERR07-J — Do not throw RuntimeException, Exception, or Throwable](https://wiki.sei.cmu.edu/confluence/display/java/ERR07-J.+Do+not+throw+RuntimeException%2C+Exception%2C+or+Throwable) — guidance on what to throw; this pattern is about what to *catch* at state-machine boundaries, which is a complementary concern.

---
*Researched: 2026-04-25 | Next review: 2026-07-25*
