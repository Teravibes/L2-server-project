# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/next-priorities-pvt2rr`

## Current state

Landed **Phase 3a of stable-identity "regulars" (friend tier: befriend + chat)**
(PROGRESS.md §12d). You can now friend-invite a regular (auto-accepted server-side, since
it's clientless) and hold a private friends-list conversation with it, answered by the LLM
brain in the regular's stable persona and replied over the friend channel. Built on the
Phase 2 stable `charId`, so the `character_friends` row survives the phantom despawning/
respawning. Stock packet-handler edits are thin (guard + delegate); logic lives in the
custom managers. **Phase 3b deferred** (the invasive part): spawning friend-regulars at
owner login so they show "online" even away from their zone, + a brain friendship-memory
flag. Until then a regular is only online / friend-PM-able while spawned near its zone
(degrades to "friend offline").

Earlier this session: Phase 2 (persistence — regulars get a stable `charId` under the
`phantom_regular` account, re-geared each spawn on load), and Phase 1 (identity only).

## What was just done

- `PhantomManager.java`: `isRegular(Player)` (account-name check) and `befriendRegular()`
  (server-side accept — `INSERT INTO character_friends` both ways + both in-memory lists +
  system message + `L2Friend` online packet), mirroring `RequestAnswerFriendInvite`.
- `FakePlayerChatManager.java`: `handleFriendMessage()` — calls the brain with the regular's
  name (mode `WHISPER` → same stable `_voice()` persona) and replies as `L2FriendSay` after
  the usual think + typing delay; canned fallback if the brain is offline. Note a regular is
  a `Player` phantom, so it's outside the Npc-based whisper path (`resolveBot`) — dedicated.
- Stock `RequestFriendInvite` / `RequestSendFriendMsg`: one guarded `isRegular` → delegate
  call each. Respawn-safe because `Player.load` → `restore()` → `restoreFriendList()`.
- `CLAUDE.md`: added "When to delegate to Sonnet vs. do it on Opus" guidance under Model
  workflow (do it yourself when small or high-judgment / touching stock core).
- **Not compiled here** (JDK 21, no Ant) — hand-verified. Confirmed against source:
  `getFriendList()`, `L2Friend(Player,int)`, `L2FriendSay(sender,recv,msg)`,
  `restore()`→`restoreFriendList()`, the `callBridge` 7-arg overload, `SystemMessageId`
  constants.

## In flight / next up

- **Regulars Phase 3b** — spawn friend-regulars at owner `EnterWorld` (show "online" away
  from zone; dedup vs population spawns; despawn on logout) + persist a "we're friends"
  flag in `fpc_brain.py` memory (needs a `FRIEND` brain mode). This is the invasive piece;
  give it its own reviewed pass. Then **Phase 3 add-on — player-crafted phantoms**.
- Other open candidates (§10b / §11): ACTIVE_DEALS orphan-on-ignore TTL, phantom tuning
  config, verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build." Phase 3a is hand-verified (all
  called signatures confirmed against source; stock-handler edits kept to guard+delegate).
- Phase 3a edits two **stock** packet handlers (`RequestFriendInvite`,
  `RequestSendFriendMsg`) — minimal hooks, but re-check them if you ever rebase onto a
  newer Mobius core.
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
