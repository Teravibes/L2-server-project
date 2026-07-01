# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/handover-catchup-oo1wmt`

## Current state

Landed **Phase 1 of stable-identity "regulars"** (PROGRESS.md §12b). Phantom
populations can now define fixed `<regular>` identities (stable name + appearance,
optional class) via a `regularChance`, so a zone gets a few recurring, recognizable
faces instead of an all-random crowd. Stable name → stable brain personality (the
brain's `_voice()` hashes the name), no brain changes needed. Identity only — the
phantom is still ephemeral; persistence + friend tier are Phases 2-3.

Earlier this session: boot-time orphaned-phantom DB sweep (§10), haggle price clamp
(§11.3), prior-findings audit (§10b — 3 "High" items already fixed), and the
CLAUDE.md "what to transfer" standing rule.

## What was just done

- `PhantomManager.java`: added `Regular` inner class, `Population.regulars` +
  `regularChance`, `<regular>` XML parsing, `pickRegular()` + `isMageClass()`
  helpers, and the identity override in `createAndSpawn()`. Constant
  `REGULAR_CHANCE_DEFAULT = 25`. A regular is never spawned twice at once.
- `dist/game/data/PhantomPopulations.xml`: documented `regularChance` + `<regular>`
  attributes in the header comment and added a commented example.
- PROGRESS.md: §12b (how Phase 1 works + code touchpoints), §1b marked Phase 1 done,
  §10b bullet expanded into the full 3-phase plan.

## In flight / next up

- **Regulars Phase 2 (persistence)** and **Phase 3 (friend tier)** are the planned
  follow-ups — see PROGRESS.md §10b / §12b. Phase 3 touches stock Mobius packet
  handlers (`RequestAnswerFriendInvite` etc.), so get user approval before editing.
- Other open candidates (§10b / §11): ACTIVE_DEALS orphan-on-ignore TTL, phantom
  tuning config, verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build." This clamp change is
  hand-verified (isolated helper + two one-line call-site edits).
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
