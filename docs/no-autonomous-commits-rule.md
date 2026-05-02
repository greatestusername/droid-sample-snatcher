---
note: >-
  Copy to .cursor/rules/no-autonomous-commits.mdc when using Agent mode.
  Use the YAML frontmatter block below as the file header.
---

```yaml
---
description: Never create git commits without explicit user request; suggest reviewed commits after each unit of work.
alwaysApply: true
---
```

# Git commits (human-owned)

## Do not commit autonomously

- **Do not** run `git commit`, `git merge`, `git push`, or similar **unless the user explicitly asks** you to commit or push in that message.
- It is fine to run **read-only** git commands when debugging (`git status`, `git diff`, `git log`) if the user’s task requires it.

## After each unit of work

- At the end of a **coherent unit of work** (feature slice, bugfix, doc update, refactor chunk), **suggest** a commit the user can run after review:
  - Proposed **one-line subject** (imperative mood, ~72 chars).
  - Optional **body** bullets if the change is non-obvious.
  - **Suggested files or scope** so the user knows what would be included.

Example:

```text
Suggested commit (after your review):
  git add docs/REQUIREMENTS.md README.md
  git commit -m "docs: bump requirements to 1.1.0 and add commit workflow"
```

This keeps **authorship and review** with the human while preserving a steady cadence of small commits.
