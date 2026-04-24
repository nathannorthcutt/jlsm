# TDD Failure-First Degeneracy — Detail

> Companion to [tdd-failure-first-degeneracy-for-pure-record-enum-work.md](tdd-failure-first-degeneracy-for-pure-record-enum-work.md).
> Extracted `reference-implementations`, `code-skeleton`, and `detection`
> sections to keep the subject file under 200 lines.

## reference-implementations

Pattern first formalised during WD-01 encryption key-hierarchy foundation
(2026-04-23):

| Unit | Constructs | Kind breakdown | Tests authored | Failure-first outcome |
|------|-----------|----------------|----------------|-----------------------|
| WU-1 | 20 new + 1 rename | 11 records, 2 enums, 1 sealed exception hierarchy (4 types), 6 identity / factory records | 120 new + 15 pre-existing = 135 | all pass on first run; cycle-log note authored |
| WU-2 | 3 constructs | behavioural (Hkdf, AesGcmContextWrap, AesKeyWrap) | — | failure-first honoured normally (constructs have method bodies) |

### canonical-cycle-log-note (from WU-1)

```markdown
## YYYY-MM-DD — test-writer — cycle-N-tests-authored

... (test list) ...

Tests compile-green. Running them shows all pass immediately because the
planning stage wrote full bodies into the "stub" files (records with
validation, enums, sealed hierarchies, ...). This is a planner choice for
foundation layers where the stub body IS the implementation (records + enums
+ sealed exceptions cannot be stubbed meaningfully).

Per TDD protocol (".claude/rules/testing.md" step 2 — confirm failure before
implementation), this unit is a special case: tests validate the pre-landed
planning artifacts. If any contract mismatch existed, the test would fail and
the implementation would be corrected; in this cycle no mismatches were found.
Documenting here to avoid the appearance of skipping the failure-confirmation
step.
```

## code-skeleton

```java
// Pure-type WU construct — planner-landed compact constructor body is the
// implementation. No meaningful "empty stub" form exists.
public record DekHandle(TenantId tenantId, DomainId domainId,
                         TableId tableId, DekVersion version) {
    public DekHandle {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(domainId, "domainId");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(version, "version");
    }
}

// Corresponding test — validates the planner-landed contract.
// All assertions pass on first run because the planner got it right.
// The cycle-log note documents that failure-first is degenerate here.
@Test
void allComponentsRequired_eachRejectsNull() {
    assertThrows(NullPointerException.class,
        () -> new DekHandle(null, domain, table, version));
    assertThrows(NullPointerException.class,
        () -> new DekHandle(tenant, null, table, version));
    assertThrows(NullPointerException.class,
        () -> new DekHandle(tenant, domain, null, version));
    assertThrows(NullPointerException.class,
        () -> new DekHandle(tenant, domain, table, null));
}
```

### sealed-hierarchy-example

```java
// Sealed exception hierarchy — declarative type relationship.
// No "stub form" of the permits clause exists: you either name the subtypes
// or the declaration does not compile.
public abstract sealed class KmsException extends Exception
        permits KmsTransientException, KmsPermanentException {
    protected KmsException(String message) { super(message); }
    protected KmsException(String message, Throwable cause) { super(message, cause); }
}

public non-sealed class KmsTransientException extends KmsException { ... }
public final class KmsPermanentException extends KmsException { ... }
public final class KmsRateLimitException extends KmsTransientException { ... }

// Test asserts the declarative shape — passes immediately if the planner
// got the permits set right.
@Test
void hierarchyShape_kmsExceptionPermitsExactlyTwo() {
    Class<?>[] permitted = KmsException.class.getPermittedSubclasses();
    assertEquals(2, permitted.length);
    assertTrue(Set.of(permitted).containsAll(
        Set.of(KmsTransientException.class, KmsPermanentException.class)));
}
```

## detection

- Before starting a test-writing cycle, grep the WU's work-plan for
  `kind: record`, `kind: enum`, and `sealed` — if every construct matches,
  apply the recipe.
- After running the tests, if **all pass on first run** *and* the WU is
  pure-type, this is correct. Write the cycle-log note and proceed.
- After running the tests, if **all pass on first run** *and* the WU has any
  behavioural construct, this is a **bad test** signal — investigate which
  construct was stubbed incorrectly or whose contract the test failed to
  cover.
- A cycle-log missing the note on a pure-type WU is itself a finding —
  record-keeping discipline is the primary defence against the recipe being
  misapplied.
- In retrospectives, scan unit cycle-logs for `test-writer — cycle-1-tests-authored`
  entries on pure-type WUs; confirm the note is present and cites
  `.claude/rules/testing.md` step 2.
