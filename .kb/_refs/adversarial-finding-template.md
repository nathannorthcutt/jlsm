---
type: reference-fragment
title: Adversarial Finding Entry Template
---
# Adversarial Finding Entry Template

KB entries with `type: adversarial-finding` capture bug patterns and tendencies
discovered through adversarial testing (aTDD rounds, audit passes, or enhanced
TDD defensive vectors). They persist across features and inform future test
writing and implementation.

## Frontmatter

```yaml
---
title: "<Pattern name>"
type: adversarial-finding
domain: "<security | memory-safety | performance | concurrency | data-integrity>"
severity: "<tendency | confirmed | critical>"
applies_to:
  - "<file path pattern or module name>"
research_status: active
last_researched: "<YYYY-MM-DD>"
---
```

### Field definitions

- `type: adversarial-finding` — distinguishes from standard research entries
- `domain` — the risk domain; used by test-writer to load relevant findings
- `severity`:
  - `tendency` — recurring anti-pattern, not always a bug
  - `confirmed` — verified bug class, reproduced across features
  - `critical` — security or data-integrity bug requiring immediate attention
- `applies_to` — file patterns where this finding is relevant (e.g., `modules/jlsm-table/src/main/**`)

## Required sections

```markdown
# <Pattern name>

## What happens
<!-- 2-3 sentences: the bug pattern and when it manifests -->

## Why implementations default to this
<!-- Root cause: spec gap, performance shortcut, language default -->

## Test guidance
<!-- Specific test vectors the test-writer should add when working in this domain -->

## Found in
<!-- Features where this was discovered, with round/date -->
- <feature-slug> (round N, YYYY-MM-DD): <one-line description>
```

## How it's used

- **Test writer** reads findings matching the current feature's domain during
  defensive vector generation (Step 1b)
- **Spec analyst** reads findings during aTDD round analysis to avoid
  re-discovering known patterns
- **Domain scout** surfaces findings during `/feature-domains` when a domain
  has adversarial coverage
