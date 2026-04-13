---
description: "Author a hardened spec through two-pass adversarial review"
argument-hint: "<feature-id> <title>"
---

# /spec-author <feature-id> <title>

Author a hardened operational specification for a feature through a two-pass
process: structured authoring followed by adversarial falsification. The
output is a complete spec file ready for `/spec-write` registration.

This skill operates on **design intent**, not implementation. The only code
read during authoring is pre-existing components (for prerequisite stubs).
Checking the spec against the feature's implementation is `/spec-verify`'s
job, not this skill's.

---

## Inputs expected in context

- The feature ID and title passed as arguments
- The feature brief (from `.feature/<slug>/brief.md` or user description)
- The domain analysis (from `.feature/<slug>/domains.md` if available)
- The work plan (from `.feature/<slug>/work-plan.md` if available)
- Optionally, a resolved spec context bundle from `/spec-resolve`

---

## Pre-flight

1. Run `/spec-resolve` if no context bundle is in your context:
   ```bash
   bash .claude/scripts/spec-resolve.sh "<feature description>" 8000
   ```

2. Read `.spec/CLAUDE.md` for the domain taxonomy.

3. Read any relevant KB entries and ADRs referenced in the domain analysis
   or context bundle.

4. **Revival detection:** Check for INVALIDATED specs in matching domains:
   ```bash
   INCLUDE_INVALIDATED=true \
     bash .claude/scripts/spec-resolve.sh "<feature description>" 8000
   ```
   If the bundle contains a `## INVALIDATED Specs (historical reference)`
   section, check whether any INVALIDATED spec's requirements overlap with
   the feature brief's intent (shared subject tokens, related domain).

   If overlap is found, present the INVALIDATED spec(s):
   ```
   ── Revival candidate ──────────────────────────
     <spec_id> — "<spec title>"
     Displaced by: <displacing spec id>
     Reason: <displacement_reason>
     Requirements: <count> (R1-R<N>)
   ```

   Use AskUserQuestion:
   - **"Use as reference"** — load the INVALIDATED spec as additional context
     for Pass 1. The spec is a *reference*, not a template — author the new
     spec fresh through the normal two-pass flow. After registration (via
     spec-write), set `revives: ["<old_spec_id>"]` in the new spec's
     frontmatter and update the old spec's `revived_by` field.
   - **"Author from scratch"** — proceed normally without the INVALIDATED
     spec as context.

   If no INVALIDATED specs overlap or the section is absent, skip silently.

5. **Work group context:** If `.work/` exists, run:
   ```bash
   bash .claude/scripts/work-context.sh --domains "<domains from spec-resolve bundle>"
   ```
   If output is non-empty, read it and note:
   - **Downstream consumers:** Which work definitions depend on specs in this
     domain. Surface: "WD-03 and WD-05 depend on specs in this domain — the
     spec you author will affect their readiness."
   - **Interface contracts:** If any work definition expects an interface
     contract (`kind: interface-contract`) in this domain, note it: "WD-02
     expects an interface contract at <path> — consider whether this spec
     should be authored as an interface contract."
   - This information does not change the authoring flow — it adds awareness
     of who will consume the spec being authored.

---

## Pass 1 — Structured authoring

**Mode: Author.** Fast, exploratory, high-trust. Produce the draft.

### Step 1a — Extract surface requirements

From the feature brief, domain analysis, and work plan, extract every
explicit requirement. These are the "what to build" requirements —
behavioral contracts, API signatures, storage formats, configuration
options.

Write each as a numbered requirement following the requirement rules:
- One falsifiable claim per requirement
- Explicit subject ("The vector index must..." not "Must...")
- Measurable condition where applicable
- Present tense, active voice
- **Behavioral, not structural:** every requirement must be verifiable by
  observing inputs and outputs, not by reading source code. Never reference
  specific class names, method names, file paths, or call chains. The spec
  describes what must be true; the work planner decides which constructs
  deliver it.
  - BAD: "The LsmVectorIndex.encodeFloat16s method must produce bytes"
  - GOOD: "Float16 encoding must produce big-endian bytes with length
    exactly dimensions * 2"
  - BAD: "IvfFlat.index() must quantize before calling assignCentroid"
  - GOOD: "Centroid assignment must use the quantized vector value, not
    the original float32 input"

### Step 1b — Identify prerequisites

For each surface requirement, ask: **"What must already be true in the
existing system for this requirement to hold?"**

For each assumption identified:
- Check the resolved context bundle for an existing spec that covers it
- If covered: add to the `requires` array
- If not covered: create a prerequisite stub (see /spec-write Step 2)
- Read the relevant existing code ONLY for prerequisite verification —
  do not read the feature's own implementation

### Step 1c — Expand by operational sequence

For each requirement that describes a temporal relationship ("X before Y",
"X uses the result of Y", "X must happen during Z"):

Trace the operational sequence step by step:
1. What is the input to each step?
2. What transformation happens?
3. What is the output?
4. Who consumes the output?
5. At which step does the requirement's constraint apply?

If the trace reveals that the requirement is ambiguous about *when* in the
sequence the constraint applies, split into separate requirements that
specify the exact step.

**Example:** "Centroid assignment must use the quantized vector" is
ambiguous. The operational trace reveals: input arrives as float32 →
encoding quantizes to float16 bytes → decoding produces float32 from
float16. The requirement must specify: "centroid assignment must use the
quantized vector value (the result of encoding to the configured precision
then decoding back to float32), not the original float32 input." Note:
the requirement describes the behavioral constraint (use quantized value),
not the implementation mechanism (which methods to call in which order).

### Step 1d — Expand by failure mode

For each component or technology the feature touches, search for known
failure modes BEFORE reading the design:

