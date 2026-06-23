# Fake Player "Living World" — Project Progress & Handoff

> Read this first if you're picking up the project fresh. It explains what this is,
> the goal, how everything fits together, what's done, how to build/deploy, and what's next.

---

## 1. What this is

This is a fork of the **L2J Mobius CT_0 Interlude** Lineage 2 server. The owner runs it for
**offline / solo play** and wants the world to *feel alive* — towns full of believable players,
trade chatter, shops, and people out farming — without other humans online.

Two systems were built on top of Mobius's bare-bones "fake player" (FPC) feature:

1. **AI chat** — fake players hold real conversations (whisper / trade / say) via an external LLM.
2. **A living-world system** — hundreds of procedurally generated fake players that look unique,
   cluster in towns, run private shops, and roam hunting zones, all data-driven and editable from a
   visual tool.

The end goal: log in and the server feels like a populated live server.

---

## 2. The two big pieces

### A) LLM chat brain — `fpc_brain.py` (repo root)
A small **Flask** sidecar (separate process) that the Java server calls over HTTP.

- Talks to **DeepSeek** (or local **Ollama**) via the OpenAI client; provider chosen by `PROVIDER` env.
- One endpoint `POST /chat`; the Java side sends the message + headers (`X-FPC`, `X-Mode`,
  `X-Player`, `X-Speaker`).
- Modes: **WHISPER** (private, per-(player,bot) memory), **SAY** (local), **TRADE/AMBIENT** (global
  trade channel). Personas keep replies short, lowercase, in-character, never "I am an AI".
- Bots can reply `pass` to stay silent (mapped to empty → no message), which keeps chatter natural.

**Java side that drives it:** `gameserver/managers/FakePlayerChatManager.java`
- Hooks `overheardTradeChat` / `overheardSay` (called from the chat handlers) → picks nearby bots →
  HTTP to the brain → broadcasts the reply. Throttled (reply chance, max repliers, per-minute cap).
- Repliers are **deduped by name** so duplicate-named spawns don't answer the same line twice.
- Whisper path is wired via `ChatWhisper.java` (`manageChat`).

**Run the brain:** `python fpc_brain.py` (needs `PROVIDER` + the relevant API key env). It listens on
`127.0.0.1:5000`. If it's offline, the Java side silently falls back to canned chat.

### B) Living-world behavior system — the heart of the recent work
A single Java manager plus a data file plus a visual editor.

---

## 3. Living-world architecture (read this carefully)

### The keystone: per-instance appearance
Mobius fake players are **NPCs**, not real `Player` objects. Normally **one NPC template = one
fixed-looking character** (name, gear, class all baked into the template). To get *hundreds of unique*
bots from **one** base template, we added a per-instance appearance override:

- **`model/actor/holders/npc/FakePlayerAppearance.java`** — a per-bot identity: name, title, race,
  gender, class, hair/face/colors, gear slots, and **private-store type + sitting + store message**.
- **`model/actor/Npc.java`** — carries an optional `FakePlayerAppearance`; `getName()` honors it; new
  `get/setFakePlayerAppearance`. `sendInfo()` sends the store-title packet for vendors.
- **`network/serverpackets/FakePlayerInfo.java`** — the packet that renders an NPC as a player. It now
  reads from the appearance override when present (name, race, sex, class, gear, hair, colors, sitting,
  store type), else falls back to the template. **Existing template bots are unaffected.**

### The generator: `managers/FakePlayerAppearanceFactory.java`
Static factory that builds a `FakePlayerAppearance`:
- Unique pronounceable **names** (syllable pools, dedup set).
- Coherent random **race / gender / class** (race-appropriate first-occupation classes; orcs/dwarves
  skew male), optional **dominant race** bias for a population.
- **Gear** by a "wealth" roll so a same-level crowd shows mixed grades (most poor/average, a few fully
  kitted; capped at C-grade). Item ids are **verified from the datapack** (`stats/items`). Helmets/
  gloves often skipped for variety.
- **Store titles** per kind (WTS / WTB / craft).

### The brain: `managers/FakePlayerBehaviorManager.java`
A lightweight, data-driven manager (no LLM in the loop — pure rule-based, scales to hundreds).

