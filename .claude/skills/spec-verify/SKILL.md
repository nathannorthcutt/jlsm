---
description: "Verify a spec against its implementation"
argument-hint: "<feature-id>"
effort: high
---

# /spec-verify <feature-id>

Reconcile a spec's requirements against the current implementation.
Idempotent — safe to re-run. Updates spec state on success.

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

## Step 1 — Load the spec

Read the spec file's machine section (requirements only, not narrative).
Note the feature ID and version.

---

## Step 2 — Load the implementation

Identify the implementation files. Sources (in priority order):
- If `.feature/<slug>/` exists for this feature, read the work plan and
  implementation files listed there
- Otherwise, use the spec's `domains` to identify likely source directories,
  and read the relevant files

Focus on the code that implements the requirements. Do not read the entire
codebase — read targeted files.

---

## Step 3 — Verify each requirement

For each numbered requirement (R1, R2, ...), determine one of:

| Verdict | Meaning |
|---------|---------|
| SATISFIED | Implementation fully meets the requirement |
| PARTIAL | Implementation partially meets the requirement — note what's missing |
| VIOLATED | Implementation contradicts the requirement |
| UNTESTABLE | Requirement cannot be verified from code alone (e.g., performance claims) |

For each verdict, provide a one-line evidence reference (file:line or method name).

---

## Step 4 — Check obligations

Review the spec's `open_obligations` array. For each obligation:
- If addressed by the implementation, note it as resolved
- If not addressed, flag it

---

## Step 5 — Check for undocumented behavior

Scan the implementation for significant behavior that is NOT covered by
any requirement. If found, note it as:
```
Undocumented: <description> — consider adding as R<N+1>
```

This is informational, not a verification failure.

---

## Step 6 — Write verification note

Append to the spec file's `## Verification Notes` section:

```markdown
### Verified: v<version> — <date>

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | SATISFIED | <file:line or method> |
| R2 | PARTIAL | <what's missing> |
| ... | ... | ... |

**Overall: PASS | PASS_WITH_NOTES | FAIL**

Obligations resolved: <count>
Obligations remaining: <count>
Undocumented behavior: <count or "none">
```

---

## Step 7 — Update state and status

**Only if overall verdict is PASS or PASS_WITH_NOTES:**

Update the spec file's front matter:
- `state`: DRAFT -> APPROVED
- `status`: keep as-is (status tracks lifecycle maturity, not verification)

Update the registry:
```bash
tmp=$(mktemp)
jq --arg id "<feature-id>" '.features[$id].state = "APPROVED"' \
  .spec/registry/manifest.json > "$tmp" \
  && mv "$tmp" .spec/registry/manifest.json
```

**If overall verdict is FAIL:**
- Do NOT change state or status
- Create an obligation in `_obligations.json` for each VIOLATED requirement:
  ```json
  {
    "id": "OBL-<next>",
    "domains": ["<spec domains>"],
    "description": "<requirement text> — VIOLATED in verification",
    "target_feature": "<feature-id>",
    "created_by": "spec-verify",
    "status": "open"
  }
  ```

---

## Step 8 — Run obligations GC

```bash
bash .claude/scripts/spec-obligations-gc.sh
```

---

## Step 9 — Report

```
Verification complete: <feature-id> v<version>
Overall: <PASS | PASS_WITH_NOTES | FAIL>
  SATISFIED: <count>
  PARTIAL: <count>
  VIOLATED: <count>
  UNTESTABLE: <count>
State: <DRAFT | APPROVED>
```

---

## Hard constraints

- Never verify a spec that fails structural validation
- Never double-verify the same version (idempotency check in pre-flight)
- State and status are updated together or not at all
- FAIL verdict creates obligations, not silent annotations
- Verification notes are append-only — never delete previous notes
