---
note: >-
  Cursor rules use .mdc under .cursor/rules/. Plan mode could not create .mdc;
  copy this content to .cursor/rules/requirements-and-docs.mdc (YAML frontmatter below)
  when using Agent mode, or merge into project rules.
---

```yaml
---
description: Keep docs/REQUIREMENTS.md versioned and sync README and docs when requirements change.
alwaysApply: true
---
```

# Requirements and documentation maintenance

## Canonical requirements

- **[docs/REQUIREMENTS.md](REQUIREMENTS.md)** is the **single versioned source** for product scope, limitations, and UI standards pointers.
- On any **material** change to features, limitations, filenames, or UX promises: update `docs/REQUIREMENTS.md`, bump **`version`** (semver: MAJOR for breaking scope, MINOR for added/changed behavior, PATCH for clarifications), refresh **`last_updated`**, and append a **Changelog** entry.

## When changing requirements or user-visible behavior

1. Edit `docs/REQUIREMENTS.md` first (or in the same PR/commit).
2. Search the repo for stale references: **`README.md`**, **`docs/**/*.md`**, onboarding strings, and comments that restate requirements.
3. Update those files so they **do not contradict** `docs/REQUIREMENTS.md`. Prefer linking to `docs/REQUIREMENTS.md` instead of duplicating long passages.

## UI work

- Follow **[.cursor/skills/mobile-realtime-ui/SKILL.md](../.cursor/skills/mobile-realtime-ui/SKILL.md)** for capture/editor UI; run **design intake** before large UI changes.

## Root `REQUIREMENTS.md`

- **[REQUIREMENTS.md](../REQUIREMENTS.md)** at repo root is a **short index** only; do not duplicate the full spec there.

## Git (coordination with humans)

- Follow **[no-autonomous-commits-rule.md](no-autonomous-commits-rule.md)**: do not commit without explicit user request; after each unit of work, suggest a commit for review.
