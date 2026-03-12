---
description: Create or update CLAUDE.md files for all jlsm submodules based on current module structure
---

For each submodule in the project (`jlsm-indexing`, `jlsm-vector`, and any others found in the
root `build.gradle` or `settings.gradle`), create or update a `CLAUDE.md` file in that module's
root directory.

Each CLAUDE.md should follow this structure and be derived from the actual current state of the
module — read the source files, `module-info.java`, and `build.gradle` before writing:

1. **One-line purpose** — what this module does and what it builds on
2. **Depends on** — which other jlsm modules it imports (from `module-info.java`)
3. **Exported packages** — packages visible to consumers (from `module-info.java`)
4. **Internal packages** — packages that are not exported
5. **Key constraints** — anything an agent must not do in this module (e.g. no new
   dependencies on modules not already in `module-info.java` without discussion)

Do not copy boilerplate — every field must reflect the actual current source.
Do not modify `jlsm-core/CLAUDE.md`; that file is maintained separately.
After writing, print a one-line summary of what changed per module.