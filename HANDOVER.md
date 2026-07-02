# HANDOVER

> **Read this first.** A short, always-current snapshot so a new session is caught up
> in 30 seconds without re-reading everything. Updated as part of every commit.
> For deep detail see `PROGRESS.md`; for static project/build info see `CLAUDE.md`.

**Last updated:** 2026-07-01
**Branch:** `claude/next-priorities-pvt2rr`

## Current state

**The friend system is feature-complete** (PROGRESS.md §12d-§12f): friend-invite ANY live
phantom (field hunter, recruit, buddy) → instant accept + permanent promotion to a
persistent regular; kept online with you (self-healing 15s ensure pass); spawns at login /
despawns at logout at its last position; friend chat answered by the brain in **FRIEND
mode** (knows you're friends — warm tone + friendship written to `fpc_memory.json` so every
channel picks it up); and **crafted friends are authored in the fpc-editor** (Phantoms →
Friends panel): it writes `<friend>` entries into `PhantomPopulations.xml`, which the
server materializes once per order when the owner is online (create on `phantom_regular` +
spawn next to owner + befriend; name-already-exists = inert). The interim `//phantom
friend` admin command was **scrapped** (user choice) in favor of the tool; **new
`//phantom reload`** applies phantom XML edits live (no restart). Support-class friends
re-gear correctly as casters; buddy-class specs come back as proper idle buddies.
CHAT-DEBUG logging has been stripped (root cause was the 20s brain timeout vs ~30s Ollama
generation — now 45s).

Earlier this session: promotion-on-befriend (§12f), buddy befriending, Phase 3a+3b (friend
tier), Phase 2 (persistence), brain timeout fix.

## What was just done (batch: polish + friend features)

- **CHAT-DEBUG stripped** from `FakePlayerChatManager` and `PhantomPartyManager` (senders
  restored to their clean pre-debug form).
- **Support-class gear fix**: loaded/crafted regulars derive the caster flag from
  `PlayerClass.isMage()` (covers Bishop etc.), not the DD pool — in `spawnFriendRegular`,
  `createAndSpawn` (loaded branch) and `craftFriend`. Buddy classes still map to their
  `BuddyRole` first.
- **Brain FRIEND mode** (`fpc_brain.py` + `handleFriendMessage` now sends mode `FRIEND`):
  whisper persona + explicit "you two are friends" note (warm, familiar tone) + a
  `remember_fact(player, "social", "Player is in-game friends with <bot>")` so whisper/
  party/shout chats see the friendship too. Conversation history shared with whispers.
- **Crafted friends via the fpc-editor** (replaces the scrapped `//phantom friend`):
  editor Phantoms → Friends panel (form + list; `parsePhantomFriends`/`buildPhantomXml`
  round-trip `<friend>` nodes). Server: `CraftedFriend` orders parsed from
  `PhantomPopulations.xml`; `materializeCraftedFriends(owner)` runs in the supervisor's
  15s pass for each online real player — each order attempted once per load (name exists →
  permanently inert; failures log once), then `craftFriend()`: validates name (1-16
  alnum, free), resolves class keyword/id, level 0 = owner's, random looks where omitted,
  creates straight onto `phantom_regular`, spawns geo-snapped next to the owner (buddy
  classes → idle buddies; failed spawn deletes the half-made row), `befriendPhantom`.
  `AdminPhantom` gained `//phantom reload` → `PhantomManager.reloadPopulations()` (clear +
  re-parse, NO boot sweeps — those are only safe pre-spawn). XML header documents
  `<friend>`.

## In flight / next up

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
