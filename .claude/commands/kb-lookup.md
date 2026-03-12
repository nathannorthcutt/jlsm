# /kb-lookup <topic> <category> <subject>

Loads a specific knowledge base entry into context for the current task.

---

## Argument handling

- All three arguments provided → proceed directly
- Missing topic or category → ask: "Please provide topic, category, and subject.
  Format: /kb-lookup <topic> <category> <subject>"
- Missing subject only → read the category CLAUDE.md and list available subjects,
  then ask which one to load

---

## Steps

1. Check `.kb/CLAUDE.md` exists — if not, say "Run /setup first."
2. Read `.kb/<topic>/<category>/CLAUDE.md` — confirm subject exists in the contents table
3. Read `.kb/<topic>/<category>/<subject>.md` — full subject content
4. Summarise: lead with the `## summary` section in plain language
5. Surface `### key-parameters` and `## code-skeleton` if the task involves implementation
6. Do not load sibling files unless explicitly asked
7. If subject not found: report what does exist in that category and suggest alternatives

---

## Notes

- Use this command when you need one specific subject — it loads the minimum context
- For comparison tasks ("which algorithm is best for X"), read the category CLAUDE.md
  first — it contains the comparison summary without loading all subject files
- Subject files may end with `@./<subject>-detail.md` — load the detail file too
  if the task requires deep implementation knowledge
