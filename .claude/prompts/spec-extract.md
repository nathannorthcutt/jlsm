# Spec Extraction

Extract a hardened specification from existing implementation code for a
foundational type that multiple specs reference but that has no spec of
its own.

Read `.claude/skills/spec-author/extraction-mode.md` for the full
extraction mode process.

---

## Step 0 — Discovery

The user provides a type name, description, or area of functionality.
You discover everything else.

### 0a. Identify the target

If the user gave a specific type name (e.g., "JlsmSchema"):
- Search the codebase for the class/interface declaration:
  `grep -rn "class <name>\|interface <name>" --include="*.java" --include="*.ts" --include="*.py" --include="*.go"`
- Collect all matching source files and their paths

If the user gave a description (e.g., "the schema system"):
- Search for keywords in source files to identify candidate types
- Present candidates to the user and ask which to extract

If multiple source files define the type (e.g., interface + implementations):
- Include all of them — the spec covers the contract, not one implementation

### 0b. Find consuming specs

Search all specs in `.spec/domains/` for references to the target type:
- Grep for the type name in all spec .md files
- Grep for related terms (e.g., for "JlsmSchema" also search "schema",
  "field type", "field definition")
- List each consuming spec with the requirement IDs that reference the type

### 0c. Find related tests

Search test directories for tests that exercise the target type:
- Grep for imports/usage of the type in test files
- These tests are evidence of intended vs incidental behavior

### 0d. Confirm scope with the user

Display the discovery results:

```
── Spec Extraction Scope ──────────────────────
Target: <type name>

Source files:
  <path> (<line count> lines)
  <path> (<line count> lines)

Consuming specs (<n> specs, <n> requirements reference this type):
  F01 — <title> (R3, R7, R12)
  F05 — <title> (R32, R55)
  ...

Related tests:
  <test file path>
  <test file path>

Proceed with this scope?
  Type **yes** to start extraction
  Type **add <path>** to include additional files
  Type **remove <path>** to exclude a file
```

Wait for user confirmation before proceeding.

---

## Pass 1 — Implementation extraction

1. Read the source files for the target type completely
2. For each public/protected member: document signature, preconditions,
   postconditions, side effects, thread safety
3. Convert each observation into a behavioral requirement marked as:
   - `[EXPLICIT]` — clearly intended (validation, documented, tested)
   - `[IMPLICIT]` — happens but may be incidental (no test/docs)
   - `[ABSENT]` — not done but downstream specs assume it
4. Document lifecycle: construction, mutability, thread sharing, cleanup
5. Assemble into a spec draft

---

## Pass 2 — Cross-spec conflict detection

Launch a subagent. It receives the draft spec + all consuming specs
identified in Step 0b.

For each assumption a consuming spec makes about this type, classify:
- **CONFIRMED** — implementation guarantees this, cite evidence
- **CONTRADICTED** — implementation does something different
- **UNGUARANTEED** — works today but no explicit guarantee (fragile)
- **MISSING** — consuming spec assumes behavior that doesn't exist

Also check cross-spec consistency: do two specs assume contradictory
things about the same behavior?

---

## Arbitration

Present conflicts to user. For each:
- Should the implementation change?
- Should the consuming spec be corrected?
- Should a new requirement make the guarantee explicit?

---

## Output

1. Extracted spec file ready for `/spec-write`
2. Conflict report at `.feature/_spec-extraction/<type>-conflicts.md`
