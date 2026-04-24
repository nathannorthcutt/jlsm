---
title: "Stale Test After Exception Type Tightening"
aliases:
  - "exception type narrowing stale test"
  - "audit test cleanup after exception narrowing"
  - "STALE vs REGRESSION classification"
topic: "patterns"
category: "testing"
tags: ["testing", "audit", "exception-types", "assertThrows", "test-triage", "TDD"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - "modules/jlsm-core/src/test/java/jlsm/encryption/LocalKmsClientTest.java"
  - "modules/jlsm-core/src/test/java/jlsm/encryption/EncryptionKeyHolderTest.java"
  - "modules/jlsm-core/src/test/java/jlsm/encryption/AesGcmContextWrapTest.java"
related:
  - "wall-clock-dependency-in-duration-logic"
  - "static-mutable-state-test-pollution"
decision_refs: []
sources:
  - url: "https://docs.junit.org/current/api/org.junit.jupiter.api/org/junit/jupiter/api/Assertions.html#assertThrows(java.lang.Class,org.junit.jupiter.api.function.Executable)"
    title: "JUnit 5 Assertions — assertThrows / assertThrowsExactly"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://github.com/junit-team/junit5/issues/2505"
    title: "assertThrows doesn't fail if the thrown exception is of child type"
    accessed: "2026-04-23"
    type: "repo"
---

# Stale Test After Exception Type Tightening

## summary

When an audit pass narrows a method's exception contract — replacing a broad
thrown type (e.g. `IllegalStateException`, `IOException`, `GeneralSecurityException`)
with a purpose-built narrower type (e.g. `KmsPermanentException`,
`UncheckedIOException`, a split between `IllegalArgumentException` for caller
faults and `IllegalStateException` for infrastructure faults) — pre-existing
tests that asserted the old broad type will fail. These failures are **STALE**,
not **REGRESSION**: the behaviour under test is unchanged, the assertion is.
The fix is to update the assertion to the new narrower type and cite the
finding ID that drove the narrowing.

## how-it-works

During test-cleanup after an adversarial audit fix, the audit may introduce
a narrower exception type to distinguish failure classes previously collapsed
under a single broad type. JUnit's `assertThrows` matches any subclass of the
declared type — so a stale test can either **fail loudly** (narrower type is
not a subclass of the old one) or **pass spuriously** (narrower type extends
the old one, hiding that callers can no longer rely on the broad type for
retry / classification). Both require the same fix: update the assertion to
the new narrower type and cite the finding ID.

### classification-matrix

| Failing test covers | Audit-added test covers | Classification | Action |
|---------------------|-------------------------|----------------|--------|
| same behaviour, broad type | same behaviour, narrower type | **STALE** | update assertion to narrower type; cite finding ID |
| unrelated behaviour | — | **REGRESSION** | investigate — the audit fix broke something outside its scope |
| same behaviour, broad type | (no audit test for this behaviour) | **STALE** (gap) | update the stale test AND file a gap — the audit missed coverage for this path |

## algorithm-steps

1. **Run the full test suite** after the audit fix lands.
2. **For each failing test:**
   1. Identify which production method it exercises and what it asserts.
   2. Check the audit-added tests for the same production method.
   3. If an audit-added test covers the same behaviour with a narrower type, classify as **STALE**.
   4. If no audit-added test covers that behaviour, classify as **STALE (gap)** and file a coverage gap.
   5. Otherwise, classify as **REGRESSION** and escalate — the audit fix broke scope-adjacent behaviour.
3. **Update STALE tests** — change the asserted type, rename the test method
   if its name encodes the old type, and leave a comment citing the finding ID
   (e.g. `// F-R1.cb.2.7: post-close wrap now throws KmsPermanentException`).
4. **Re-run the full suite** to confirm green.
5. **Never convert a REGRESSION to STALE** without evidence that the audit
   intentionally changed the test-covered behaviour.

## implementation-notes

- **Naming hygiene:** if the test name encoded the old type
  (`postClose_wrap_throwsIllegalState`), rename it
  (`postClose_wrap_throwsKmsPermanentException`). If the name encodes
  behaviour only (`postClose_wrap_fails`), leave the name and only update
  the assertion.
- **Comment discipline:** every STALE update carries a single-line comment
  citing the finding ID, e.g.
  `// F-R1.cb.2.7: post-close wrap narrowed IllegalStateException → KmsPermanentException`.
- **Subclass-spurious-pass:** if the narrower type extends the old broad
  type, a stale test may still pass. Grep `assertThrows(<broad type>.class`
  across touched tests even if green, and audit each match against the
  audit notes.
- **Parameterised tests:** update the type argument for only the parameter
  whose behaviour was narrowed; don't blanket-replace.
- **Message assertions:** may need updating if the audit changed the message.
- **Don't widen back:** changing the stale test to
  `assertThrows(RuntimeException.class, ...)` "for flexibility" defeats the
  audit's purpose.

## tradeoffs

### strengths

- **Cheap** — every STALE update is a one-line assertion change plus a comment.
- **Preserves audit signal** — the narrower assertion is strictly more informative.
- **Traceable** — finding-ID comments connect the test to its audit rationale
  without git-history spelunking.

### weaknesses

- **Requires audit-note discipline** — without finding IDs, STALE vs REGRESSION
  classification becomes guesswork.
- **Subclass-spurious-pass risk** — tests can stay stale while passing when the
  narrower type extends the broad one; grep-based audit is required alongside
  run-based triage.

### compared-to-alternatives

- **Delete and rely on audit-added test** — valid only if the audit-added test
  covers the same input / output / side-effect surface. When in doubt, update
  rather than delete.
- **Keep broad assertion alongside narrower one** — creates a silent contract
  where broadening the type back would still pass. Do not.

## practical-usage

### when-to-use

- Post-audit test-cleanup where a finding narrowed an exception contract.
- Post-refactor cleanup where a method's thrown type was intentionally
  specialised (e.g. checked `IOException` wrapper → `UncheckedIOException`
  at an API boundary).

### when-not-to-use

- If the production behaviour itself changed, classify as REGRESSION and
  investigate.
- If multiple audits overlap and narrowing attribution is ambiguous, stop
  and reconcile audit notes first — do not guess the finding ID.

## reference-implementations

First formalised during the WD-01 encryption-foundation audit (2026-04-23)
test-cleanup phase:

| Test | Finding ID | Before | After |
|------|-----------|--------|-------|
| `LocalKmsClientTest.postClose_wrap_*` | F-R1.cb.2.7 | `IllegalStateException` | `KmsPermanentException` (renamed) |
| `EncryptionKeyHolderTest.*` (three sites) | F-R1.cb.1.1 / 1.2 / 1.3 | `IOException` | `UncheckedIOException` |
| `AesGcmContextWrapTest.*` | F-R1.cb.4.4 | `GeneralSecurityException` | split: `IllegalArgumentException` (tag fail) / `IllegalStateException` (infra) |

## code-skeleton

```java
// BEFORE (STALE after audit finding F-R1.cb.2.7):
@Test
void postClose_wrap_throwsIllegalState() {
    client.close();
    assertThrows(IllegalStateException.class,
                 () -> client.wrap(keyId, plaintext));
}

// AFTER:
@Test
void postClose_wrap_throwsKmsPermanentException() {
    // F-R1.cb.2.7: post-close wrap narrowed
    //              IllegalStateException → KmsPermanentException
    client.close();
    assertThrows(KmsPermanentException.class,
                 () -> client.wrap(keyId, plaintext));
}
```

## detection

- Triage every failing test after an audit fix against the audit-added test
  set before any fix attempt.
- Grep `assertThrows(<old broad type>.class` across touched tests even when
  green — subclass relationships mask stale assertions.
- Run `./gradlew test` (full suite, not just the touched module) after
  updates — stale assertions can leak across module boundaries via shared
  test utilities.

## sources

1. [JUnit 5 Assertions — assertThrows / assertThrowsExactly](https://docs.junit.org/current/api/org.junit.jupiter.api/org/junit/jupiter/api/Assertions.html#assertThrows(java.lang.Class,org.junit.jupiter.api.function.Executable)) — `assertThrows` matches subclasses; `assertThrowsExactly` does not. Critical for detecting subclass-spurious-pass cases.
2. [assertThrows doesn't fail if the thrown exception is of child type (junit-team/junit5#2505)](https://github.com/junit-team/junit5/issues/2505) — discussion confirming the documented subclass-matching behaviour and the rationale for the separate `assertThrowsExactly` form.

---
*Researched: 2026-04-23 | Next review: 2026-10-23*
