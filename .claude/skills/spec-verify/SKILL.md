---
description: "Verify a spec against its implementation and repair violations"
argument-hint: "<feature-id>"
effort: high
---

# /spec-verify <feature-id>

Verify a spec's requirements against the current implementation. When
violations are found, repair them inline — fix code or amend spec text —
then re-verify. A spec is not done until every requirement is SATISFIED
or the user explicitly defers a finding.

**Spec violations are broken contracts, not backlog items.** Every downstream
consumer (work-plan, spec-author, audit, domain analysis) operates on the
assumption that specs are true. A VIOLATED requirement means the system
doesn't do what it claims. This skill owns the full lifecycle: diagnose,
classify, repair, confirm.

---

## Pre-flight guard

1. Run structural validation on the spec file:
   ```bash
   spec_file=$(jq -r --arg id "$ARGUMENTS" \
     '.features[$id].latest_file // ""' .spec/registry/manifest.json)
   bash .claude/scripts/spec-validate.sh ".spec/$spec_file"
   ```
   If validation fails, report errors and stop. Never verify a structurally
   invalid spec.

2. Check the spec's current state. If already APPROVED at this version,
   report:
   ```
   Spec <id> v<version> already verified. No changes needed.
   ```
   Stop. This prevents double-verification of the same version.

---

## Phase 1 — Verify all requirements

### Step 1.1 — Load the spec

Read the spec file's machine section (requirements only, not narrative).
Note the feature ID and version.

### Step 1.2 — Discover and annotate implementation

Identify the implementation files. Sources (in priority order):
1. **@spec annotations** — run `bash .claude/scripts/spec-trace.sh <id>`
   to find all annotated enforcement points.
2. If `.feature/<slug>/` exists for this feature, read the work plan and
   implementation files listed there.
3. Otherwise, use the spec's `domains` to identify likely source
   directories and read the relevant files.

Focus on the code that implements the requirements. Do not read the entire
codebase — read targeted files.

**Annotate as you go.** For every enforcement point discovered during
verification — whether from existing annotations, work plans, or domain
scanning — ensure an `@spec` annotation exists in the code. If an
enforcement point is found but not annotated, add the annotation before
proceeding to the verdict. This ensures that every verification pass
leaves the codebase with complete traceability for the verified spec.

Annotation rules (from `.spec/CLAUDE.md` Code Traceability section):
- Format: `// @spec FXX.RN` or `// @spec FXX.RN — brief description`
- Place above the enforcing method or code block
- Same format in both implementation and test files
- Multiple requirements per annotation: `// @spec FXX.R1,R3,R7`

After discovery and annotation, run `spec-trace.sh` again to confirm
coverage. Report any requirements with zero enforcement points — these
are candidates for UNTESTABLE or may indicate missing implementation.

### Step 1.3 — Verify each requirement

For each numbered requirement (R1, R2, ...), determine one of:

| Verdict | Meaning |
|---------|---------|
| SATISFIED | Implementation fully meets the requirement |
| PARTIAL | Implementation partially meets the requirement — note what's missing |
| VIOLATED | Implementation contradicts the requirement |
| UNTESTABLE | Requirement cannot be verified from code alone (e.g., performance claims) |

For each verdict, provide a one-line evidence reference (file:line or method name).

### Step 1.4 — Check for undocumented behavior

Scan the implementation for significant behavior that is NOT covered by
any requirement. Note these as informational findings — they may become
new requirements during the repair phase.

### Step 1.5 — Present verification results

Display the full verdict table. If all requirements are SATISFIED (or
SATISFIED + UNTESTABLE only), skip to Phase 6 (Finalize).

If any requirements are PARTIAL or VIOLATED, proceed to Phase 2.

---

## Phase 2 — Classify findings

For each non-SATISFIED requirement, classify it into one of three categories:

| Classification | When to use | Repair action |
|----------------|-------------|---------------|
| **code-bug** | Spec is correct, code doesn't match | Fix code + write regression test |
| **stale-spec** | Code is correct (or stricter), spec text is outdated | Amend spec text |
| **needs-decision** | Genuinely ambiguous — code does Y, spec says X, both are defensible | User decides direction |