- **Loads `data/FakePlayerBehavior.xml`** (validated by `data/xsd/FakePlayerBehavior.xsd`).
- **Profiles** define *how* a bot moves:
  - `WANDER` — random hops within a radius of its anchor.
  - `VISIT` — walks between a list of points of interest with long idles ("purposeful" town movement).
  - `PATROL` — loops a waypoint list.
  - `FARM` — seeks the nearest monster in its zone, fights, then roams to find more.
- **Populations** define *where / how many / what*: center, radius, count, level range, profile,
  optional `race`, optional `respawn`, optional `store` (SELL/BUY/CRAFT/PACKAGE), and an optional
  **polygon** (`<point>` children) — bots spawn **inside the drawn shape** instead of a circle.
- **Deploy** (~15s after boot): spawns each population from one base template
  (`FakePlayerBaseNpcId`, auto-picks first), attaches a generated identity, snaps Z to ground via
  `GeoEngine.getHeight`, registers it with a small finite-state machine. Logs
  `===== N BOTS DEPLOYED =====`. Per-population under-deploy warnings help find bad anchors.
- **Per-bot FSM tick** (every 3s): idle → pick destination → move → idle, with combat back-off.
  Movement uses normal AI `MOVE_TO` + geodata pathfinding; wander targets are validated with
  `canMoveToTarget` to reduce wall-running.
- **Vendors** (store populations) are seated, **immobilized + core-AI-disabled** so they never move.
- **Field hunters** respawn with a fresh identity when they die (`respawn="true"`).

### Config — `dist/game/config/Custom/FakePlayers.ini`
- `EnableFakePlayers`, `FakePlayerChat`, `FakePlayerBehavior` (master switches).
- `FakePlayerDeployCount` (fallback only; populations drive deployment when present).
- `FakePlayerBaseNpcId` (0 = auto-pick first fake-player template).
- New flags are parsed in `config/custom/FakePlayersConfig.java`. Manager is booted in
  `GameServer.java` right after `WalkingManager`.

### Data — `dist/game/data/FakePlayerBehavior.xml`
The single source of truth for the living world: town clusters (Giran market/gatekeeper/warehouse,
starting villages with racial bias, Gludin/Gludio), Giran private-shop populations, and ~26 field
hunting zones bracketed by level. All coordinates came from the datapack (town respawn points, NPC
spawns, zone polygon vertices) — **not guessed**.

---

## 4. The editor tool — `tools/fpc-editor/index.html`

A **single self-contained HTML file** (no server/build/install) to edit `FakePlayerBehavior.xml`
visually. Open it in a browser.