- IEEE 754 edge cases for floating point
- Concurrency hazards for shared data structures
- Encoding format ambiguities for serialization
- Input validation gaps for API boundaries
- Precision/rounding issues for numerical computation

Build a checklist from what you find. Then review each surface requirement
against the checklist. For each failure mode that isn't addressed by an
existing requirement, add a requirement.

### Step 1e — Collapse user decisions, expand requirements

Review the requirements for clusters that stem from the same conceptual
decision. For each cluster:
- State the conceptual decision once in the Design Narrative
- Keep individual requirements separate (they evolve independently)
- Ensure the user would only need to make one decision, not N

### Step 1f — Write the draft

Assemble the requirements into a spec file following the format in
`.spec/CLAUDE.md`. Include:
- All numbered requirements grouped by category
- Design Narrative (Intent, Why this approach, What was ruled out)
- Front matter with domains, requires, decision_refs, kb_refs
- `[UNVERIFIED]` annotations on any requirement you couldn't confirm

Present the draft to the user. Do NOT proceed to Pass 2 until the user
confirms the draft direction is correct. The user may add, remove, or
adjust requirements at this point.

---

## Pass 2 — Adversarial falsification

**Mode: Adversarial.** Slow, zero-deference, exhaustive. Treat the Pass 1
output as a hypothesis to be falsified.

Launch a subagent for this pass. The subagent receives:
- The complete draft spec from Pass 1
- The feature brief and domain analysis
- The resolved context bundle
- The list of prerequisite stubs created in Pass 1

The subagent's prompt must include:

### 2a — Requirement-level falsification

For each requirement in the draft, begin with the assertion: **"This
requirement is complete and unfalsifiable."** The adversary's job is to
disprove this by constructing a concrete attack — a specific input,
sequence of operations, or initial state that produces wrong behavior
while technically satisfying the requirement text.

For each successful disproof, produce:
1. **Requirement:** the specific requirement being challenged (by number)
2. **Attack:** the concrete scenario — exact input values, operation
   sequence, or initial state that triggers the failure
3. **Expected wrong behavior:** what breaks, and how an observer would
   detect it
4. **Gap in requirement text:** which specific words are ambiguous,
   missing, or overly broad, and why they permit the attack
5. **Tightened requirement:** suggested replacement text that closes
   the gap

If the adversary cannot construct a concrete attack for a requirement,
the requirement stands as written. No changes.

Bare assertions that a requirement "could be" violated without a concrete
attack are not findings. The burden of proof is on disproof, not on the
requirement.

### 2b — Enforcement path tracing

For each NEW requirement proposed in this pass:
- If it adds validation ("must reject X"): identify all callers that
  could send X. For each caller, either add a filtering requirement or
  confirm the caller already prevents X.
- If it changes behavior ("must do X instead of Y"): identify all
  consumers that depend on old behavior Y.
- **A requirement that creates a cascading failure when enforced is a
  spec defect, not a finding.**

### 2c — Cross-requirement interaction

Review the full requirement set (Pass 1 + new additions) for:
- **Contradictions:** two requirements that cannot both be satisfied
- **Cascading failures:** requirement A, when enforced, causes
  requirement B to fail
- **Ordering dependencies:** requirement A must be implemented before B
  but this ordering isn't stated
- **Ambiguous alternatives:** a requirement that uses "either X or Y",
  "may", or conditional language allowing two mutually exclusive
  implementations. If two test writers could independently write tests
  that contradict each other while both satisfying the requirement,
  the requirement is ambiguous and must be tightened. Replace "either
  X or Y" with a concrete choice. The spec makes the decision — the
  implementer should not have to choose.

### 2d — Cross-module boundaries

Search the codebase for other implementations of the same concepts:
- Are there multiple encode/decode paths for the same data?
- Are there utility classes in other modules that duplicate functionality?
- Could a maintainer accidentally use the wrong implementation?

For each found: add a requirement that specifies which implementation
to use and prohibits cross-use.

### 2e — Observability of silent failures

For each failure mode in the spec that produces no error (silent
degradation, invisible data, suppressed exceptions):
- Is there a way for an operator to detect it?
- If not, add a requirement that the behavior is documented in the
  public API, or add a diagnostic mechanism.

### 2f — Uncertainty disclosure

For each finding where you are uncertain whether it's a real gap:
- State what you're uncertain about
- State what would need to be true for it to be a real gap
- Let the user decide — don't suppress uncertain findings

---

## Arbitration

**Mode: Arbitration.** User has design authority, agent has technical
accuracy.

Present the Pass 2 findings to the user, grouped by:
1. **Confirmed gaps** — requirements that are definitely missing
2. **Implementation constraints** — requirements that clarify how
   something must be done (operational sequence, enforcement path)
3. **Observability** — silent failure modes that need documentation
4. **Uncertain** — findings where you couldn't determine if it's a real gap

For each finding, provide:
- What's missing (the unspecified behavior)
- What breaks (the concrete failure scenario)
- Suggested requirement text

The user decides what to include, adjust, or drop. Apply their decisions
to the spec.

---

## Output

The final spec file, ready for `/spec-write <id> <title>` to register.

The user runs `/spec-write` separately — this skill does not register the
spec in the manifest. Separation of concerns: authoring is cognitive,
registration is mechanical.

---

## Hard constraints

- Never read the feature's implementation during authoring — only
  pre-existing code for prerequisite stubs
- Never skip Pass 2 — nothing advances past a draft without a
  falsification pass
- Never suppress uncertain findings — surface them for arbitration
- Never add a validation requirement without tracing its enforcement path
- Always present the draft to the user between Pass 1 and Pass 2
- The subagent for Pass 2 must receive the complete Pass 1 output, not
  a summary — summaries lose the detail that adversarial review needs
