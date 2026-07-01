# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/handover-catchup-oo1wmt`

## Current state

Landed **Phase 1 of stable-identity "regulars"** (PROGRESS.md §12b), now with
**auto-generated slots**. A population gets recurring recognizable faces either via
`regularCount="N"` (auto — no manual authoring; deterministic per population+slot,
so identical every restart) or hand-authored `<regular>` entries, gated by
`regularChance`. Stable name → stable brain personality (the brain's `_voice()`
hashes the name), no brain changes needed. Identity only — the phantom is still
ephemeral; persistence + friend tier are Phases 2-3.

Earlier this session: boot-time orphaned-phantom DB sweep (§10), haggle price clamp
(§11.3), prior-findings audit (§10b — 3 "High" items already fixed), and the
CLAUDE.md "what to transfer" standing rule.

## What was just done

- `PhantomManager.java`: `Regular` inner class; `Population.regulars` / `regularCount`
  / `regularChance`; `<regular>` + `regularCount` parsing; `generateAutoRegulars()`
  (deterministic, seeded by population name + slot), `pickRegular()`, `isMageClass()`;
  identity override in `createAndSpawn()`. Constants `REGULAR_CHANCE_DEFAULT = 25`,
  `MAX_AUTO_REGULARS = 30`. A regular is never spawned twice at once.
- `FakePlayerAppearanceFactory.java`: new seeded `generateName(Random)` overload
  (reuses the same syllable pools) for stable auto-regular names.
- `dist/game/data/PhantomPopulations.xml`: documented `regularCount` + `regularChance`
  + `<regular>` attributes and added commented examples (auto + mixed).
- PROGRESS.md: §12b (how Phase 1 works + touchpoints), §1b Phase 1 done, §10b 3-phase
  plan + a new Phase 3 add-on: **player-crafted phantoms** ("recreate an old friend to
  play together") — user's idea, slotted with the friend tier.

## In flight / next up

- **Regulars Phase 2 (persistence)** then **Phase 3 (friend tier + player-crafted
  phantoms)** — see PROGRESS.md §10b / §12b. Phase 3 touches stock Mobius packet
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