- **Load/Save XML** — populations are editable; profiles/assigns/default are preserved verbatim.
- **Load geodata** — point it at `game/data/geodata`; it parses the `.l2j` files **in-browser**
  (mirrors the server's region/block format) and renders the real world map as a height-relief
  background, **auto-aligned** to coordinates. Crisp when zoomed in.
- **Landmarks** — towns/villages drawn at real coordinates for orientation (no map image needed).
- **Map image** (optional) — load a client world-map image over the geodata, with an opacity slider.
- **Edit** — double-click to add a population; drag circles; side form for count/level/profile/race/
  store/respawn. **Draw zone**: click points to outline a polygon area (saved as `<point>` vertices).
- **Show/hide** individual populations (eye toggle) or all at once.

Workflow: edit visually → **Save XML** → copy to `game/data/` → restart server.

---

## 5. How to build & deploy

- **Java changes** (anything under `java/`): the owner builds with **`ant`** on `build.xml`
  (needs JDK 25), then copies the produced **`GameServer.jar`** into the live server. NOTE: chat
  *handlers* (`ChatTrade.java`, `ChatGeneral.java`) live under `dist/game/data/scripts/` and are
  **runtime-compiled** — they must be copied into the live datapack, not bundled in the jar.
- **Datapack changes**: copy the edited files into the live `game/data/...`
  (e.g. `FakePlayerBehavior.xml`, its `xsd`, the `FakePlayers.ini` keys).
- **Brain**: restart `fpc_brain.py` if it changed.
- **Geodata**: the live server needs **`.l2j`** cell geodata in `game/data/geodata/`
  (`%d_%d.l2j` names). Without it, pathfinding is auto-disabled and field bots can't place on ground.
  Log line `GeoEngine: Loaded N regions.` with N>0 = good.

> Build can't be done in this dev environment (Java 21, no Ant); all Java was verified by hand. If a
> build error appears, it's almost always a quick import/signature fix.

---

## 6. Status — what works

- ✅ LLM chat: whisper / say / trade, with silence + reply dedup. Roaming bots are whisper-able by
  their generated name and know their nearest town; seated AFK vendors stay silent.
- ✅ Trade-ad matchmaking + roaming trade (Phase 2): a WTS/WTB in trade chat can make one relevant
  same-town bot PM you, walk to its gatekeeper, and open a real one-item store there so you actually
  buy/sell. **Or** negotiate it entirely in whispers: when the bot agrees to a specific item + price it
  emits a hidden `[[SHOP:SELL|BUY:item:price]]` tag; the chat manager arms/opens the store
  (`openDealNow` if it is already waiting, else `setupDeal` + walk). Item is resolved from text; trades
  reset the wait timer so it won't leave mid-deal; the store tears down when sold out / the meet ends,
  and the bot resumes roaming. (Caveat: while its store is open the bot is treated as an AFK vendor and
  stops whispering until the deal ends.)
- ✅ Procedural identities: hundreds of unique names/races/genders/classes/hair/gear from one template.
- ✅ Town life: idler clusters around NPCs, purposeful VISIT movers, racial villages, Giran market.
- ✅ Private shops (**functional**): seated SELL/BUY/CRAFT/PACKAGE vendors with item-accurate signs;
  click to open a real store window and actually buy/sell (adena moves, items transfer, stock
  decrements). A vendor that sells out **closes** (sign removed) — no runtime restock; fresh stock is
  generated for every vendor on each server start. Stock is procedurally generated from the datapack
  item table — realistic reference-price-based pricing, level-gated grades with weighted scarcity
  (S-grade stays rare), and value-scaled bulk amounts. CRAFT vendors are deployed as finished-goods sellers.
- ✅ Field hunters: 26 level-bracketed zones, monster-seeking FARM behavior, respawn.
- ✅ Geodata-aware placement (Z-snap) and reduced wall-running.
- ✅ Visual editor with in-browser geodata map, landmarks, polygon zones, visibility, opacity.

## 7. Known issues / rough edges

- **Field hunting feel**: bots can still cluster / move sluggishly in some zones — pathfinding +
  combat tuning is a rabbit hole; deprioritized in favor of town life.
- **Shops** (Phase 2 done): clicking a vendor opens a working store window and trades for real. Custom
  NPC-side packets (`FakePlayerStoreList{Sell,Buy}`) plus interception in `NpcClick` and
  `RequestPrivateStore{Buy,Sell}` route around the `Player`-only store path. CRAFT is currently a
  finished-goods seller (renders a "Sell" tag with a "crafting X" title), not a true manufacture/recipe
  flow. CRAFT vendors are **real manufacture stores**: they show the manufacture sign, open a recipe
  board on click, and craft for real — the customer supplies the materials and pays the fee, the bot
  rolls the recipe's success rate and hands over the product (custom NPC packets
  `FakePlayerRecipeShop{SellList,ItemInfo}` + interception in `RequestRecipeShopMake{Info,Item}`; a
  simplified single-shot craft, no MP/stat-use). Sold-out vendors close (no runtime restock — they
  repopulate on restart). Buy stores list
  **everything they want**; items the viewer lacks come through with a sellable amount of 0 so the
  client greys them out (standard L2 behaviour). `Npc.sendInfo` reads the store title from the
  per-instance appearance (not the template), so signs show when you walk up. Pricing markup + grade scarcity weights are hard-coded in
  `FakePlayerStoreFactory` for now (config knobs are a planned follow-up). Needs an ant rebuild +
  in-game test (untestable in this dev env).
- **Map image**: must be supplied by the user (not in server files); calibration is bounds-based
  (a friendlier 2-click calibration was discussed but not built).
- **Whisper to generated bots** (done): you can now whisper a roaming bot by its generated name —
  `ChatWhisper` resolves it from the live world and `FakePlayerChatManager` replies via the brain.
  **AFK store vendors are intentionally silent** (treated as offline shops) in whisper, trade and say.
  Bots also get an `X-Location` (nearest town) so they answer "where are you?" truthfully.
