## PR Readiness Checklist

Before marking a PR ready for review, complete all of the following:

- Local `main` is up to date with `origin/main` and all changes from `main`
  are merged into the feature branch — run `git fetch origin main` and merge
  or rebase before creating the PR to avoid missing infrastructure or conflicts
- `./gradlew check` passes cleanly with no failures or warnings
- VSCode diagnostics panel shows no warnings or errors (skip if unavailable)
- TDD cycle is complete — tests were written before implementation and all pass
- Run `/update-module-docs` if any module's public API, exported packages, or
  dependencies changed; commit the result on the same branch