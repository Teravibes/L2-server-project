# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/next-priorities-pvt2rr`

## Current state

Landed **Phase 2 of stable-identity "regulars" (persistence)** (PROGRESS.md §12c). A
regular now gets a real, persistent character row under a distinct account
(`phantom_regular`) with a **stable `charId`** across reboots — first spawn creates it,
every spawn after `Player.load()`s the same row back instead of re-creating/re-gearing
it. Random phantoms are completely unchanged (still ephemeral under `phantom`, deleted
on despawn). The boot orphan-sweep already only targets `account_name='phantom'`, so it
leaves regulars alone with no change needed. `despawn()` now saves (`storeMe()`) and
keeps a regular's row instead of deleting it. This is the prereq the friend tier
(Phase 3) needs: `character_friends` can now reference a stable regular `charId`.

Earlier: Phase 1 (identity only — auto-generated + hand-authored `<regular>` slots,
stable name → stable brain voice), boot-time orphaned-phantom DB sweep, haggle price
clamp, prior-findings audit.

## What was just done

- `PhantomManager.java`: new constant `ACCOUNT_NAME_REGULAR = "phantom_regular"`; new
  `findRegularCharId(String)` (account-scoped DB lookup, mirrors `sweepOrphanedPhantoms`'s
  query style); `createAndSpawn()`'s regular branch now `Player.load()`s an existing
  regular (stable charId) or creates one under `ACCOUNT_NAME_REGULAR` on first spawn. A
  loaded regular keeps its persisted level/class/skills but is **re-geared each spawn**
  (inventory wiped, then the normal `outfit`/`outfitBuddy` runs) so its consumables +
  soulshot/auto-use registrations come back — the exp/class/skill steps are guarded no-ops
  the second time. `despawn()` detects a persistent regular via `getAccountName()`, calls
  `storeMe()`, and skips `GameClient.deleteCharByObjId(...)` so the row survives. Random-
  phantom path (`ACCOUNT_NAME`) is untouched.
- Also made `transferClass()` idempotent (bails if the char is already at its warranted
  tier) so a reloaded, already-transferred regular isn't over-advanced; and the caster
  flag for a loaded regular is derived from its **restored** class, not the pre-load roll.
- PROGRESS.md: §10b Phase 2 marked done; new §12c documents the full mechanism and code
  touchpoints.
- **Not compiled here** (JDK 21, no Ant in this env) — hand-verified only. Signatures
  used (`Player.load(int)`, `Player.create(...)`, `Player.storeMe()`,
  `Player.getAccountName()`, `getInventory().destroyAllItems(...)`, `PlayerClass.level()`,
  `GameClient.deleteCharByObjId(int)`) were all confirmed against the actual source.

## In flight / next up

- **Regulars Phase 3 (friend tier + player-crafted phantoms)** — see PROGRESS.md
  §10b / §12b. Touches stock Mobius packet handlers (`RequestAnswerFriendInvite` etc.),
  so get user approval before editing.
- Other open candidates (§10b / §11): ACTIVE_DEALS orphan-on-ignore TTL, phantom tuning
  config, verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build." This Phase 2 change is
  hand-verified (all called methods confirmed against Player.java/GameClient.java
  source, not assumed).
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
