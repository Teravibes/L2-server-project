# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/next-priorities-pvt2rr`

## Current state

Landed **the full friend tier for stable-identity "regulars" — Phase 3a + 3b**
(PROGRESS.md §12d/§12e). You can friend-invite a regular (auto-accepted server-side, since
it's clientless), it shows **"online" whenever you're logged in** (spawned at its own
location on your `EnterWorld`, despawned on your logout), and you can hold a private
friends-list conversation with it — answered by the LLM brain in the regular's stable
persona, over the friend channel, at a **human 1-4s cadence**. All built on the Phase 2
stable `charId`, so `character_friends` rows survive despawn/respawn/reboot. Stock
packet-handler edits (`RequestFriendInvite`, `RequestSendFriendMsg`, `EnterWorld`,
`Disconnection`) are thin guard+delegate; logic lives in the custom managers. Only the
brain "we're friends" **memory flag** remains (a `fpc_brain.py` follow-up).

Earlier this session: Phase 2 (persistence — regulars get a stable `charId` under the
`phantom_regular` account, re-geared each spawn on load), and Phase 1 (identity only).

## What was just done

- `PhantomManager.java`: 3a — `isRegular()` + `befriendRegular()` (server-side friend accept:
  `INSERT INTO character_friends` both ways + both in-memory lists + `L2Friend` online packet).
  3b — `onOwnerLogin()`/`onOwnerLogout()` (login-spawn / logout-despawn of a player's
  friend-regulars), `spawnFriendRegular()` (loads by charId, spawns at its stored location),
  `findFriendRegulars()`, `otherOnlineFriendOf()` (hand-over guard), extracted shared
  `finishSpawn()`, new `PhantomData.friendOwnerId`, constant `FRIEND_SPAWN_DELAY`.
- `FakePlayerChatManager.java`: `handleFriendMessage()` — brain call (mode `WHISPER` → stable
  `_voice()` persona) replying as `L2FriendSay` at a human cadence (`FRIEND_THINK_MIN..MAX` +
  typing time, ~1-4s); canned fallback if the brain is offline. A regular is a `Player`
  phantom, so it's outside the Npc whisper path (`resolveBot`) — dedicated path.
- Stock hooks (all one guarded delegate call): `RequestFriendInvite`, `RequestSendFriendMsg`,
  `EnterWorld` (login), `Disconnection.storeAndDelete` (logout).
- `CLAUDE.md`: added "When to delegate to Sonnet vs. do it on Opus" guidance.
- **Not compiled here** (JDK 21, no Ant) — hand-verified. Confirmed against source:
  `World.getPlayer(int)`/`getPlayers()`, `getFriendList()`, `L2Friend(Player,int)`,
  `L2FriendSay(...)`, `Player.load`/`restore()`→`restoreFriendList()`,
  `destroyAllItems(...)`, the `callBridge` 7-arg overload, `SystemMessageId` constants.

## ⚠️ Temporary debug in tree (revert after diagnosis)

Investigating a report that **bot chat replies never appear in-game** even though the
`fpc_brain` terminal shows the replies being generated (200 OK). Added `CHAT-DEBUG:`
`LOGGER.info` lines at every send site so the **game-server console** (not the brain
terminal) reveals whether each reply is actually sent, to how many players, and whether
anything drops it:
- `FakePlayerChatManager`: `sendTradeChat` / `sendSayChat` / `sendShoutChat` (recipient
  count + bot objId/spawned), `sendChat` (WHISPER: logs if `resolveBot` returns null =
  dropped), `handleFriendMessage` (FRIEND-PM).
- `PhantomPartyManager.askBrainAsync`: logs empty-after-`applyTags`, party-broadcast vs
  whisper-fallback (bot not actually partied), and the not-sent case.

Diagnosis plan: rebuild jar, chat in-game, read the game-server console. If a `CHAT-DEBUG`
line appears but nothing shows in-game → client/render side. If it never appears → the send
aborts upstream (the log narrows where). **Remove all `CHAT-DEBUG` lines once resolved.**

## In flight / next up

- **Brain friendship memory (3b remainder):** persist a "we're friends" flag in
  `fpc_brain.py` (a `FRIEND` mode) so tone reflects the relationship — a Python-only
  follow-up (friend PMs currently use `WHISPER` mode: right persona, no explicit memory).
- **Phase 3 add-on — player-crafted phantoms** (author a persistent phantom to spec, then
  befriend it): builds on all of the above.
- Other open candidates (§10b / §11): ACTIVE_DEALS orphan-on-ignore TTL, phantom tuning
  config, verifying the still-unverified relayed findings.
- Standing rules in play: update HANDOVER.md every commit; end every change with a
  "what to transfer to the live server" list.

## Watch out for

- Java build can't run here (needs JDK 25 + Ant; env has Java 21). Java changes are
  hand-verified, not compiled — don't claim they "build." Phase 3a+3b are hand-verified (all
  called signatures confirmed against source; stock-handler edits kept to guard+delegate).
- Phase 3 edits four **stock** files (`RequestFriendInvite`, `RequestSendFriendMsg`,
  `EnterWorld`, `Disconnection`) — minimal hooks, but re-check them if you ever rebase onto
  a newer Mobius core.
- Friend-regulars auto-hunt from their spawn location (they're `population==null`, so they
  revive in place on death and don't proximity-despawn) — if that ever feels off, that's the
  place to change their idle behaviour.
- Project dir name has a space: `L2J_Mobius_CT_0_Interlude github` — quote paths.
