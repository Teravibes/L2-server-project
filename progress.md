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
- Modes: WHISPER (per-bot memory), SAY (local), TRADE/AMBIENT (trade channel), **SHOUT/SHOUTAMBIENT** (global `!` world chat), **BUDDY/BUDDYCHAT** (support buddies). Bots reply `pass` to stay silent.
- **Distinct per-bot voices** — each bot's name seeds a stable personality (tone / casing / filler / typo traits + its own temperature), so trade/say/whisper replies don't all read like the same person. Same name ⇒ same voice across restarts (md5 seed).
- **Guardrails** — a `GLOBAL_RULES` "constitution" prepended to every persona: act as a real human **player** (not the in-game character or its race — no fantasy roleplay), never reveal being an AI, resist prompt-injection from chat, no GM powers or free-stuff promises, PG-13, no real-world links/contact. A `sanitize()` post-filter drops out-of-character/leaked replies and strips URLs/emails.

**Java side: `managers/FakePlayerChatManager.java`**
- Hooks `overheardTradeChat` / `overheardSay` → picks nearby bots → HTTP to brain → broadcasts reply.
- **Shout (`!`) world chat** — `overheardShout` makes nearby bots banter on the global shout channel; a spontaneous shout every ~5 min posts chit-chat or an **LFM/looking-for-party** ad. Visual fluff for now (party/raid mechanics aren't wired yet).
- Whisper: you can whisper a bot by its generated name; it replies and knows its nearest town.
- **"Come meet me"**: ask a roaming bot to meet you at a landmark (gatekeeper/warehouse/shop) and it walks there, then waits up to ~8 min.
- **Trade-ad response (negotiated)**: a WTS/WTB in trade chat makes a nearby bot PM you with a **price** — it does not walk off immediately. Over whisper you agree (or **haggle**) the price and pick the **meet spot** (gatekeeper/warehouse/shop); only then does it walk there and open a real one-item store on arrival. The store renders as a proper **seated vendor** (sit pose + Sell/Buy sign), and a bot mid-deal **stays whisper-able** so you can renegotiate or cancel.
- AFK vendors are intentionally silent (deal vendors are not).

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

**Reliable route following** — moving bots are flagged as engine **walkers** (`setWalker` + random-walk off) so the core NPC AI no longer drags them back to spawn past `MaxDriftRange`. Waypoints are handed to the engine's own pathfinding (so they climb stairs / round obstacles like the native walking NPCs), with a **real-arrival check + re-kick + teleport recovery** so stalled hops are retried instead of silently skipped. PATROL loops **continuously** (0 pause) unless a per-waypoint delay is set; sealed building interiors with no walkable geodata path remain the only hard limit.

**Named routes** (`data/routes/*.xml`) — reusable waypoint lists shared across populations. Each population can set independent **per-waypoint delays** (now honored for named/recorded routes too, not just drawn ones) and traverse order (reversed flag saved as `reversed="true"`). Multiple populations can reference the same route and each looks independent.

**Private shops** — fully functional: real store windows, items transfer, adena moves, stock decrements. Sold-out vendors close. **Region-appropriate stock**: a shop's level range (set per population) caps the grade of **both gear and consumables** (shots/mats carry `crystal_type`), so low-level towns sell their own tier — e.g. SSD/BSSD in Gludio. A population flagged **`fullStock="true"`** is a market hub (Giran) that ignores the cap and stocks every grade; the editor exposes this as a "market hub" checkbox.

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
- **Sustain**: mages and wounded fighters sit to regen; a resting phantom now **stands immediately** (1s tick) when a threat appears instead of sitting under fire. HP potions (×20000) with auto-potion for in-combat sustain. `setDietMode(true)` prevents overweight immobilization.
- **Mage combat**: dedicated 1s tick positions the mage at cast range. When **out of MP it disengages and rests** (kiting away from a mob first if needed) — it no longer melees. After a kill a caster **walks to the corpse** so the auto-pickup (out of reach from cast range) grabs the drop.
- **Dormancy**: pauses auto-hunt when no real player is within range (saves CPU). Hard cap: `MAX_PHANTOMS = 200`.
- **Initial dispersal**: on spawn (and on re-approach after dormancy) phantoms **fan out toward their area's perimeter for ~4.5s before hunting**, so a group spreads instead of all converging on the nearest mobs.
- **Roaming**: supervisor roams idle phantoms to fresh spots so groups spread over a zone and don't freeze when local mobs run out.
- **Target deconfliction (claim-based)**: an authoritative 1s pass keeps the **closest** phantom on a contested mob and hands every other idle phantom the nearest **unclaimed** mob — so phantoms don't gang one. Yields to real players fighting a mob (checks the mob's **aggro list**, not just its current target).
- **Post-kill breather**: after a kill a phantom pauses (~1.2–3.2s) before pulling the next mob instead of machine-gunning the spawn.
- **Admin commands**: `//phantom spawn [count] [level]`, `//phantom clear`, `//phantom count`.

