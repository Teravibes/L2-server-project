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
