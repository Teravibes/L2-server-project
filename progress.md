# Fake Player "Living World" — Project Progress

> Read this first when picking up the project. Covers what's built, how it fits together, how to deploy, and what's next.

---

## What this is

A fork of **L2J Mobius CT_0 Interlude** for offline/solo play. The goal: log in and the server feels like a populated live server — towns full of people, shops, trade chat, field hunters — without other humans online.

Two systems run on top of Mobius's bare-bones fake-player feature:

1. **LLM chat** — fake players hold real conversations via an external AI model.
2. **Living-world behavior** — data-driven NPC fake players and real-Player phantoms filling towns and hunting zones, edited from a visual tool.

---

## System A — LLM Chat Brain

**`fpc_brain.py`** (repo root) — Flask sidecar the Java server calls over HTTP.
- Talks to DeepSeek or local Ollama; provider set by `PROVIDER` env var.
- `POST /chat` endpoint; Java sends message + headers (`X-FPC`, `X-Mode`, `X-Player`, `X-Speaker`).
- Modes: WHISPER (per-bot memory), SAY (local), TRADE/AMBIENT (trade channel). Bots reply `pass` to stay silent.

**Java side: `managers/FakePlayerChatManager.java`**
- Hooks `overheardTradeChat` / `overheardSay` → picks nearby bots → HTTP to brain → broadcasts reply.
- Whisper: you can whisper a bot by its generated name; it replies and knows its nearest town.
- **"Come meet me"**: ask a roaming bot to meet you at a landmark (gatekeeper/warehouse/shop) and it walks there, then waits up to ~8 min.
- **Trade-ad response**: a WTS/WTB in trade chat triggers a nearby bot to walk to the gatekeeper, open a real one-item store, and PM the player.
- AFK vendors are intentionally silent.

Run: `python fpc_brain.py` (needs API key env). Listens on `127.0.0.1:5000`. Java falls back to canned replies if offline.

---

## System B — NPC Fake Players

### Architecture

**Per-instance appearance** — Mobius FPCs are NPCs. Normally one template = one fixed look. We override this:
- `FakePlayerAppearance.java` — per-bot identity (name, race, gender, class, hair, gear, store type).
- `Npc.java` — carries optional appearance; `getName()` honors it; `sendInfo()` sends store-title packet.
- `FakePlayerInfo.java` — renders NPC as player, reads from appearance when present.

**`FakePlayerAppearanceFactory.java`** — generates unique pronounceable names, coherent race/gender/class combos, level-appropriate gear (C-grade cap, mixed wealth), store titles.

**`FakePlayerBehaviorManager.java`** — loads `data/FakePlayerBehavior.xml`, deploys bots ~15s after boot, ticks every 3s.

Behaviors:
- **IDLE/WANDER** — drift within radius or drawn polygon zone.
- **VISIT/RUNNER** — roam waypoints randomly.
- **PATROL** — walk waypoints in order, loop.
- **FARM** — seek and fight monsters in zone; `respawn="true"` replaces on death.
- **SHOP** — seated, immobilized SELL/BUY/CRAFT/PACKAGE vendors.

**Named routes** (`data/routes/*.xml`) — reusable waypoint lists shared across populations. Each population can set independent per-waypoint delays and traverse order (reversed flag saved as `reversed="true"` on the population element). Multiple populations can reference the same route and each looks independent on the map and in the server.

**Private shops** — fully functional: real store windows, items transfer, adena moves, stock decrements. Sold-out vendors close. Stock is procedurally generated from the datapack item table with realistic pricing.

### The Editor — `tools/fpc-editor/index.html`

Single HTML file, no install. Edits both `FakePlayerBehavior.xml` (NPC mode) and `PhantomPopulations.xml` (Phantom mode).

See `tools/fpc-editor/README.md` for full usage.

---

## System C — Real-Player Phantoms

Clientless `Player` objects (same pattern as Mobius offline traders) that farm via the engine's native **AutoPlay + AutoUse** system. Intended for field zones where NPC FPCs can't do real combat.

**Key config required:** `EnableAutoPlay = True` in `config/Custom/AutoPlay.ini`.

### What's built