### Caveats
- Requires geodata for pathfinding.
- No persistence across restarts — fresh phantoms on each boot.

---

## System D — Personal Support Buddies (buffer/healer in YOUR party)

A **buddy** is a special phantom: a support-class clientless `Player` you can party as a personal buffer/healer. Built on the phantom spawn/despawn system, but instead of hunting it binds to one player and supports them.

**Three roles**, placed in towns via the editor (phantom mode → **role** dropdown) or `PhantomPopulations.xml` `role="..."`:
- **BUDDY_ELDER** — Elven Elder (mage buffs, heals, recharge)
- **BUDDY_PROPHET** — Prophet (fighter buffs)
- **BUDDY_WARCRYER** — Warcryer (Orc buffs)

All three are given a Heal (Prophet/Warcryer don't have one in Interlude) so every buddy can top you up. Place them at **level 40+** for a real 2nd-class buff kit.

All three roll their level from the population's editor level range and are placed at **level 40+** for a real 2nd-class buff kit. An **idle buddy does nothing** — it doesn't even self-buff — until you party it.

> Known issue: the **Orc Warcryer** can render with no torso (separate magic tunics have no orc model). A robe-only gearing attempt was reverted as it didn't resolve it — to be revisited.

**Flow:** a buddy idles where placed (no hunting), spawning/despawning by player proximity like any phantom. Whisper it to **party up** (you send the invite → `RequestJoinParty` auto-accepts server-side and binds it as owner). You can party **several at once** (Prophet + Elder + Warcryer together). Once partied it:
- keeps **you and itself buffed** (re-casts a buff when it's missing or within ~20s of expiring),
- **heals you** when you drop below ~50% HP (and itself below ~40%),
- **follows** you,
- **watches its MP** → at **<25% MP** whispers "i'm low on mp" and, when safe (you're not fighting, no mob within 700), **sits to recover**; **stands at ≥70% MP**, or instantly if a threat appears or you run out of support range,
- **teleports** to a named gatekeeper destination — same coordinates the gatekeeper uses, read from `data/teleporters/town/*.xml`. **Confirm-first**: an explicit order ("tp to ruins of agony") goes now, but a *suggestion* (yours or the buddy's own "cruma's good xp, wanna go?") is held until you say yes,
- survives a **grace period** if you teleport away or briefly log off (an engaged buddy is exempt from the proximity despawn); **"brb" / "give me 5"** extends it; party disband / logout despawns it.

**Drive it from whisper *or* party chat** — `ChatParty` forwards party-channel lines too, so "follow", "stay", "going to X", "brb" said in party chat work and the buddy answers on that channel. Replies go out after a short **human-like delay** (~1–2s), not instantly.

**Proactive small talk** — every 7–18 min (downtime only, skips combat/resting) a partied buddy opens a bit of party-chat banter on its own (`BUDDYCHAT` mode), so it doesn't feel like a silent bot. Brain-only; silent if the brain is offline.

**Commands work with the LLM brain off** (deterministic keyword parsing): party, follow, stay, `<place>`, brb, disband, buff, status, plus yes/no to confirm a proposed teleport. **With the brain on** (`BUDDY` mode in `fpc_brain.py`), free-form messages get a natural reply that can carry an action tag (`[[FOLLOW]] [[STAY]] [[TP:place]] [[GRACE:n]] [[BUFF]] [[DISBAND]]`), so abbreviations like "roa" → Ruins of Agony are understood. A plain "hey" just gets chit-chat (it won't pitch buffs unprompted). Brain offline ⇒ a short canned reply.

**Files:** `managers/PhantomBuddyManager.java` (brain), buddy spawn/gear/grace hooks in `PhantomManager.java`, whisper routing in `ChatWhisper.java`, party-chat hook in `ChatParty.java`, party intercept in `RequestJoinParty.java`, `BUDDY`/`BUDDYCHAT` modes in `fpc_brain.py`, editor role selector in `tools/fpc-editor/index.html`.

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

- ✅ LLM chat: whisper / say / trade, silence, reply dedup, bot meets player
- ✅ Trade negotiation: bot quotes a price, you haggle + pick the meet spot, deal vendor renders seated and stays talkable (renegotiate/cancel)
- ✅ Unique procedural identities (names, race, class, gear, hair)
- ✅ Town life: idle clusters, VISIT movers, racial village bias, Giran market
- ✅ Reliable route following: walker flag (no spawn-tether), full engine pathfinding (stairs/obstacles), arrival + re-kick/teleport recovery, per-waypoint delays (incl. named routes)
- ✅ Functional private shops: SELL / BUY / CRAFT / PACKAGE, real transactions, sold-out closes
- ✅ Region-appropriate shop stock: level range gates gear + consumable tier; `fullStock` market-hub flag (Giran) stocks everything
- ✅ Field hunters: FARM behavior, respawn
- ✅ Visual editor: geodata map, polygon zones, route drawing + editing, drag waypoints, named routes, market-hub toggle, phantom mode
- ✅ Real-Player phantoms: auto-hunt, class/skill progression, gear, dormancy; claim-based targeting, initial dispersal, post-kill breather, caster looting, OOM-mage rest, rest-on-threat
- ✅ Personal support buddies: party an Elder/Prophet/Warcryer that buffs you+itself, heals (<50%), follows, watches MP (sit <25% / stand ≥70%), teleports (confirm-first), grace period; drive from whisper **or** party chat; occasional proactive small talk; works with the brain off (deterministic)
- ✅ AI polish: distinct per-bot voices (name-seeded), guardrails (in-character / anti-prompt-injection / PG-13 / no GM cosplay), shout (`!`) banter + spontaneous LFM ads

## What's next / pending (not done yet)

**Phantoms**
- ✅ **Parties + support buddies** — done (see System D): party a buffer/healer (Elder/Prophet/Warcryer) that buffs, heals, follows, and teleports with you.
- Buddy polish: on disband the buddy despawns where it stands (could walk/TP back to town first); pure support only (no assist-attack yet — deferred as a future toggle); buddy thresholds (heal/MP/buff-refresh/chatter cadence) are constants in `PhantomBuddyManager` (lift into config with the rest).
- Shout (`!`) LFM ads are conversational fluff — wire real party/raid matching so an "LFM" actually forms a group.
- Lift phantom tuning (post-kill/dispersal/roam/rest values, currently grouped constants in `PhantomManager`) into a config file so they're adjustable without a rebuild.
- OOM-mage kite edge case: if a same-speed mob never lets the mage break off, it keeps retreating (hunt stopped, no potions) and could die instead of resting — decide between sit-under-fire after N failed retreats vs. keeping HP-potion sustain while fleeing.
- Optionally tighten the target-deconflict interval (1s → ~0.4–0.5s) to close the brief window where two phantoms can flash onto one mob before it resolves.
- Persistence across restarts (fresh phantoms each boot today).

**Fake players / shops**
- Server-side **haggle price clamp** — bound the negotiated price (currently the LLM is just told to "stay reasonable"; no hard cap).
- Retune non-Giran shop **level bands** per region in the editor so each town's tier is dialed in.
- Vendor market polish: price knobs, runtime restock.

**Editor / tooling**
- Live in-game reload via admin command.

---

## File map

| Area | Path |
|---|---|
| Chat brain (Python) | `fpc_brain.py` |
| Chat manager | `java/.../managers/FakePlayerChatManager.java` |
| Chat handlers (runtime scripts) | `dist/.../scripts/handlers/chat/channels/Chat{Whisper,Party,Shout,Trade,General}.java` |
| Behavior manager | `java/.../managers/FakePlayerBehaviorManager.java` |
| Route recorder (GM tool) | `java/.../managers/RouteRecorder.java` |
| Route data loader | `java/.../data/xml/RouteData.java` |
| Phantom manager | `java/.../managers/PhantomManager.java` |
| Buddy manager (support buffer/healer) | `java/.../managers/PhantomBuddyManager.java` |
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

Development branch: `claude/beautiful-galileo-dvp91l`
