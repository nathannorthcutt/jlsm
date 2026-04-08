# Spec Author — Extraction Mode

Extract a hardened specification from existing implementation code for a
foundational type that has no spec but is referenced by multiple existing
specs. The output captures what the implementation guarantees today and
flags conflicts with downstream specs that assume different behavior.

**This is the inverse of normal spec authoring:** normal mode writes specs
from design intent (top-down). Extraction mode reads implementation to
discover what's actually guaranteed (bottom-up), then cross-references
against all specs that depend on this type.

---

## When to use

Use extraction mode when:
- A type is referenced by 3+ existing specs but has no spec of its own
- The type exists as implemented code, not a planned feature
- Multiple specs may have inconsistent assumptions about the type's behavior

Examples: JlsmSchema (6 specs reference it), JlsmDocument (4 specs),
shared utility types, foundational data structures.

---

## Inputs

The user provides:
- **Type name** — the class/interface/module to extract a spec for
- **Source files** — file paths and line ranges for the implementation
- **Consuming specs** — list of spec IDs that reference this type
  (or "all" to scan the full .spec/ directory)

---

## Pass 1 — Implementation extraction

**Mode: Observer.** Read the code. Document what it does, not what it
should do. Zero judgment — only observable behavior.

### 1a. Read the implementation

Read every source file for the type. For each public or protected member:
- Method signature (parameters, return type, exceptions)
- Preconditions (null checks, bounds checks, validation)
- Postconditions (what the return value guarantees)
- Side effects (state mutations, I/O, synchronization)
- Thread safety model (immutable, synchronized, volatile, none)

### 1b. Extract behavioral requirements

Convert each observation into a requirement:
- One requirement per observable guarantee
- Present tense, behavioral language ("The schema rejects null field
  names with NullPointerException")
- Include the evidence: which line(s) provide this guarantee
- Mark each requirement with confidence:
  - `[EXPLICIT]` — the code clearly intends this (named validation,
    documented contract, test coverage)
  - `[IMPLICIT]` — the code does this but it may be incidental (no test,
    no documentation, could be an implementation detail that changes)
  - `[ABSENT]` — the code does NOT do this but downstream specs assume
    it does (gap between assumption and reality)

### 1c. Identify lifecycle and construction patterns

Document:
- How instances are created (constructor, builder, factory)
- Whether instances are mutable or immutable after construction
- Whether instances are shared across threads
- Whether instances have a close/cleanup lifecycle
- What happens with invalid construction arguments

### 1d. Write the extraction draft

Assemble requirements into a spec file following `.spec/CLAUDE.md` format.
Group by behavioral category (construction, validation, access, mutation,
thread safety, lifecycle).

Do NOT present to the user yet — proceed directly to Pass 2.

---

## Pass 2 — Cross-spec conflict detection

**Mode: Adversarial.** Treat each downstream spec's assumptions as claims
to verify against the extracted implementation.

### 2a. Load all consuming specs

Read every spec listed in the inputs (or scan `.spec/domains/` if "all").
For each spec, extract every requirement that references the target type:
- Direct references (mentions the type name)
- Indirect references (uses a concept the type provides — e.g., "field
  types" references the schema's field type registry)

### 2b. Cross-reference each assumption

For each assumption found in a consuming spec:

1. **Find the matching extraction requirement.** Does the implementation
   actually guarantee what the consuming spec assumes?

2. **Classify the match:**

   - **CONFIRMED** — the implementation does guarantee this. Cite the
     extraction requirement and the code evidence.

   - **CONTRADICTED** — the implementation guarantees something different.
     State what the consuming spec assumes, what the implementation
     actually does, and the specific conflict.
     ```
     CONFLICT: F03 R8 assumes schema field names are case-insensitive.
     Implementation: field name lookup uses HashMap (case-sensitive).
     Impact: encrypted field matching fails for case-variant names.
     ```

   - **UNGUARANTEED** — the implementation happens to behave this way
     today but provides no explicit guarantee (no validation, no test,
     implementation detail). Mark as fragile.
     ```
     FRAGILE: F07 R54 assumes schema preserves field insertion order.
     Implementation: uses LinkedHashMap (preserves order) but no test
     or documentation asserts this. A change to HashMap would break F07.
     ```

   - **MISSING** — the consuming spec assumes behavior the implementation
     does not have at all.
     ```
     MISSING: F10 R92 assumes schema validates field type compatibility
     before index creation. Implementation: no such validation exists.
     Index creation with incompatible field types silently succeeds.
     ```

### 2c. Cross-spec consistency check

Compare what different consuming specs assume about the same behavior:

- Do two specs assume contradictory things about the same method/behavior?
- Do two specs assume the same behavior but describe it differently
  (potential for divergence if one spec is updated)?
- Are there specs that should share a requirement but each defines its own?

For each inconsistency:
```
CROSS-SPEC CONFLICT: F03 and F07 both assume schema field lookup but
with different semantics.
  F03 R10: "field lookup by name returns the encryption configuration"
  F07 R56: "field lookup by name returns the field type for SQL mapping"
  These are different lookups on the same type. The implementation has
  both but they are independent methods. Not a conflict, but the shared
  "field lookup" language is ambiguous.
```

### 2d. Identify missing requirements

Requirements that should exist in the extracted spec but don't appear in
any consuming spec either. These are behaviors the implementation provides
that no one depends on yet — either unused features or untested contracts.

---

## Arbitration

Present findings to the user grouped by:

1. **Conflicts** — consuming spec assumes X, implementation does Y
2. **Fragile assumptions** — consuming spec assumes X, implementation
   does X today but without explicit guarantee
3. **Cross-spec inconsistencies** — two specs assume different things
   about the same behavior
4. **Missing coverage** — behaviors the implementation has that no spec
   references
5. **Confirmed assumptions** — consuming spec assumptions validated
   against implementation (for completeness)

For each conflict and fragile assumption, ask:
- Should the implementation change to match the spec assumption?
- Should the consuming spec be corrected to match the implementation?
- Should a new requirement be added to the extracted spec to make the
  guarantee explicit?

---

## Output

The final extracted spec file, ready for `/spec-write` registration.

Additionally, write a **conflict report** to
`.feature/_spec-extraction/<type-slug>-conflicts.md` containing:
- All CONTRADICTED, FRAGILE, MISSING, and CROSS-SPEC CONFLICT findings
- For each: which consuming specs need updates
- Recommended resolution (change impl, change spec, or add requirement)

The user decides which resolutions to apply. The extracted spec is
registered via `/spec-write`. Consuming spec updates are done via
separate `/spec-author` sessions on each affected spec.

---

## Hard constraints

- Never assume the implementation is correct — it may have bugs that
  consuming specs accidentally work around
- Never assume consuming specs are correct — they may assume behavior
  that was never intended
- Never suppress conflicts — surface everything for arbitration
- The extracted spec documents what IS, not what SHOULD BE. Design
  changes come from the arbitration phase, not from extraction.
- Read tests for the target type — tests are evidence of intended
  behavior vs incidental behavior
