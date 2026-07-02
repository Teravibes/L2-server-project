# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/next-priorities-pvt2rr`

## Current state

Landed **promotion-on-befriend** (PROGRESS.md §12f) on top of the full friend tier
(§12d/§12e): **friend-inviting ANY live phantom auto-accepts and promotes it into a
persistent regular on the spot** — no XML authoring needed at all (user decision after
discovering the live config defines zero regulars). Its row flips to `phantom_regular`
(durable: `storeMe()` never writes `account_name`), it's kept **online with you** by a
self-healing 15s supervisor pass (respawns after death / zone despawn), shows online at
login, despawns at your logout, and chats over the friend channel in its stable persona at
a human 1-4s cadence. Buddies are politely declined (population-managed fixtures).
Friend-deleting a regular prunes it live and its row is swept at next boot. Only the brain
"we're friends" **memory flag** remains (a `fpc_brain.py` follow-up).

Earlier this session: Phase 3a+3b (friend tier), Phase 2 (persistence), timeout fix
(brain 20s→45s — was why bots never replied), temporary CHAT-DEBUG logging (still in).

## What was just done (promotion-on-befriend, §12f)

- `PhantomManager.java`: `_promoted` set (in-memory bridge — `Player._accountName` is
  final, so a live promoted instance can't change account until reloaded), `isPhantom()`,
  `befriendPhantom()` (replaces `befriendRegular`: buddy decline → promote via
  `UPDATE characters SET account_name` → friendship insert → cache register),
  `_friendRegularsByOwner` cache + `ensureFriendRegulars()` + supervise ensure pass
  (15s, prunes friend-deleted ids via the owner's in-memory list), `despawn()` persistence
  now via `isRegular()` (protects live-promoted phantoms incl. recruits — `despawnRecruit`
  routes through `despawn`), boot-sweep second pass (friendless `phantom_regular` rows),
  spawn log now says `regular`/`friend-regular`/`phantom`/buddy.
- Stock `RequestFriendInvite`: hook now fires for **any** phantom (`isPhantom || isRegular`).
- Verified: `UPDATE_CHARACTER` has no `account_name` column (promotion can't be reverted by
  store); stock `RequestFriendDel` deletes both directions + updates in-memory lists.
- Known rough edge: a promoted support-class recruit re-spawns geared as melee
  (`isMageClass` only knows the DD pool) — functional, cosmetic follow-up.
- `CLAUDE.md`: added "When to delegate to Sonnet vs. do it on Opus" guidance.
- **Not compiled here** (JDK 21, no Ant) — hand-verified. Confirmed against source:
  `World.getPlayer(int)`/`getPlayers()`, `getFriendList()`, `L2Friend(Player,int)`,
  `L2FriendSay(...)`, `Player.load`/`restore()`→`restoreFriendList()`,
  `destroyAllItems(...)`, the `callBridge` 7-arg overload, `SystemMessageId` constants.

## ⚠️ Bot-chat-not-in-game: ROOT CAUSE FOUND + temporary debug still in tree

**Root cause:** the brain HTTP call had a **20s timeout**, but a local Ollama model takes
~30s per reply. Java gave up at 20s ("Brain bridge unreachable: request timed out" in the
*game-server* log), so no message was ever sent — while the brain finished ~30s later and
logged its 200 to nobody. That's why the brain terminal showed replies but the game showed
nothing. **Fix:** raised the timeout to a tunable `BRAIN_TIMEOUT_SECONDS = 45` in all three
managers (`FakePlayerChatManager`, `PhantomPartyManager`, `PhantomBuddyManager`; 4 call
sites). For snappy chat, use a faster/smaller Ollama model or `PROVIDER=deepseek` — 45s is
just the ceiling before a genuinely-slow generation is dropped.

**Still temporary — remove after confirming the fix:** `CHAT-DEBUG:` `LOGGER.info` lines at
every send site (`sendTradeChat`/`sendSayChat`/`sendShoutChat`/`sendChat`/`handleFriendMessage`
in `FakePlayerChatManager`; `askBrainAsync` in `PhantomPartyManager`). Keep them for one test
to confirm replies now arrive (a `CHAT-DEBUG` line per reply), then strip all `CHAT-DEBUG`.

## In flight / next up

- **Brain friendship memory (3b remainder):** persist a "we're friends" flag in
  `fpc_brain.py` (a `FRIEND` mode) so tone reflects the relationship — a Python-only
  follow-up (friend PMs currently use `WHISPER` mode: right persona, no explicit memory).
- **Player-crafted phantoms — authoring front-end only:** the "adopt anyone you meet" half
  is DONE (promotion-on-befriend, §12f). Remaining: an in-game UI/command to create one
  from scratch (name/appearance/class), which then funnels into the same promotion path.
- **Buddy befriending** (currently declined) and **promoted support-class recruits gear as
  melee** — both flagged in §12f as follow-ups.
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
