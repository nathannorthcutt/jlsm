---
title: "Defensive-Copy Accessor Defeats Zeroize-on-Close"
aliases: ["zeroize no-op", "clone-then-fill", "accessor-zeroize trap", "R69 silent no-op"]
topic: "patterns"
category: "resource-management"
tags: ["zeroize", "key-material", "java-records", "defensive-copy", "CWE-316", "encryption", "resource-lifecycle", "immutability"]
research_status: "active"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/internal/KeyRegistryShard.java"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/internal/TenantShardRegistry.java"
related:
  - "systems/security/jvm-key-handling-patterns.md"
  - "systems/security/dek-caching-policies-multi-tenant.md"
  - "patterns/resource-management/non-idempotent-close.md"
  - "patterns/resource-management/multi-resource-close-ordering.md"
decision_refs:
  - "encryption.primitives-lifecycle R69"
sources:
  - url: "https://cwe.mitre.org/data/definitions/316.html"
    title: "CWE-316: Cleartext Storage of Sensitive Information in Memory"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://bugs.openjdk.org/browse/JDK-6263419"
    title: "JDK-6263419 — No way to clean the memory for a java.security.Key"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://github.com/dbsystel/SecureSecretKeySpec"
    title: "SecureSecretKeySpec — AutoCloseable/Destroyable replacement for SecretKeySpec"
    accessed: "2026-04-23"
    type: "repo"
  - url: "https://wiki.sei.cmu.edu/confluence/display/java/OBJ06-J.+Defensively+copy+mutable+inputs+and+mutable+internal+components"
    title: "SEI CERT OBJ06-J — Defensively copy mutable inputs and mutable internal components"
    accessed: "2026-04-23"
    type: "docs"
---

# Defensive-Copy Accessor Defeats Zeroize-on-Close

## summary

A record (or any value-immutable class) holds a `byte[]` of sensitive material —
an HKDF salt, AES key, nonce, password-hash. Its accessor returns a defensive
clone so consumers cannot mutate the internal array. A close/shutdown path
then attempts `Arrays.fill(record.accessor(), (byte) 0)` to satisfy a
zeroize-on-close requirement. The `fill` writes zeros into the throwaway clone
returned by the accessor; the authoritative byte[] inside the record is never
touched. The zeroize-on-close guarantee silently degrades to a no-op, and the
secret persists in heap memory until GC reclaims it — which may be never, or
may happen after a heap dump captures it (CWE-316).

## problem

The interaction between two individually correct patterns produces the bug:

1. **Defensive-copy accessor** — correct practice per SEI CERT OBJ06-J for any
   record component whose type is mutable. A `byte[]` component accessor that
   returns the internal array would let any caller silently corrupt the record.
2. **Zeroize-on-close** — correct practice per CWE-316 / R69 for sensitive
   bytes. Lifecycle owners (registries, caches, key holders) overwrite the
   memory before the record becomes unreachable.

Naive composition looks right and reads plausibly at review time:

```java
public record KeyRegistryShard(..., byte[] hkdfSalt) {
    @Override public byte[] hkdfSalt() {
        return hkdfSalt.clone();       // defensive copy (good)
    }
}

// Lifecycle owner close path:
for (KeyRegistryShard shard : shards) {
    Arrays.fill(shard.hkdfSalt(), (byte) 0);   // zeros a throwaway clone
}
```

After this loop runs, every `shard.hkdfSalt()` call still returns the
unmodified secret. A heap dump taken one millisecond after "close" still
contains every salt byte.

## symptoms

- Heap dump taken during shutdown (or any time after a purported zeroize call)
  still reveals the sensitive bytes in the record's internal byte[].
- `record.accessor()` returns a non-zero byte[] after the zeroize-on-close
  path has completed.
- A security audit flags R69 / CWE-316 as unsatisfied despite a close path
  that visibly calls `Arrays.fill(...)`.
- Unit tests that verify zeroization by reading the accessor pass (they read
  a fresh clone that happens to be zero if taken before any consumer read) or
  fail sporadically depending on invocation order.

## root-cause

Two root causes fuse into one bug:

- **Accessor semantics invisible at the call site.** `record.accessor()` and
  `record.someField` look identical to a reviewer; only the method body
  reveals the clone. A caller reasoning locally cannot tell whether the
  returned reference aliases internal state or not.
- **`Arrays.fill` writes to its argument.** Calling `Arrays.fill(x, 0)` zeros
  whatever array `x` currently refers to — not the variable `x` and not any
  other array that happens to hold the same values. A clone returned from an
  accessor is a *different* array; writing to it cannot affect the source.

The record's immutability contract and the zeroize-on-close requirement are
intrinsically in tension. Resolving that tension requires an explicit escape
hatch — not a well-placed `Arrays.fill`.

## fix

Add a narrow, scoped authoritative-zeroization method on the record. The
accessor continues to return a defensive clone; a separate method writes
directly to the internal byte[].

```java
public record KeyRegistryShard(..., byte[] hkdfSalt) {
    @Override public byte[] hkdfSalt() {
        return hkdfSalt.clone();   // still defensive — consumers cannot corrupt
    }

    /** Package-private: authoritative zeroize of the internal byte[]. */
    void zeroizeSalt() {
        Arrays.fill(hkdfSalt, (byte) 0);   // writes to the stored array
    }
}

// Lifecycle owner close path:
for (KeyRegistryShard shard : shards) {
    shard.zeroizeSalt();   // mutates authoritative state
}
```