Present ALL findings batched, grouped by classification:

```
── Verification findings: <id> ─────────────────────

Needs decision (resolve before fixes):
  R39g — spec requires IOException on buffer exceed, code gracefully falls
         back. Fallback may be the better design (avoids data loss on large
         files). Which is correct?

Code bugs (will write regression tests + fix):
  R33 — readKeyIndexV2 missing bounds validation on blockIndex/intraBlockOffset.
        Corrupt data throws internal exceptions instead of descriptive IOException.

Stale spec text (code is correct, spec needs update):
  R42 — spec says "silently ignores trailing bytes" but code now rejects them.
        Code is stricter, which is better.
  R43 — spec says "document in public API" but javadoc is missing.

Use AskUserQuestion with relevant options.
```

---

## Phase 3 — Resolve decisions

For each **needs-decision** finding, present the tradeoff and ask the user
to choose a direction.

For each finding, present:
1. What the spec says
2. What the code does
3. Why each approach is defensible
4. The implication of each direction

Use AskUserQuestion with options:
- "Fix the code (spec is correct)"
- "Amend the spec (code is correct)"
- "Defer (create obligation for later)" — only if user explicitly chooses
- "Need more analysis" — invoke `/architect` if a design decision is needed

After each decision:
- If "fix the code": reclassify as **code-bug**
- If "amend the spec": reclassify as **stale-spec**
- If "defer": create an obligation and exclude from repair
- If "need more analysis": pause spec-verify, invoke `/architect` with the
  ambiguity framed as a constraint. Resume after the decision is made.

After all decisions are resolved, proceed to Phase 4.

---

## Phase 4 — Amend stale specs

For each **stale-spec** finding:

1. **Draft the amendment.** Write the updated requirement text. The
   requirement number stays the same — this is an in-place update, not a
   new requirement.

2. **Check for displacement.** Run displacement detection against other
   specs in the same domains:
   ```bash
   NEW_SPEC_FILES=".spec/$spec_file" \
     bash .claude/scripts/spec-resolve.sh "<domains>" 2>&1
   ```
   If the amendment would displace requirements in another spec, **stop and
   present the chain to the user.** Do not auto-follow displacement chains.

   Use AskUserQuestion with options:
   - "Apply amendment (I'll handle downstream impact separately)"
   - "Skip this amendment"

3. **Apply the amendment.** Update the requirement text in the spec file.

4. **Bump the version** in the spec's front matter (increment by 1).

5. **Re-validate structure:**
   ```bash
   bash .claude/scripts/spec-validate.sh ".spec/$spec_file"
   ```

After all amendments are applied, proceed to Phase 5.

---

## Phase 5 — Fix code violations (TDD)

This is the core repair phase. For each **code-bug** finding, run a
scoped TDD cycle: write a regression test that enforces the spec
requirement, then implement the fix.

### Step 5.1 — Plan all fixes

Before writing any code, present the fix plan for all code-bug findings
together:

```
── Fix plan: <id> ──────────────────────────────

<n> code violations to fix:

  R33 — Add bounds validation to readKeyIndexV2
        Test: verify IOException on out-of-range blockIndex
        Fix: add range check before CompressionMap.entry() call

  R15 — Add blank key rejection to JsonObject.of/Builder.put
        Test: verify IAE on blank/whitespace key
        Fix: add isBlank() check after requireNonNull

Use AskUserQuestion with options:
  - "Approve fix plan"
  - "Adjust" (with Other for custom input)
```

### Step 5.2 — Write regression tests (all violations, batched)

For each code-bug finding, write a test that:
1. Is annotated with `@spec <id>.R<n>` linking it to the requirement
2. Exercises the specific violation (e.g., passes corrupt data, passes
   blank key)
3. Asserts the behavior the spec requires (e.g., IOException with
   descriptive message, IAE on blank key)
4. **Currently fails** against the unfixed code — this confirms the
   violation is real and the test is meaningful

