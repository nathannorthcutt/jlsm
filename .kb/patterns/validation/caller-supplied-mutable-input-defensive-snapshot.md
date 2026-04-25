---
title: "Caller-Supplied Mutable Input — Defensive Snapshot at Method Entry"
aliases:
  - "validate-vs-use TOCTOU on mutable input"
  - "input-side defensive snapshot"
  - "caller mutates between validate and consume"
type: adversarial-finding
topic: "patterns"
category: "validation"
tags:
  - "TOCTOU"
  - "defensive-copy"
  - "input-validation"
  - "snapshot"
  - "encoder-contract"
  - "CWE-367"
research_status: "active"
confidence: "high"
last_researched: "2026-04-25"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/V6Footer.java"
related:
  - "patterns/validation/mutable-state-escaping-builder.md"
  - "patterns/resource-management/defensive-copy-accessor-defeats-zeroize-on-close.md"
  - "data-structures/mutable-array-in-record.md"
  - "patterns/validation/untrusted-storage-byte-length.md"
decision_refs: []
source_audit: "implement-encryption-lifecycle--wd-02"
sources:
  - url: "https://cwe.mitre.org/data/definitions/367.html"
    title: "CWE-367 — Time-of-check Time-of-use (TOCTOU) Race Condition"
    accessed: "2026-04-25"
    type: "docs"
  - url: "https://wiki.sei.cmu.edu/confluence/display/java/OBJ06-J.+Defensively+copy+mutable+inputs+and+mutable+internal+components"
    title: "SEI CERT OBJ06-J — Defensively copy mutable inputs and mutable internal components"
    accessed: "2026-04-25"
    type: "docs"
  - title: "F-R1.contract_boundaries.1.001 — V6Footer.encodeScopeSection"
    accessed: "2026-04-25"
    type: "audit-finding"
  - title: "F-R1.contract_boundaries.1.002 — V6Footer.Parsed canonical constructor"
    accessed: "2026-04-25"
    type: "audit-finding"
---

# Caller-Supplied Mutable Input — Defensive Snapshot at Method Entry

## summary

A method that accepts a caller-supplied mutable container — `int[]`,
`Set<Integer>`, `Map<K, V>`, `List<E>` — and uses it across two or more
distinct phases (validate, then encode; validate, then store; validate, then
publish) is exposed to a TOCTOU window between the validation phase and the
consume phase. If the caller mutates the container after the method returns,
or — for non-trivial methods — between method entry and the consume phase,
the method's output diverges from what was validated. The defensive technique
is a **snapshot at method entry** — `array.clone()`, `Set.copyOf(set)`,
`List.copyOf(list)`, `Map.copyOf(map)` — and operate exclusively on the
snapshot for the remainder of the method, including the validation step
itself.

## problem

The bug arises when a method's contract reads as a single atomic
"validate-and-encode" but the implementation interleaves caller-visible
references with internal computation:

```java
static int encodeScopeSection(int[] sortedDekVersions, ByteBuffer out) {
    // PHASE A — validate caller's array (reads sortedDekVersions[i])
    for (int i = 1; i < sortedDekVersions.length; i++) {
        if (sortedDekVersions[i] <= sortedDekVersions[i - 1]) {
            throw new IllegalArgumentException("not strictly ascending");
        }
    }
    // PHASE B — encode (reads sortedDekVersions[i] again, separately)
    for (int v : sortedDekVersions) {
        out.putInt(v);
    }
    return sortedDekVersions.length * 4;
}
```

There are two distinct attack surfaces:

1. **Caller mutates after return** — for methods that retain a reference (a
   record's canonical constructor that stores the array as a field, a builder
   that defers consumption), the validated state is captured but the stored
   reference still aliases the caller's array. Subsequent mutation by the
   caller corrupts the stored state. (CWE-374-adjacent.)
2. **Caller mutates between phases** — for methods running in a context where
   the caller can interleave (multi-threaded, listener-triggered, or where
   PHASE A invokes any code that could yield the thread), the array passes
   validation under one set of values but is encoded under another. (CWE-367.)

