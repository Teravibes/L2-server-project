# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-02
**Branch:** `claude/friends-phantoms-fpc-review-27ttjp`

## Current state

**The friend system is feature-complete and review-hardened** (PROGRESS.md §12d-§12g):
friend-invite ANY live phantom → instant accept + permanent promotion to a persistent
regular; kept online with you (self-healing 15s ensure pass); friend chat answered by the
brain in FRIEND mode; and **crafted friends are authored in the fpc-editor** (Phantoms →
Friends panel) as `<friend>` entries in `PhantomPopulations.xml`, materialized by the
supervisor when the owner is online. `//phantom reload` applies phantom XML edits live.
The editor's class dropdown now offers **every Interlude race/class** (grouped, real class
ids; buddy classes labeled; summoners omitted).

## What was just done (review pass + editor race/class picker)

- Reviewed the whole editor-crafted-friend implementation; verified the editor↔server
  round-trip, save path, reload idempotence, and all called signatures. Then fixed findings:
- `_populations` → `CopyOnWriteArrayList` (reload-vs-supervisor race, CME risk).
- Phantom-cap no longer consumes a `<friend>` order (checked before removal; retried later).
- Diagnosability: every parsed `<friend>` order logs at load; the name-already-exists inert
  skip logs too (was silent). "Nothing happened" is now answerable from the gameserver log.
- `//phantom reload` message warns recruited parties/buddies don't respawn.
- Editor: full race/class picker (grouped per race, `PlayerClass` ids), class names in the
  friend list, legacy keyword values still render. XML header docs updated.

## In flight / next up

- **USER-REPORTED**: a crafted friend for owner `BeregotGR` did not appear at login. Code
  path verified correct — most likely operational: live server not running a jar with the
  feature, XML not on live + no `//phantom reload`/restart, friend name already taken
  (was silent, now logged), or owner set to account name instead of character name. The new
  load/inert logging pinpoints it — check the gameserver log for "crafted-friend order(s)"
  and "<friend> order queued".
- Other open candidates (§10b / §11): ACTIVE_DEALS orphan-on-ignore TTL, phantom tuning
  config, verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build."
- Phase 3 edits four **stock** files (`RequestFriendInvite`, `RequestSendFriendMsg`,
  `EnterWorld`, `Disconnection`) — minimal hooks, but re-check them if you ever rebase onto
  a newer Mobius core.
- Crafted-friend orders are once-per-load except the phantom-cap case (retried). A spawn
  failure still consumes the order until the next `//phantom reload`.
- Friend-regulars auto-hunt from their spawn location (population==null: revive in place,
  no proximity-despawn) — that's the place to change their idle behaviour if it feels off.
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
