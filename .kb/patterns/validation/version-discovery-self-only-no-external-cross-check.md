---
type: adversarial-finding
title: "Version Discovery from Self-Only with No External Cross-Check"
topic: "patterns"
category: "validation"
tags: ["version-discovery", "external-authority", "substitution-attack", "factory-contract"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "dispatch-discriminant-corruption-bypass"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources: []
---

# Version Discovery from Self-Only with No External Cross-Check

## What Happens

A file-format reader derives the file's format version from the file's own
trailing magic without any cross-check against external authority (manifest,
catalog, level metadata, sibling references). A corrupted or substituted
file whose self-magic disagrees with what the caller "knows" cannot be
detected. The attacker model here is "wrong file substituted" rather than
"bit-flip in discriminant" (which is the sibling pattern
`dispatch-discriminant-corruption-bypass`).

Concrete example: the manifest records "SSTable at path P is format v5"
but someone replaces P with a legitimate v3 file from an older database.
The reader opens P, observes v3 magic, dispatches to the v3 branch, and
the caller receives a reader for a file that should never have been there.

## Why It Happens

The happy-path discovery flow is "open file, read trailing magic,
dispatch." Nothing links the opened file to the caller's external
expectation. Adding an external cross-check requires a factory API
surface change (an extra parameter) — an easy step to skip when the
happy path works.

## Fix Pattern

Expose an optional `expectedVersion` parameter on the open/factory entry
points; validate the caller-supplied expectation against the magic-derived
version; preserve the auto-detect path for callers that don't opt in:

```java
public static TrieSSTableReader open(Path p) throws IOException {
    return openInternal(p, null);  // auto-detect
}

public static TrieSSTableReader open(Path p, int expectedVersion) throws IOException {
    if (expectedVersion < 1 || expectedVersion > 5)
        throw new IllegalArgumentException("expectedVersion out of range");
    return openInternal(p, expectedVersion);
}

// internal
private static TrieSSTableReader openInternal(Path p, Integer expected) throws IOException {
    long magic = readTrailingMagic(p);
    int discovered = versionFromMagic(magic);
    if (expected != null && expected != discovered) {
        throw new CorruptSectionException(SECTION_FOOTER,
            "expected v" + expected + " but file is v" + discovered);
    }
    return dispatch(discovered, p);
}
```

Callers with external authority (manifest, catalog) opt in; callers
performing generic discovery (tools, audit scripts) do not.

## Detection

Dispatch-routing lens simulated a manifest-vs-file disagreement: wrote a
legacy v3 SSTable, passed `expectedVersion=5` to the factory, asserted
a typed exception.

## Seen In

- `TrieSSTableReader.open` / `openLazy` `expectedVersion` overloads —
  audit finding F-R1.dispatch_routing.1.6.

## Test Guidance

- Write files at every supported version; open each with every possible
  `expectedVersion`. Only the matching combinations must succeed; all
  others must throw `CorruptSectionException(SECTION_FOOTER)` with a
  message naming both the expected and discovered versions.
- Cover the opt-out path: calling `open(Path)` without `expectedVersion`
  on a v3 file must return a working reader for the legacy branch.
- Invalid `expectedVersion` values (0, 6, negative) must throw
  `IllegalArgumentException` at the factory boundary, not
  `CorruptSectionException`.

## Scope

Pattern is meaningfully distinct from
`dispatch-discriminant-corruption-bypass`: discriminant corruption
targets the dispatch mechanism itself; external-authority mismatch
targets the file identity. A well-engineered reader applies both:
speculative cross-version verification closes the bit-flip attack,
`expectedVersion` closes the substitution attack.