- **"Come meet me" (done):** in a whisper you can ask a roaming bot to meet you at a same-town spot
  (gatekeeper / warehouse / shop) and it actually walks there. The brain agrees and appends a hidden
  `[[MEET:spot]]` tag; `FakePlayerChatManager` strips it and calls `FakePlayerBehaviorManager.requestMeet`,
  which resolves the spot to the nearest real Teleporter/Warehouse/Merchant NPC. The FSM aims `MOVE_TO`
  at the **real** destination so the engine pathfinds around walls (no wall-banging; 45s give-up if no
  path). On arrival it **pins itself** (`disableCoreAI`/`setImmobilized`) so the core AI can't drag it
  back to spawn, and **waits**: it stays until you show up, you call it off (`[[MEET:cancel]]` →
  `cancelMeet`), or you go quiet — after ~5 min it whispers "still coming?" and leaves if no reply within
  3 min (any whisper resets the timer via `noteMeetInteraction`). Same-town only — long cross-town
  pathfinding is unreliable.
- **Trade-ad → roaming trade (Phase 2, done):** `overheardTradeChat` schedules `respondToTradeAd`
  off-thread. It parses WTS/WTB, asks the brain (`ITEM` mode) to translate slang like "ssd" into a
  plain item name, then grounds it to a real item via `FakePlayerStoreFactory.findItemByName` (token
  matcher; raw words are the offline fallback), picks a nearby roaming
  bot (`pickTradeResponder`), arms it with a one-item BUY/SELL deal (`setupDeal` + `dealBuy/SellStock`),
  sends it to its gatekeeper (`requestMeet`) and PMs the player (brain `OFFER` mode). On arrival the FSM
  activates the store (it becomes a clickable vendor); selling out (or the meet ending) tears the store
  down via `endMeet` and the bot roams again. Reuses all the existing store packets/transactions.
  Cross-town and exact-item matching are best-effort; relies on a roaming bot being near the player.

## 7b. Real-Player phantoms (NEW — parallel system, vertical slice)

A **second, independent** bot system alongside the NPC fake players above. Where FPCs are NPCs
*rendered* to look like players, a **phantom is a genuine `Player` database object with no client
attached** — the same clientless-Player pattern Mobius already ships for **offline traders**
(`OfflineTraderTable.restoreOfflineTraders`: `Player.load` → `setOnlineStatus` → `spawnMe` →
`broadcastUserInfo`, no `GameClient`). This unlocks real skills/inventory/stats/leveling/PvP that an
NPC structurally can't have. Intended split: **towns = NPC FPCs** (chat/shops/crowd), **field =
real-Player phantoms** (combat/leveling).

**Why this is safe in our tree (verified):** `Player.sendPacket` is null-client guarded
(`if (_client != null)`), the `gameserver/ai` package has **zero** direct `getClient().` derefs, and
offline traders already prove clientless Players live in the World loop and are visible to others.

