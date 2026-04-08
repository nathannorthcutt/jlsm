# Resolve Spec Conflict

Resolve a conflict between an audit fix and an existing spec requirement.

---

## Step 0 — Identify the conflict

If the user provided a specific conflict, use it. Otherwise, scan for
unresolved conflicts:

1. Read the audit report at the path the user provides (or search
   `.feature/*/audit/*/audit-report.md` for the most recent)
2. Look for the "Fix-Spec Conflicts" section or any mention of spec
   conflicts in the report
3. If no structured conflict section exists, search prove-fix output
   files for fixes that reference spec requirements from other features

Present each conflict found:

```
── Spec Conflict ──────────────────────────────
  Fix: <finding ID> — <what the fix changed>
  Spec: <spec ID>.<requirement> — <requirement text>
  Nature: <contradicts | weakens | changes assumption>
  Tradeoff: <why both sides had valid reasons>
───────────────────────────────────────────────
```

## Step 1 — Analyze the tradeoff

Read the relevant files:
- The prove-fix output for the finding (what the fix does and why)
- The spec requirement that's contradicted (the full requirement text
  and its design narrative context)
- The source code as fixed (what the code does now)
- Any other specs that depend on the conflicting requirement

Present the analysis:
```
If we KEEP the fix:
  - <what improves — the bug that's fixed>
  - <what breaks — which spec requirement is violated>
  - <downstream impact — other specs that depend on this requirement>
  - Spec changes needed: <which requirements to update>

If we REVERT the fix:
  - <what improves — spec consistency preserved>
  - <what breaks — the original bug returns>
  - <risk — how likely the bug is to cause real problems>
```

## Step 2 — User decides

```
Options:
  1. Keep the fix, update the spec
     → I'll update the spec requirement and check downstream specs

  2. Revert the fix, keep the spec
     → I'll revert the source change and mark as FIX_IMPOSSIBLE

  3. Split — keep the fix but add a new spec requirement that
     describes the new behavior explicitly
     → I'll add a requirement to the audited feature's spec and
       mark the conflicting spec's requirement as needing review

  4. Defer — decide later
     → I'll log as unresolved

Which option? (1 / 2 / 3 / 4)
```

## Step 3 — Execute the resolution

**Option 1 (keep fix, update spec):**
- Read the conflicting spec file
- Update the requirement to match the fix's new behavior
- Add a note in the design narrative: "Updated by audit finding <ID>:
  <why the original requirement was revised>"
- Run `bash .claude/scripts/spec-resolve.sh` to check if other specs
  depend on the changed requirement
- If downstream specs found: list them and note which requirements
  may need review

**Option 2 (revert fix):**
- Revert the source code change (git checkout the specific lines)
- Update the prove-fix output file: change result to FIX_IMPOSSIBLE
- Add to the impossibility proof: "Reverted — conflicts with
  <spec>.<requirement>. The spec requirement takes precedence."

**Option 3 (split):**
- Keep the fix as-is
- Add a new requirement to the audited feature's spec describing the
  new behavior
- Add an `invalidates: ["<spec>.<req>"]` entry to the new requirement
- Note in the conflicting spec that the requirement is invalidated by
  the new spec, with a pointer to the audit finding

**Option 4 (defer):**
- Add `[UNRESOLVED: conflicts with audit finding <ID>]` to the
  conflicting spec requirement
- If the spec was APPROVED, change status to DRAFT
- Add `open_obligations: ["resolve conflict with <finding ID>"]`
