# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/handover-catchup-oo1wmt`

## Current state

Landed the **haggle price clamp** (PROGRESS.md §11.3). Whisper-negotiated trade
prices from the LLM are now bounded server-side in `FakePlayerStoreFactory` so a
bot can no longer be talked into selling a rare item for 1 adena or buying junk
for billions. First real game-code change on top of the repo-onboarding setup.

## What was just done

- Added `clampDealPrice(unitPrice, referencePrice, selling)` to
  `FakePlayerStoreFactory.java` and wired it into `dealSellStock`/`dealBuyStock`
  (the single choke point). Band (moderate): SELL floor 0.5× / ceiling 3×,
  BUY floor 0.1× / ceiling 1.5× of the item reference price. Haggling still works
  inside the band; only absurd values get pinned. Also neutralizes the `k`/`kk`
  multiplier trick since the clamp applies to the final per-unit price.
- Auto-priced initial quotes (price arg `0`) are unaffected — clamp only fires on
  an explicit negotiated price.

## In flight / next up

- Nothing mid-change. Clamp band is a one-line tuning knob if it feels too
  tight/loose in play. Next candidates (PROGRESS.md §11): phantom tuning config,
  field-behavior tuning.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build." This clamp change is
  hand-verified (isolated helper + two one-line call-site edits).
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
