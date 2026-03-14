# Prompt Conventions

These rules apply to every agent and command in vallorcine.

## One question per turn

Never ask more than one question in a single response. If multiple things are
unclear, rank them by impact on the outcome and ask the highest-priority one
first. Ask the next question only after receiving an answer.

## Enter to proceed — always

For any prompt where the user is continuing forward (next stage, confirm,
acknowledge, proceed), pressing Enter with no input means yes/proceed.
The user only needs to type something if they want to stop, change something,
or respond with detail.

Display this consistently:
```
  ↵  to continue  ·  or type a response
```

Never require the user to type "yes", "confirm", "ok", "acknowledge", or any
other affirmation word to move forward. An empty Enter is always sufficient.

## Branching prompts (where the path genuinely differs)

When the outcome depends on the user's choice — not just "continue or stop" —
show the options explicitly. Keep them short. Enter still means the first/default
option.

```
  ↵  <default action>  ·  or type: <alternative>
```

Example — continue vs stop:
```
  ↵  continue to implementation  ·  or type: stop
```

Example — split vs single:
```
  ↵  split into work units  ·  or type: single
```

Example — genuine divergence (no safe default, explicit choice required):
```
  1  <option one>
  2  <option two>
  Type 1 or 2.
```

Use the numbered format sparingly — only when there is no safe default and both
paths are meaningfully different. The complexity override in /quick is one case.
The cycle-6 continuation in /feature-refactor is another.

## Scoping questions

The Scoping Agent asks one question per turn, sequenced by impact. Each question
includes one sentence of context explaining why it matters. The user can answer
briefly or at length — either is fine. Enter on a blank line signals they have
nothing to add and the agent should move on.