**Combat = native auto-hunt (key decision).** Instead of hand-rolling a combat FSM (or porting
miacodeweb's `PhantomAI`), phantoms are driven by the engine's own **`AutoPlayTaskManager` +
`AutoUseTaskManager`** — the exact systems `OfflinePlayTable.restoreOfflinePlayers` uses to make a
clientless player farm. They handle target-finding, movement (geodata pathfinding), attacking, and —
once phantoms are leveled/geared — skills, buffs, soulshots and potions, all tested and maintained
upstream. `PhantomManager` now owns only the **macro** layer.

**Done (increment 2):**
- `managers/PhantomManager.java`
  - Spawn a clientless `FIGHTER` (offline-play pattern: `setOnlineStatus` → `spawnMe` →
    `setOfflinePlay(true)` → `broadcastUserInfo`). `setOfflinePlay(true)` is **required** — a phantom
    has `_client == null`, so `isInOfflineMode()` is true, and without the offline-play flag AutoPlay
    would immediately stop the task.
  - `enableAutoHunt`: sets `AutoPlaySettings` (target mode = monsters, long range, pickup on), adds
    auto-action `2` (basic melee — without it AutoPlay treats the char as a non-hitting mage caster),
    then `startAutoPlay` + `startAutoUseTask`.
  - Light **supervisor** tick (5s): forgets phantoms that left the world; **revives** dead ones at
    their home spot after 15s and resumes hunting.
- `handlers/chat/commands/admin/AdminPhantom.java` — `//phantom spawn [count] | clear | count`
  (registered in `MasterHandler.java`).

**REQUIRED config:** set `EnableAutoPlay = True` in `config/Custom/AutoPlay.ini` (default false). With
it off, phantoms spawn but stand still (the manager logs a warning).

**Done (increment 3 — leveling + skills):**
- `//phantom spawn [count] [level]` — level arg added (1-80), e.g. `//phantom spawn 5 20`.
- `PhantomManager.outfit(phantom, level)`: grants the exact experience for the level (same approach as
  the admin level command — `addExpAndSp(getExpForLevel(level) - getExp())`, which rewards class skills
  level-by-level), then `rewardSkills()` to be sure, tops up HP/MP/CP.
- `registerAutoSkills(phantom)`: **data-driven, no hard-coded ids** — scans the phantom's learned
  skills and registers self-buffs (`isContinuous() && !isDebuff() && effectPoint>=0 && target==SELF`)
  into `getAutoBuffs()` and offensive actives (`isActive() && !isContinuous() && effectPoint<0`) into
  `getAutoSkills()`, so the native AutoUse system buffs and casts in combat.

**Done (increment 4 — weapon + soulshots):**
- `PhantomManager.gear(phantom, level)`: equips a grade-appropriate **set** — a **sword** plus
  **LIGHT/HEAVY armor** (chest, legs, gloves, boots, helmet) — and hands over matching **soulshots**
  (auto-enabled via `addAutoSoulShot`). The weapon makes the base-fighter attack skills (Power Strike /
  Mortal Blow) usable (diagnosed cause of "no skills" was a naked phantom); the armor keeps it alive.
- `buildGear()`: resolves the cheapest (most basic) tradeable item per grade **per slot** from
  `ItemData` once — data-driven, no hard-coded ids; `pickForSlot()` steps down grades if a slot has no
  candidate; FULL_ARMOR is skipped so it never conflicts with the separate legs piece; robes/shields/
  sigils are excluded (phantoms are fighters). `gradeForLevel()` maps level→grade aligned with
  Expertise (NONE<20, D<40, C<52, B<61, A<76, S≥76); `soulshotIdFor()` maps grade→shot id.
- **Spawn order matters for visuals:** level/skills/**gear run BEFORE `spawnMe()`** so the first
  `CharInfo` nearby clients receive already shows the weapon. (Equipping after spawn worked
  functionally — soulshots fired — but the blade didn't render because `broadcastCharInfo` is throttled
  and the update was being coalesced/missed.)

**Done (increment 5 — data-driven deployment + death/respawn):**
- `PhantomManager` is now an `IXmlReader` that loads **`data/PhantomPopulations.xml`** on boot (its own
  file, separate from the NPC `FakePlayerBehavior.xml`) and **deploys** ~20s after start.
- `<population>`: `name`, `x/y/z`, `radius`, `count`, `minLevel`, `maxLevel`, `respawn`, and optional
  `<point>` **polygon** (spawn inside the shape instead of a circle). Phantoms are scattered within the
  group and **Z-snapped to ground via geodata** (reuses the behavior-manager's scatter/polygon/Z-snap
  idioms) so they spread out and don't stack.
- **Death → despawn → respawn:** population phantoms that die are removed and a **fresh** phantom is
  spawned into the same group after `RESPAWN_DELAY` (15s). Ad-hoc admin-spawned phantoms still revive in
  place (stable test count).
- Booted in `GameServer.java` after `FakePlayerBehaviorManager`. Ships with an **empty** populations
  file (documented example commented out) so nothing auto-deploys until configured — `//phantom spawn`
  testing is unaffected.
- **Anti-clump tuning:** spawn scatter is area-uniform (sqrt) with a `MIN_SEPARATION` (250) retry so a
  group doesn't stack on one tile; auto-hunt uses **short-range + respectful** so each phantom works its
  own pocket and two don't pile on one mob (was long-range, which funneled the whole group together).

**Done (increment 6 — fpc-editor phantom support):**
- `tools/fpc-editor/index.html` now has an **NPC / Phantom mode switch** (two buttons atop the side
  panel). NPC mode edits `FakePlayerBehavior.xml` (unchanged); Phantom mode edits
  `PhantomPopulations.xml`.
- Switching **hides the inactive set** and shows only the active one (phantoms drawn teal). **Map,
  geodata and landmarks are shared** across modes for easy placement. The NPC-only form fields
  (profile/race/store) are hidden in Phantom mode; the respawn label re-labels to "replace on death".
- Each mode loads/saves its **own file** (separate handles); `+ Population`, double-click-add, drag,
  polygon zones, show/hide all, and direct-write/download all work per-mode. Phantom save defaults to
  `respawn="true"` (omits it) and only writes `respawn="false"` when unticked.

**Done (increment 7 — randomized identities):**
- **Names** reuse `FakePlayerAppearanceFactory.generateName()` (pronounceable syllable pools), checked
  unique against the DB.
- **Race / body / gender / look:** each phantom rolls a random melee **fighter class across races**
  (Human/Elf/Dark Elf/Orc/Dwarf — verified class ids 0/18/31/44/53, humans weighted common; orcs/dwarves
  skew male) plus random face/hair/hair-colour. All are melee so the sword + soulshot combat path is
  unchanged; falls back to Human Fighter if a template is missing.
- **Gear variety:** `buildGear` now keeps the cheapest few candidates per slot/grade (not just one) and
  `pickForSlot` rolls one at random; armor **completeness varies** (chest almost always; helmet/gloves
  often skipped) so a group is not a row of identical clones. Applies to respawns too.

**Done (increment 8 — proximity dormancy + cap):**
- **Proximity dormancy:** the supervisor pauses a phantom's auto-hunt (`stopAutoPlay`/`stopAutoUseTask`
  + park via `Intention.IDLE`) when no genuine client-connected player is within `SLEEP_RANGE` (4500),
  and resumes (`startAutoPlay`/`startAutoUseTask`) within `WAKE_RANGE` (3500). Hysteresis stops flapping.
  Observers are `World.getPlayers()` filtered by `!isInOfflineMode()` (excludes phantoms/offline shops).
  For solo play this means only the handful of phantoms around you actually compute the 700ms loop.
- **Hard cap:** `MAX_PHANTOMS` (200) safety ceiling enforced in `createAndSpawn` (covers deploy, respawn
  and admin spawns) — caps total live `Player` objects (memory), independent of dormancy (CPU).

**Performance context:** phantoms are full `Player` objects (700ms auto-hunt with geodata LOS/path
checks, per-Player tasks, combat), far heavier than NPC fake players. Dormancy cuts the CPU of distant
phantoms to ~nil; the cap bounds memory. A large world-filling population is now viable for solo play
because only nearby phantoms are live.

**Done (increment 9 — class transfer + full skill tree):**
- Phantoms now **advance to the class their level warrants** instead of staying a bare base Fighter:
  `transferClass` walks the `PlayerClass` tree (data-driven via `getNextClasses()`), picking a random
  **non-mage** branch at each transfer — 1st occupation at 20+, 2nd at 40+, 3rd at 76+ — then
  `setPlayerClass`+`setBaseClass` (same as a village master).
- `learnAllSkills` loops `giveAvailableSkills(false, true, true)` until nothing new resolves, so the
  phantom learns its **whole class tree up to its level** (chained skills included). `registerAutoSkills`
  then wires the new buffs/attacks into AutoUse, so e.g. a level-40 phantom is a real 2nd-class fighter
  (Gladiator/Warlord/etc.) using its kit — not a 3-skill base Fighter.
- All branches kept **melee** so the shared sword + soulshot path is unchanged.

**Done (increment 10 — mage DDs + resting):**
- **Mage DDs:** `MAGE_CHANCE` (~30%) of phantoms roll a mystic base class (Human/Elf/Dark Elf nuker
  lines; orcs/dwarves excluded). `transferClass` follows the DD branch (`isOfType(MYSTIC) && !isSummoner`,
  so healers/summoners are skipped). Gear is a second loadout: **magic weapon + MAGIC robe + spiritshots**
  (`MAGE_GEAR` vs `FIGHTER_GEAR`, built in `buildGear`). Mages get **no** auto-attack action, so AutoPlay
  treats them as casters and AutoUse fires their nukes (the registered offensive skills) on the target.
- **Resting (HP/MP sustain):** the supervisor sits a phantom to regenerate when it's safe (no monster
  within `REST_DANGER_RANGE`, not in combat) and below `REST_SIT_PERCENT` HP **or** MP, and stands it back
  up at `REST_STAND_PERCENT` or when threatened. This is what keeps mages going (MP) and wounded fighters
  alive (HP) — Interlude-authentic (sitting boosts regen; no MP potions needed). Resting pauses the
  auto-hunt while seated; dormancy/wake clears the rest state.

**Answer to "do phantoms rest?":** they do **now** (this increment). Before it they only regenerated
passively while standing and never sat or drank potions; the native AutoPlay/AutoUse has no rest, and its
auto-potion path is HP-only.

**Healing potions (in-combat HP sustain):** each phantom is given a large stack of Greater Healing
Potion (id 1539, ×20000, refreshed every spawn) with native **auto-potion** set to drink below 60% HP
(`setAutoPotionItem` + `setAutoPotionPercent`; requires `EnableAutoPotion=True`, the default). Potions
cover HP *during* fights (sitting can't); resting still covers MP (mages) and HP when safe. If a stack
ever runs dry the engine clears the auto-potion item until respawn — bump `HP_POTION_COUNT` if needed.

**Fixes (post-test):**
- **They didn't move / didn't pick up:** short-range hunting (set during anti-clump tuning) left phantoms
  idle unless a mob aggroed them — AutoPlay doesn't roam beyond its search radius, so they never walked to
  mobs or to dropped adena. Reverted to **long-range** search; clumping is held down by spaced spawns +
  respectful hunting. Pickup works again once they move (AutoPlay walks to items within ~200).
- **Mages now play as proper ranged casters (not melee).** Reverted the auto-attack fallback; mages are
  pure casters again, and a dedicated **mage combat tick** (1s) does the moving AutoPlay won't do for a
  caster: it holds the mage at `MAGE_CAST_RANGE` (~650) to nuke (AutoUse casts), and when MP drops below
  `MAGE_CAST_MP_PERCENT` (20%) it backs off to `MAGE_KITE_RANGE` (~850) and waits for MP to recover, then
  closes back in — classic kite. An engaged mage won't sit-rest (it kites instead). (`data.mage` flag
  drives it.) Tunables: the four `MAGE_*` constants.
  - Note: the earlier "frozen" character was a **melee** that didn't even react to hits — treated as a
    one-off glitch, not addressed.

**Fixes (post-test 2 — "only one of a deployed group moves"):** symptom: deploy ~10 into a zone (e.g.
Ruins of Agony), one melee hunts/spoils normally, the rest stand still; one is in combat stance taking
hits but only drinking potions, never attacking. Root causes (not a spawn-coordinate bug — the deploy
path already Z-snaps and validates reachability from the population center):
- **Idle phantoms never roamed.** Native AutoPlay never moves a phantom beyond its search radius — with no
  mob in range it does *nothing*. In a spread zone the first mover claims the local mobs (respectful
  hunting makes the rest skip them), so everyone else finds nothing reachable and **freezes forever**. Fix:
  the supervisor now **roams** an awake, idle (no target, not in combat, no mob within `REST_DANGER_RANGE`)
  phantom to a fresh reachable spot inside its home area (`ROAM_MIN_DISTANCE`..population radius, or
  `ROAM_RADIUS` for ad-hoc), so the next AutoPlay scan picks up new monsters. This is what keeps a group
  active instead of stalling where the local mobs ran out.
- **Out-of-MP mages were the "combat stance, not attacking, just potions" case.** A pure caster has no
  auto-attack action, so when it runs dry it can neither nuke nor melee and just stands drinking HP
  potions (auto-potion is its own AutoUse task, hence the "drinking but not fighting" look). Fix:
  `positionMage` now **melees the target** with its weapon while MP is below `MAGE_CAST_MP_PERCENT`, then
  resumes casting once MP recovers. Removed the kite-while-OOM behavior (and `MAGE_KITE_RANGE`).
- **Defensive:** `createAndSpawn` now Z-snaps the spawn location for *all* paths (the admin `//phantom
  spawn` path previously kept the caller's raw Z; idempotent for the already-snapped deploy path).

**Fixes (post-test 3 — THE real "they won't move, only dwarves do" bug):** every phantom cast skills and
mages even attacked, but **none moved except Dwarves**, in every test. Root cause: **overweight
immobilization.** Each phantom is handed 20000 Greater Healing Potions (weight 5 each = 100,000) plus
5000 soulshots. That blows past a non-Dwarf's max load; at >=100% load the engine applies **Weight
Penalty (skill 4270) level 4**, whose `runSpd` multiplier is **0** — total immobilization. Skills and
attacks don't need movement, so they still worked (and masked it). **Dwarves have a far larger carry
capacity**, so they stayed under the cap and moved — hence "only dwarves work". This also masked the
post-test-2 roam/mage-position fixes (they issue move intentions, but speed 0 = no actual movement).
Fix: `createAndSpawn` now calls **`phantom.setDietMode(true)`** (engine's "ignore weight penalty") before
gearing, plus a `refreshOverloaded()` after — so load never pins a phantom regardless of race.

**Buffers / healers — when:** they only make sense **with parties** (their job is buffing/healing allies),
so they arrive **with the parties increment** — standalone they'd have nothing to do.

**Deliberately NOT done yet (next increments):** **parties** (then buffers/healers within them — engine
already has auto-assist + offline-play party restore), skip phantom **auto-save**, runtime shot restock,
hunting-zone relocate-when-empty, PvP target mode, persistence across restarts, DB cleanup of orphan
phantom rows, config knobs.

**Caveats / to verify in-game (untestable in this dev env — needs ant rebuild + JDK 25):**
- Requires `EnableAutoPlay = True` (above) and **geodata loaded** for pathfinding to a target.
- `clear()` despawns via `Player.deleteMe()` but does **not** delete the DB row, so repeated
  spawn/clear cycles leave orphan `phantom`-account characters. Add DB cleanup later.
- A level-1 fighter with no weapon melees with fists and is weak — expected until the gearing/leveling
  increment. Verify it actually finds, walks to, and hits a low-level mob.
- Watch for any **unguarded** `getClient().x()` outside the AI package during combat/skill effects;
  add null-checks reactively (offline-play characters suggest the common paths are already safe).

---

## 8. Suggested next steps

1. **Vendors permanent / market polish** (mostly done — verify after rebuild).
2. **Functional shops** (Phase 2): give vendors real sell/buy item lists and handle purchases.
3. **Field behavior tuning**: smarter hunting, polygon-bounded roaming, persistent respawn identity.
4. **Editor niceties**: 2-click map calibration; edit profiles/assigns in-tool; live in-game reload.
5. **In-game coordinate capture** (admin command) to mark anchors/zones from your character position.

---

## 9. File map (quick reference)

| Area | Path |
|---|---|
| Chat brain (Python) | `fpc_brain.py` |
| Chat manager | `java/.../gameserver/managers/FakePlayerChatManager.java` |
| Chat handlers | `dist/game/data/scripts/handlers/chat/channels/Chat{Trade,General,Whisper}.java` |
| Behavior manager | `java/.../gameserver/managers/FakePlayerBehaviorManager.java` |
| Appearance factory | `java/.../gameserver/managers/FakePlayerAppearanceFactory.java` |
| Store content engine | `java/.../gameserver/managers/FakePlayerStoreFactory.java` |
| Store transactions | `java/.../gameserver/managers/FakePlayerStoreManager.java` |
| Store stock holder | `java/.../gameserver/model/actor/holders/npc/FakePlayerStoreItem.java` |
| Store packets | `java/.../gameserver/network/serverpackets/FakePlayerStoreList{Sell,Buy}.java` |
| Store wiring | `NpcClick.java` (open), `RequestPrivateStore{Buy,Sell}.java` (trade) |
| Appearance holder | `java/.../gameserver/model/actor/holders/npc/FakePlayerAppearance.java` |
| Render packet | `java/.../gameserver/network/serverpackets/FakePlayerInfo.java` |
| NPC integration | `java/.../gameserver/model/actor/Npc.java` |
| Config | `java/.../gameserver/config/custom/FakePlayersConfig.java`, `dist/game/config/Custom/FakePlayers.ini` |
| Behavior data | `dist/game/data/FakePlayerBehavior.xml` (+ `data/xsd/FakePlayerBehavior.xsd`) |
| Visual editor | `tools/fpc-editor/index.html` (+ `README.md`) |

Development branch: `claude/compassionate-dijkstra-mmfu13`.
