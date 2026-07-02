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

## What was just done (friend usability batch — PROGRESS §12h)

Root cause of "my crafted friend never appeared": the user added the friend in the editor
but never clicked Save, so `PhantomPopulations.xml` was never written. Fixed + two more gaps:

- **Editor auto-saves**: add/delete friend writes the XML immediately (no Save click).
- **PM to a friend works**: new `ChatWhisper` hook (friend-list-gated) → brain FRIEND mode,
  reply as a whisper. Previously fell through to "Player is in offline mode."
- **Party invite works**: new `RequestJoinParty` hook (friend-list-gated) →
  `onInvitedFriend` adopts the live friend as a recruited member (full party AI: follow/
  assist/raid/support). Party ends → row stored → ensure pass respawns it idle in ~15s.
- **Friends gear properly**: `outfitFriend` (friend spawns) uses `gearParty` — best-in-grade
  weapon, full armor, jewelry, tank shield, enchant chance + full buffs. Existing friends
  upgrade automatically on next spawn (loaded regulars are re-geared each spawn).
- Earlier this session: review-pass fixes (`_populations` COW list, cap doesn't consume
  orders, order logging) + full race/class picker in the editor.

## In flight / next up

- Live-verify the whole friend loop: author in editor (auto-saves) → copy XML + new jar +
  ChatWhisper.java to live → `//phantom reload` → friend spawns, PM it, party it.
- Other open candidates (§10b / §11): ACTIVE_DEALS orphan-on-ignore TTL, phantom tuning
  config, verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build."
- The friend tier now hooks five **stock** java files (`RequestFriendInvite`,
  `RequestSendFriendMsg`, `EnterWorld`, `Disconnection`, `RequestJoinParty`) plus the
  datapack `ChatWhisper` — minimal guard+delegate hooks, but re-check them if you ever
  rebase onto a newer Mobius core.
- Crafted-friend orders are once-per-load except the phantom-cap case (retried). A spawn
  failure still consumes the order until the next `//phantom reload`.
- Friend-regulars auto-hunt from their spawn location (population==null: revive in place,
  no proximity-despawn) — that's the place to change their idle behaviour if it feels off.
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
