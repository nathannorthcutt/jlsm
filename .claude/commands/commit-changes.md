---
description: Commit and push current work in progress to the remote branch
---

Stage and commit all current changes, then push to the remote branch.

## Steps

1. Run `git status` to identify all changed and untracked files
2. If there are untracked files, stop and ask the user one question:
   - List the untracked files and ask whether to include them in the commit or abort
   - Wait for an explicit answer before proceeding; do not assume
3. Stage all intended files and run `git diff --staged` to review what will be committed
4. Write a commit message that describes what was actually changed — be specific, not generic
   (e.g. "Add CRC validation to WAL replay" not "Update files")
5. Commit and push to the current remote branch
6. Confirm the push succeeded and print the commit hash

## Constraints

- Never commit directly to `main` — if the current branch is `main`, stop and tell the user
- Never auto-resolve the untracked file question; always wait for user input
- If the push fails, report the exact error and stop — do not force push or rebase without instruction