For records with canonical constructors, the second pattern is especially
subtle: the canonical constructor's body runs `requireNonNull` and validates
the elements, but if it then stores the original reference rather than a
defensive copy, every later accessor exposes whatever the caller has done to
their copy in the meantime.

## symptoms

- A regression test mutates the caller-supplied container after the method
  returns and observes that the encoded bytes (or a downstream accessor)
  reflects the post-mutation state, contradicting what was validated.
- A second regression test mutates the container in a thread interleaved
  between the validate phase and the encode phase, and observes that the
  encoded bytes do not satisfy the documented invariant (e.g., "strictly
  ascending") because the encode phase read different values than the
  validate phase.
- An audit's contract_boundaries lens flags the construct because the
  declared input contract ("strictly ascending") is enforced on the input
  reference rather than on a snapshot the method controls.

## root-cause

Two intertwined assumptions:

- **The validation step happens once.** Authors reason about the contract at
  PHASE A and assume the values do not change before PHASE B. In a
  single-threaded encoder this seems obvious — but the encoder gains a hidden
  dependency on caller cooperation that is not in the method signature.
- **A canonical constructor's `requireNonNull` is sufficient defence.** It
  defends only against the null pointer; it does not produce a snapshot. The
  resulting record is technically immutable (its component references do not
  change) but its state is observably mutable through the surviving caller
  reference.

These assumptions are individually plausible. The bug is the composition: a
caller-supplied mutable container plus a validate-then-consume body plus no
defensive snapshot.

## fix

Snapshot at method entry — before validation runs — and operate exclusively
on the snapshot:

```java
static int encodeScopeSection(int[] sortedDekVersions, ByteBuffer out) {
    Objects.requireNonNull(sortedDekVersions, "sortedDekVersions");
    final int[] snapshot = sortedDekVersions.clone();   // entry snapshot

    for (int i = 1; i < snapshot.length; i++) {
        if (snapshot[i] <= snapshot[i - 1]) {
            throw new IllegalArgumentException("not strictly ascending");
        }
    }
    for (int v : snapshot) {
        out.putInt(v);
    }
    return snapshot.length * 4;
}
```

For record canonical constructors:

```java
public record Parsed(..., Set<Integer> dekVersionSet) {
    public Parsed {
        Objects.requireNonNull(dekVersionSet, "dekVersionSet");
        dekVersionSet = Set.copyOf(dekVersionSet);   // entry snapshot, immutable
        // remaining validation operates on the snapshot
        for (Integer v : dekVersionSet) {
            if (v == null || v < 0) throw new IllegalArgumentException();
        }
    }
}
```

Rules for the fix:

1. **Snapshot before any read used for validation.** `clone()` /
   `Set.copyOf` / `List.copyOf` / `Map.copyOf` must run before the first
   element access. Validating-then-snapshotting reintroduces the race for
   non-trivial validators.
2. **Use the snapshot for length arithmetic.** A common subtlety: if the
   method computes `out.position() + size * 4` and reads `size` from the
   caller's reference but encodes from the snapshot, a concurrent length
   change produces a buffer overflow. All reads must come from the snapshot.
3. **Prefer immutable snapshot factories where they exist.** `Set.copyOf`,
   `List.copyOf`, `Map.copyOf` return immutable views and reject `null`
   elements eagerly. `clone()` is correct for primitive arrays but does not
   guard against null elements in object arrays — combine with explicit
   null-checks if that contract is part of the validation.
4. **Document the snapshot in the method contract.** The Javadoc should state
   that the input is snapshotted at entry and subsequent caller mutation does
   not affect the method's output. Without this, a maintainer may "optimize"
   the snapshot away.

## relationship to defensive-copy-accessor pattern

This pattern's input-side discipline is the mirror image of
`defensive-copy-accessor-defeats-zeroize-on-close`. The two patterns coexist
because the threat models differ:

- **Input-side (this pattern)**: snapshot at method entry to defend against
  caller-controlled mutation that would invalidate the method's output.
- **Output-side (zeroize-on-close anti-pattern)**: do *not* return a defensive
  copy from a record accessor when the lifecycle owner needs to zero the
  authoritative bytes — the clone is the wrong target for `Arrays.fill`.

Both apply simultaneously to a record with a `byte[]` component: the record
canonical constructor snapshots the input, and a separate package-private
authoritative-zeroize method (not the accessor) writes zeros to the snapshot.

## verification

A regression test must specifically observe the post-mutation state through
whatever channel the consumer uses:

- For an encoder: run `encode()`, mutate the input container, decode the
  bytes, assert decoded state matches the *original* input (not the mutated
  one).
- For a record canonical constructor: construct the record, mutate the input
  container, read the record's accessor, assert the accessor returns the
  *original* values. (For `byte[]` accessors, the accessor's own defensive
  copy means a single test must check both: the constructor snapshotted the
  input AND the accessor returns a clone.)
- For a multi-phase method, an interleaved test: run PHASE A (validation) on
  thread T1 with a `Phaser` or `CountDownLatch`, mutate the container on
  thread T2 between A and B, then let T1 run PHASE B. Assert the encoded
  output matches what PHASE A validated.

## tradeoffs

**Strengths.** Localizes the defensive copy at the entry point — every later
line of the method can assume the snapshot is stable. Eliminates the entire
class of caller-mutates-during-encode bugs in one line. Plays well with
record canonical constructors and immutable-collection factories.

**Weaknesses.** One allocation per method call. For hot paths over short
arrays this is negligible (`int[16].clone()` is sub-microsecond); for long
collections the cost may be material — measure before assuming the cost is
free. For pre-validated internal callers where the input is known to be
immutable (e.g., the snapshot comes from another method that already
snapshotted), the second snapshot is wasted; consider a separate
`requireImmutable` overload for trusted internal callers.

## when-to-apply

Any method that accepts a caller-supplied mutable container as input and
uses that container across two or more phases — validate-then-consume,
validate-then-store, validate-then-publish. Specifically:

- Encoders that read element values during validation and again during
  encoding.
- Record canonical constructors that validate components.
- Builder `build()` methods that validate fields then materialize.
- Public API methods declared `throws IOException` that validate inputs and
  then perform I/O (the I/O may yield, exposing PHASE-A-vs-PHASE-B race).

**When not to apply.** Methods that consume the input in a single linear
read. Internal callers that pass a freshly-allocated, never-aliased array.
Hot paths where snapshot cost is shown by measurement to dominate. (In all
three exception cases, document the assumption — a future caller may not
know the snapshot was deliberately omitted.)

## reference-implementation

`modules/jlsm-core/src/main/java/jlsm/sstable/internal/V6Footer.java` —
`encodeScopeSection` snapshots `sortedDekVersions.clone()` at entry; the
`V6Footer.Parsed` canonical constructor wraps `dekVersionSet` with
`Set.copyOf(...)` at entry.

Audit findings that surfaced the pattern:
`F-R1.contract_boundaries.1.001` (encoder),
`F-R1.contract_boundaries.1.002` (record canonical constructor) — WD-02
ciphertext-format audit, 2026-04-25.

## sources

1. [CWE-367 — Time-of-check Time-of-use (TOCTOU) Race Condition](https://cwe.mitre.org/data/definitions/367.html) — canonical TOCTOU weakness; the validate-vs-use form is one of the named instances.
2. [SEI CERT OBJ06-J — Defensively copy mutable inputs and mutable internal components](https://wiki.sei.cmu.edu/confluence/display/java/OBJ06-J.+Defensively+copy+mutable+inputs+and+mutable+internal+components) — canonical guidance for input-side defensive copies; this pattern extends OBJ06-J specifically to validate-then-consume bodies.

---
*Researched: 2026-04-25 | Next review: 2026-07-25*