- **Deployment**: loads `data/PhantomPopulations.xml` on boot; zones activate **on demand** (when a real player walks within range) and despawn ~30s after the last player leaves. No boot-time mass-spawn.
- **Identities**: random melee or mage (~30%) fighter class across races, pronounceable names, varied gear (grade matches level, random completeness so groups aren't clones).
- **Class progression**: advances to the class their level warrants (1st at 20+, 2nd at 40+, 3rd at 76+), learns the full skill tree, registers buffs/attacks into AutoUse.
- **Gear**: sword + soulshots for fighters; magic weapon + robe + spiritshots for mages. Data-driven — no hard-coded item IDs.
- **Sustain**: mages and wounded fighters sit to regen. HP potions (×20000) with auto-potion below 60% for in-combat sustain. `setDietMode(true)` prevents overweight immobilization.
- **Mage combat**: dedicated 1s tick positions the mage at cast range, melees if OOM, resumes casting when MP recovers.
- **Dormancy**: pauses auto-hunt when no real player is within range (saves CPU). Hard cap: `MAX_PHANTOMS = 200`.
- **Roaming**: supervisor roams idle phantoms to fresh spots so groups spread over a zone and don't freeze when local mobs run out.
- **Target deconfliction**: yields to real players fighting the same mob; farther phantom yields when two phantoms share a target.
- **Admin commands**: `//phantom spawn [count] [level]`, `//phantom clear`, `//phantom count`.

### Caveats
- Requires geodata for pathfinding.
- No party system yet (buffers/healers deferred until parties are built).
- No persistence across restarts — fresh phantoms on each boot.

---

## How to build & deploy

| What changed | How to deploy |
|---|---|
| Anything under `java/` | `ant` build → copy `GameServer.jar` to live server |
| Scripts under `dist/game/data/scripts/` | Copy files to live datapack (runtime-compiled) |
| `FakePlayerBehavior.xml`, `routes/*.xml`, config files | Copy to live `game/data/` |
| `FakePlayers.ini` | Copy to live `game/config/Custom/` |
| Editor `index.html` | Just open in browser — no build |
| `fpc_brain.py` | Restart the Python process |

Build environment: JDK 25 + Ant. Build cannot run in this dev environment (Java 21, no Ant) — Java changes are hand-verified.

---

## What works

- ✅ LLM chat: whisper / say / trade, silence, reply dedup, bot meets player, trade-ad response
- ✅ Unique procedural identities (names, race, class, gear, hair)
- ✅ Town life: idle clusters, VISIT movers, racial village bias, Giran market
- ✅ Functional private shops: SELL / BUY / CRAFT / PACKAGE, real transactions, sold-out closes
- ✅ Field hunters: FARM behavior, respawn
- ✅ Named routes: shared across populations, per-population delays + reversal
- ✅ Visual editor: geodata map, polygon zones, route drawing + editing, drag waypoints, named routes, phantom mode
- ✅ Real-Player phantoms: auto-hunt, class/skill progression, gear, mage combat, dormancy, deconfliction

## What's next

- Phantom parties (then buffers/healers make sense)
- Vendor market polish (price config knobs, runtime restock)
- Editor: live in-game reload via admin command

---

## File map

| Area | Path |
|---|---|
| Chat brain (Python) | `fpc_brain.py` |
| Chat manager | `java/.../managers/FakePlayerChatManager.java` |
| Behavior manager | `java/.../managers/FakePlayerBehaviorManager.java` |
| Route recorder (GM tool) | `java/.../managers/RouteRecorder.java` |
| Route data loader | `java/.../data/xml/RouteData.java` |
| Phantom manager | `java/.../managers/PhantomManager.java` |
| Appearance factory | `java/.../managers/FakePlayerAppearanceFactory.java` |
| Store factory / manager | `java/.../managers/FakePlayerStoreFactory.java`, `FakePlayerStoreManager.java` |
| Appearance holder | `java/.../model/actor/holders/npc/FakePlayerAppearance.java` |
| Render packet | `java/.../network/serverpackets/FakePlayerInfo.java` |
| NPC integration | `java/.../model/actor/Npc.java` |
| Config | `java/.../config/custom/FakePlayersConfig.java`, `dist/.../FakePlayers.ini` |
| NPC behavior data | `dist/game/data/FakePlayerBehavior.xml` |
| Named routes | `dist/game/data/routes/*.xml` |
| Phantom data | `dist/game/data/PhantomPopulations.xml` |
| Admin route commands | `dist/.../scripts/handlers/chat/commands/admin/AdminFpcRoute.java` |
| Visual editor | `tools/fpc-editor/index.html` + `README.md` |

Development branch: `claude/zen-albattani-qyp6w4`
