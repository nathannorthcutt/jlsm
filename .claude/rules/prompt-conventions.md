# Prompt Conventions

These rules apply to every agent and command in vallorcine.

## One question per turn

Never ask more than one question in a single response. If multiple things are
unclear, rank them by impact on the outcome and ask the highest-priority one
first. Ask the next question only after receiving an answer.

## Explicit yes to proceed — always

For any prompt where the user is continuing forward (next stage, confirm,
acknowledge, proceed), require `Type **yes**` to continue. Claude Code does
not support empty-Enter submission — users must always type something. Never
show `↵ to continue` because it does not work.

Display this consistently:
```
  Type **yes**  to proceed to <next step>  ·  or: stop
```

The user can also type feedback or adjustments instead of "yes" — that triggers
a revision loop. "stop" halts the pipeline.

## Branching prompts (where the path genuinely differs)

When the outcome depends on the user's choice — not just "continue or stop" —
show the options explicitly. Keep them short.

```
  Type **yes**  to <default action>  ·  or type: <alternative>
```

Example — continue vs stop:
```
  Type **yes**  to proceed to implementation  ·  or: stop
```

Example — split vs single:
```
  Type **yes**  to split into work units  ·  or type: single
```

Example — genuine divergence (no safe default, explicit choice required):
```
  1  <option one>
  2  <option two>
  Type 1 or 2.
```

Use the numbered format sparingly — only when there is no safe default and both
paths are meaningfully different. The complexity override in /feature-quick is one case.
The cycle-6 continuation in /feature-refactor is another.

## Scoping questions

The Scoping Agent asks one question per turn, sequenced by impact. Each question
includes one sentence of context explaining why it matters. The user can answer
briefly or at length — either is fine. Enter on a blank line signals they have
nothing to add and the agent should move on.
