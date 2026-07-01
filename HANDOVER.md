# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/handover-catchup-oo1wmt`

## Current state

Landed the **boot-time orphaned-phantom DB sweep** (PROGRESS.md §10). Phantom/
buddy/party-member `characters` rows (`account_name='phantom'`) left behind by an
unclean shutdown are now bulk-deleted once at boot before anything spawns, ending
the DB-bloat / boot-RAM / name-collision growth. Also verified a prior session's
code-review findings against live code: the three "High" items were **already
fixed** — see the §10b audit so they aren't re-chased.

Earlier this session: the **haggle price clamp** (§11.3) and the CLAUDE.md
"what to transfer" standing rule.

## What was just done

- Added `PhantomManager.sweepOrphanedPhantoms()`, called first thing in `load()`.
  It queries every `account_name='phantom'` charId and deletes each via
  `GameClient.deleteCharByObjId()` (same cascade cleanup as `despawn()`). `load()`
  runs once per JVM (lazy singleton, not touched by `//reloadfakeplayers`), so it
  can never delete a live phantom's row. New imports: `java.sql.*`, `DatabaseFactory`.
- Recorded the finding audit in PROGRESS.md §10b: 3 High items already fixed;
  still-open lower-priority items (ACTIVE_DEALS orphan-on-ignore, non-atomic trade
  rate limiter) + unverified relayed items listed for a future pass.

## In flight / next up

- Nothing mid-change. Next candidates (PROGRESS.md §10b / §11): ACTIVE_DEALS
  orphan-on-ignore TTL, phantom tuning config, stable-identity "regulars" (design
  idea in §1b), or verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build." This clamp change is
  hand-verified (isolated helper + two one-line call-site edits).
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
