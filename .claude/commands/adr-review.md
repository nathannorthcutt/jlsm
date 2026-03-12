# /adr-review "<problem-slug>"

Reviews an existing ADR. Uses the deliberation loop for all outcomes —
no file is written until the user confirms in chat.

---

## Step 0 — Load the decision record

Read in this order:
1. `.decisions/<problem-slug>/adr.md` — current decision and KB links
2. `.decisions/<problem-slug>/constraints.md` — original constraints
3. `.decisions/<problem-slug>/log.md` — full history including deliberation summaries
4. `.decisions/<problem-slug>/evaluation.md` — scoring and KB evidence

If the slug directory does not exist, say:
  "No decision found for '<problem-slug>'. Use /architect to start a new one."

---

## Step 1 — Open the review deliberation

Present this in chat:

```
─────────────────────────────────────────────────────────────
REVIEW — <problem-slug>
─────────────────────────────────────────────────────────────
Current recommendation: <from adr.md>
Status: <from adr.md frontmatter>
Last activity: <most recent log entry date and event>
Original decision date: <from adr.md>

What prompted this review? (pick one or describe freely)
  1. Constraints have changed
  2. New research is available in the KB
  3. Implementation revealed unexpected problems
  4. Scheduled review
  5. Want to change the decision regardless of scoring

Or just describe what has changed and I'll determine the approach.
─────────────────────────────────────────────────────────────
```

Append a `review-requested` log entry immediately (before any analysis).

---

## Step 2 — Analyse and re-evaluate

Branch based on the user's response:

### Constraints changed
- Ask for the updated values for each changed dimension
- Do not update constraints.md yet — wait for confirmation
- Re-score only the dimensions that changed
- Determine if the recommendation changes

### New KB research available
- Read the new subject file(s) from `.kb/`
- Score them against the current constraint profile
- Add them to the candidate pool and re-run the comparison matrix
- Do not update evaluation.md yet — wait for confirmation

### Implementation revealed problems
- Ask the user to describe the problem specifically
- Map it to a constraint dimension and KB source
- Determine whether the ADR remains valid or must be revised

### Override requested
- Ask one question only: "Can you tell me why? I'll record it in the log."
- Accept any reason given (or none)
- Proceed to present the override as the review outcome

---

## Step 3 — Present review deliberation

Present the review outcome as a defence summary in chat (same format as Step 6a of /architect),
with one of these headers:

- `[NO CHANGE]` — recommendation still holds; explain why with KB evidence
- `[REVISED]` — recommendation changes; explain what changed and why
- `[OVERRIDE]` — user-directed change; state what was changed and the reason given

Follow all deliberation chat rules from Step 6b of /architect:
- Answer questions with KB references
- Acknowledge valid challenges and revise
- Accept constraint reweighting without argument
- Ask at most one clarifying question per turn

---

## Step 4 — Confirmation and write

When the user confirms:

### No change
- Update `adr.md` frontmatter: add `last_reviewed: YYYY-MM-DD`
- Append `review-deliberation-confirmed` log entry using the Deliberation Log Entry Template
  (summary: "Review completed. Recommendation stands: <one line reason>.")
- Also append a brief `review-completed` log entry

### Revision
1. Update current `adr.md`: `status: superseded`, `superseded_by: adr-v<N>.md`
2. Write `adr-v<N>.md` from the ADR Template (in /architect):
   - `version: <N>`, `supersedes: adr-v<N-1>.md`
   - Updated KB links for any changed candidates
   - If override: add the override note block
     ```
     > **Override note:** The scored evaluation favoured <original candidate>.
     > This ADR reflects the user's choice of <overridden candidate>.
     > Override reason: <reason provided, or "not stated">
     > See [evaluation.md](evaluation.md) for the original scoring.
     ```
3. Write updated sections to `constraints.md` or `evaluation.md` as needed
   (append `## Updates YYYY-MM-DD` — never overwrite existing content)
4. Append `revision-confirmed` log entry using the Deliberation Log Entry Template
5. Update `.decisions/<problem-slug>/CLAUDE.md` ADR Version History table
6. Update `.decisions/CLAUDE.md` master index

---

## Step 5 — Always log

Every `/adr-review` invocation produces at minimum:
- A `review-requested` entry written at Step 1
- A `review-deliberation-confirmed` or `review-completed` entry written at Step 4

Even if nothing changes, both entries must be present.