Run the test suite. Confirm all new regression tests fail. If a test
passes, the violation may not be real — re-examine the finding.

Read `.feature/project-config.md` for test framework, conventions, and
source directories. Follow the project's existing test patterns.

### Step 5.3 — Implement fixes

For each violation, implement the minimum correct fix:
- Follow the project's coding conventions
- Do not modify existing tests — only add new regression tests
- Do not refactor surrounding code — fix only the violation
- Add `@spec` annotations to the new/changed code

Run the test suite after each fix. All tests (existing + new regression
tests) must pass before proceeding to the next fix.

### Step 5.4 — Verify fixes

After all fixes are implemented and tests pass, re-verify each fixed
requirement against the spec:
- Re-read the implementation code at the fix location
- Confirm the verdict is now SATISFIED
- Update the evidence reference

If any requirement is still not SATISFIED after the fix, investigate.
Either the fix is incomplete (iterate) or the spec needs amendment
(reclassify as stale-spec and return to Phase 4).

---

## Phase 6 — Finalize

### Step 6.1 — Write verification note

Append to the spec file's `## Verification Notes` section:

```markdown
### Verified: v<version> — <date>

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | <file:line or method> |
| R2 | SATISFIED | <file:line or method> |
| ... | ... | ... |

**Overall: PASS | PASS_WITH_NOTES | FAIL**

Amendments applied: <count or "none">
Code fixes applied: <count or "none">
Regression tests added: <count or "none">
Obligations deferred: <count or "none">
Undocumented behavior: <count or "none">
```

If any requirements were amended, note the old and new text:
```markdown
#### Amendments
- R42: "silently ignores trailing bytes" → "rejects trailing bytes with
  IllegalArgumentException"
```

### Step 6.2 — Update state and registry

**If all requirements are SATISFIED (or SATISFIED + UNTESTABLE only):**

Update the spec file's front matter:
- `state`: DRAFT → APPROVED

Update the registry:
```bash
tmp=$(mktemp)
jq --arg id "<feature-id>" '.features[$id].state = "APPROVED"' \
  .spec/registry/manifest.json > "$tmp" \
  && mv "$tmp" .spec/registry/manifest.json
```

**If any requirements remain VIOLATED or PARTIAL (deferred by user):**
- Do NOT change state
- Ensure obligations exist in `_obligations.json` for each deferred finding

### Step 6.3 — Resolve existing obligations

Check `_obligations.json` for obligations targeting this spec. If any
are now resolved by the fixes applied in Phase 5, update their status:
```json
{ "status": "resolved", "resolved_by": "spec-verify", "resolved_date": "<date>" }
```

Run obligations GC:
```bash
bash .claude/scripts/spec-obligations-gc.sh
```

### Step 6.4 — Report

```
── Verification complete: <id> v<version> ──────

Overall: <PASS | PASS_WITH_NOTES | FAIL>
  SATISFIED:  <count>
  PARTIAL:    <count>
  VIOLATED:   <count>
  UNTESTABLE: <count>

Repairs applied:
  Amendments: <count>
  Code fixes: <count>
  Regression tests: <count>
  Deferred: <count>

State: <DRAFT → APPROVED | DRAFT (unchanged)>
```

---

## Hard constraints

- Never verify a spec that fails structural validation
- Never double-verify the same version (idempotency check in pre-flight)
- State and status are updated together or not at all
- Verification notes are append-only — never delete previous notes
- **Violations are repaired inline, not parked as obligations.** Obligations
  are created ONLY when the user explicitly defers a finding via
  AskUserQuestion — never as the default path.
- **Regression tests are mandatory for code fixes.** A code fix without a
  regression test is incomplete — the violation will recur on the next
  change that touches the same code.
- **Displacement chains are not auto-followed.** If a spec amendment would
  displace requirements in another spec, present the chain and let the
  user decide. Do not create a cascade of automated amendments.
- **@spec annotations are added to all new/changed code.** Every regression
  test and every code fix must be annotated with the requirement it
  enforces.