Rules for the fix:

1. **No public API that exposes the internal array.** The defensive-copy
   accessor remains the only outward-facing reader. Leaking the internal
   reference would reintroduce the exact problem the accessor was designed
   to prevent (consumer-side corruption).
2. **Restrict the authoritative-zeroize method's visibility.** Package-private
   is the default; use module-private / sealed hierarchy if the lifecycle
   owner lives in another package. External consumers must never be able to
   corrupt a live record.
3. **Call only from lifecycle-owning code.** Registries, caches, key holders
   that own the record's lifetime. Never call from business logic — after
   zeroization, any subsequent consumer read returns a clone of zero bytes
   and any derivation using those bytes silently produces wrong results.
4. **Document the exception to immutability.** The record is still
   value-immutable from every public observer's perspective; the escape hatch
   is a narrowly scoped, internal-only post-construction mutation. Call this
   out in a class-level comment so future reviewers do not "clean up" the
   apparent contradiction.

## verification

A regression test must not read through the accessor alone — a test that
holds onto a `pre` clone, calls zeroize, then reads a `post` clone would have
passed under the buggy pattern (both clones come from the same unmodified
internal array) only if it also asserted `post` is all zero. The sufficient
shape: capture `pre = record.accessor()`, call `record.zeroize()`, capture
`post = record.accessor()`, assert `pre != post` (defensive-copy still
working), assert `post` is all zero (authoritative state really zeroized),
and optionally assert `pre` is unchanged (consumer copies are not mutated).
The buggy pattern fails this because `post` matches `pre` (both non-zero).

## related-anti-patterns

- **`SecretKeySpec.destroy()` is a no-op.** The JDK's
  `javax.crypto.spec.SecretKeySpec` does not implement `Destroyable.destroy()`
  — calls throw `DestroyFailedException`. Same failure mode (zeroize call
  that looks implemented but is not). Fix: use a custom `SecretKey` impl or
  off-heap `MemorySegment` with `fill((byte) 0)` + `arena.close()`.
- **String secrets.** `new String(keyBytes)` is unzeroable because `String`
  is immutable and its internal char/byte array is not reachable. Sibling
  category: any path that converts sensitive bytes to `String` before
  zeroization is equally defeated.
- **GC relocation of byte[].** Even a correctly zeroized byte[] may have
  been copied by the GC during compaction, leaving stale bytes in freed heap
  space. Off-heap `MemorySegment` avoids this category entirely.

## tradeoffs

**Strengths.** Preserves defensive-copy immutability for all public consumers;
localizes the escape hatch; fixes the close path with a one-line swap
(`Arrays.fill(shard.accessor(), 0)` → `shard.zeroize()`); no extra allocation.

**Weaknesses.** Record is no longer strictly immutable — documentation is the
only guard against mid-lifecycle zeroize. `equals`/`hashCode` silently change
after zeroize, so records used as Set/Map keys must be removed before the
call. Requires package-private access, which dictates module/package layout.

## when-to-apply

Records (or value-immutable classes) whose components include a `byte[]` of
sensitive material (keys, salts, nonces, password hashes, tokens), paired
with a lifecycle owner (registry, cache, holder) responsible for CWE-316 /
R69 zeroize-on-close.

**When not to apply.** Records holding non-sensitive `byte[]` (no zeroize path
needed); short-lived method-local byte[] (zero the local directly, no record);
threat models that explicitly exclude heap inspection (rare — document it).

## reference-implementation

`modules/jlsm-core/src/main/java/jlsm/encryption/internal/KeyRegistryShard.java`
— the `zeroizeSalt()` method pattern. Documented inline with the constraint
that callers must not invoke it on a shard still reachable to functional
consumers. Called from `TenantShardRegistry.close()`.

Audit findings that surfaced the pattern:
`F-R1.contract_boundaries.3.5`, `F-R1.shared_state.2.7`,
`F-R1.resource_lifecycle.1.5` (WD-01 encryption-foundation audit, 2026-04-23).

## sources

1. [CWE-316: Cleartext Storage of Sensitive Information in Memory](https://cwe.mitre.org/data/definitions/316.html) — canonical weakness definition; explicit mitigation guidance for languages (Java) that cannot reliably zero memory.
2. [JDK-6263419 — No way to clean the memory for a java.security.Key](https://bugs.openjdk.org/browse/JDK-6263419) — open JDK bug tracking the structural problem; acknowledges `Arrays.fill` on returned material is defeated by copy semantics.
3. [SecureSecretKeySpec (dbsystel)](https://github.com/dbsystel/SecureSecretKeySpec) — third-party replacement for `SecretKeySpec` that implements working `Destroyable.destroy()` and `AutoCloseable.close()`; parallel structural fix.
4. [SEI CERT OBJ06-J — Defensively copy mutable inputs and mutable internal components](https://wiki.sei.cmu.edu/confluence/display/java/OBJ06-J.+Defensively+copy+mutable+inputs+and+mutable+internal+components) — canonical guidance for the defensive-copy accessor pattern that this anti-pattern collides with.

---
*Researched: 2026-04-23 | Next review: 2026-07-23*
