# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/repo-setup-claude-md-95pbfs`

## Current state

Repo-onboarding setup. Added `CLAUDE.md` (project overview, layout, build/deploy
rules, Python-brain run steps, model workflow, cost-awareness) and this
`HANDOVER.md`. No game code touched yet.

## What was just done

- Added `CLAUDE.md` at repo root — read automatically each session.
- Documented the Opus-plan / Sonnet-implement / Opus-review model workflow.
- Established this `HANDOVER.md` as the per-commit running snapshot.

## In flight / next up

- Nothing mid-change. First code candidate discussed: **haggle price clamp**
  (PROGRESS.md §11.3) — bound the negotiated trade price server-side instead of
  trusting the LLM prompt. Small, self-contained, Java-side; good workflow dry-run.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build."
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
