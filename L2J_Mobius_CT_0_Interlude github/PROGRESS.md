# Fake Player "Living World" — Project Progress & Handoff

> Read this first when picking up the project. Covers what's built, how it fits together, how to deploy, and what's next.

---

## 1. What this is

A fork of **L2J Mobius CT_0 Interlude** for offline/solo play. The goal: log in and the server feels like a populated live server — towns full of people, shops, trade chat, field hunters — without other humans online.

Four systems run on top of Mobius's bare-bones fake-player feature:

1. **LLM chat** — fake players hold real conversations via an external AI model.
2. **NPC fake players** — data-driven NPCs filling towns and hunting zones, edited from a visual tool.
3. **Real-Player phantoms** — clientless `Player` objects that auto-hunt in field zones.
4. **Personal support buddies** — a phantom you party as your own buffer/healer.
5. **Recruited combat parties** — shout an LFM/LFP and a full party of level-matched phantoms (tank / DD / archer / dagger / nuker / healer / buffer) walks over and joins you.

---

---

## 1b. Latest Progress Update — AI Trade, Memory & Meetup Polish (2026-06-29)

This section records the newest changes made after the previous Living World / recruited-party work. These updates focus on fake-player trade realism, persistent AI memory, and meetup/store behavior.

### Completed / working

#### Player-global persistent memory in `fpc_brain.py`

The Python brain now supports lightweight persistent memory stored on disk:

```text
memory/fpc_memory.json
```

The memory is intentionally **player-global**, not `player|botName`, because generated fake-player identities are currently random across server restarts. If memory were tied to generated bot names, many memories would become orphaned after restart.

Current memory captures rule-based facts such as:

```text
Player has looked for D-grade shots in bulk.
Player often uses gatekeeper as a meeting point.
Player has been friendly/appreciative.
Player sometimes goes AFK during party play.
Player usually completes trade negotiations normally.
Player sometimes haggles trade prices.
```

This allows later bots to answer more naturally, for example:

```text
got more ssd if u need, same spot?
np, gk again?
```

Known limitation: memory currently uses deterministic/rule-based extraction. It does not yet use a dedicated LLM summarizer, and it cannot know a trade truly completed unless Java later sends an explicit completion event.

---

#### Trade quantity support for WTB/WTS

Trade ads now support explicit stack quantities.

Examples:

```text
+WTB ssd 5k
```

Means the player wants to buy 5000 Soulshot D-grade, so the bot should open a **SELL** shop with 5000 SSD.

```text
+WTS ssd 5k
```

Means the player wants to sell 5000 Soulshot D-grade, so the bot should open a **BUY** shop for 5000 SSD.

If no quantity is provided:

```text
+WTB ssd
```

the old fallback/random bulk sizing is preserved.

Implemented through:

- `FakePlayerChatManager.java`
  - `TRADE_QUANTITY`
  - `parseTradeQuantity(...)`
  - quantity-aware stock setup
- `FakePlayerStoreFactory.java`
  - `dealSellStock(int itemId, int unitPrice, int requestedCount)`
  - `dealBuyStock(int itemId, int unitPrice, int requestedCount)`
  - `normalizedDealCount(...)`
- `FakePlayerBehaviorManager.java`
  - `getPendingDealCount(...)` so haggled `[[SHOP:...]]` tags preserve the requested count.

Status: **Working.**

---

#### Structured trade context, Phase 2A

Java now sends explicit structured deal context to the Python brain during the initial trade offer and later follow-up whispers.

Headers:

```text
X-Deal-Side: SELL / BUY
X-Deal-Item: <item name>
X-Deal-Count: <count>
X-Deal-Unit-Price: <unit price>
X-Deal-Total-Price: <total price>
```

Purpose:

- Prevents the LLM from guessing item/count/price from free text.
- Makes haggling and follow-up whispers more reliable.
- Keeps exact deal terms available after the first PM.

Implemented concept:

- `BrainDealContext` added in `FakePlayerChatManager.java`.
- `ACTIVE_DEALS` map keyed by `playerName|botName`.
- Context is stored when a WTB/WTS responder PM is created.
- Context is sent to `fpc_brain.py` through HTTP headers.
- `deal_note_from_headers()` added to `fpc_brain.py`.
- `deal_note` injected into `OFFER` and `WHISPER` prompts.

Important Python requirement inside `chat()`:

```python
message = request.get_data(as_text=True)
voice, temperature = _voice(fpc)
deal_note = deal_note_from_headers()
reply = ""
try:
```

If this assignment is missing, Python raises:

```text
deal_note is not defined
```

Status: **Implemented; needs continued gameplay verification.**

---

#### Meetup destination offset beside landmark NPCs

Bots no longer walk to the exact coordinates of the gatekeeper, warehouse keeper, or merchant.

Old behavior:

```text
bot target = gatekeeper exact X/Y/Z
```

Problem: the fake player model could stand inside the gatekeeper model.

New behavior:

```text
bot target = nearby valid X/Y/Z around the landmark
```

Implemented in `FakePlayerBehaviorManager.java`:

- `nearestNpcLocation(...)` now returns a nearby offset location.
- Added:
  - `MEET_OFFSET_MIN = 160`
  - `MEET_OFFSET_MAX = 260`
- Normal result: bot stands roughly **160-260 game units** beside the landmark.
- Geodata-adjusted fallback accepts at least about **80 units** away.

Compile fix already applied:

- `Location` does not have `calculateDistance2D(Npc)`.
- Manual dx/dy distance calculation is used instead.

Status: **Working.**

---

#### Separate WTB/WTS offer priority from public social spam

Initial WTB/WTS responder PMs should no longer be blocked by ambient shout/trade/say chatter.

Old behavior:

- Public ambient/shout/trade/say messages and WTB/WTS responder PMs shared:

```java
MAX_MESSAGES_PER_MINUTE = 8
```

This meant ambient bot chatter could consume the cap and prevent a player’s WTB/WTS from receiving a PM.

New intended behavior:

- Public/social chatter still uses:

```java
MAX_MESSAGES_PER_MINUTE = 8
```

- Important trade-offer PMs use their own cap:

```java
MAX_TRADE_OFFERS_PER_MINUTE = 20
TRADE_OFFERS_THIS_MINUTE
```

- Both counters reset every minute.
- Initial WTB/WTS PMs increment `TRADE_OFFERS_THIS_MINUTE`, not `MESSAGES_THIS_MINUTE`.

Status: **Recommended / pending full verification after rebuild.**

---

#### Higher probability WTB/WTS responder selection

Responder search was widened so a WTB/WTS ad is more likely to find a roaming fake player.

Implemented concept in `FakePlayerBehaviorManager.java`:

```java
TRADE_RESPONDER_SEARCH_RANGE = 12000
```

`pickTradeResponder(...)` should use this wider range instead of `SUMMON_SEARCH_RANGE`.

Reasoning:

- Landmark search should stay local/town-safe.
- Trade responder search can be wider to increase chance of an available bot.

Status: **Recommended / pending full verification after rebuild.**

---

### Pending / next fixes

#### Harden malformed MEET tags from Ollama

Observed bad output:

```text
[[MEET:gk])
```

Problems:

- Malformed closing bracket.
- Uses `gk` shorthand inside the tag.
- Java does not strip it if the regex only accepts perfect tags.
- The tag can leak into visible player chat.
- In one example, the bot rejected the price but still produced a meet tag-like command.

Recommended Java hardening in `FakePlayerChatManager.java`:

```java
private static final Pattern MEET_TAG = Pattern.compile("\\[\\[\\s*MEET\\s*:\\s*([a-zA-Z]+)\\s*(?:\\]\\]|\\]|\\))", Pattern.CASE_INSENSITIVE);
```

This catches:

```text
[[MEET:gatekeeper]]
[[MEET:gk]]
[[MEET:gk])
[[MEET:gk]
```

Recommended Python prompt hardening in `whisper_persona(...)`:

- Use exact tag values only:
  - `[[MEET:gatekeeper]]`
  - `[[MEET:warehouse]]`
  - `[[MEET:shop]]`
- Never use shorthand like `gk` inside the tag.
- If rejecting the player's price or still negotiating, do not add a `MEET` tag.

Status: **Pending.**

---

#### Clear active deal context after the deal ends

Structured deal context is currently keyed by:

```text
playerName|botName
```

Recommended cleanup points:

- `[[MEET:cancel]]`
- temporary deal store closes / sold out
- meet hard timeout
- player abandons meetup
- bot leaves after nudge/grace timeout
- new trade ad overwrites old context

Status: **Partially pending.**

---

#### Future: LLM-driven memory summarizer

Current memory extraction is deterministic/rule-based.

Future improvement:

- Add `MEMORY` mode in `fpc_brain.py`.
- After important conversations, ask the LLM to summarize only durable facts.
- Store categorized memories:
  - trade
  - party
  - social
  - preferences

Example target output:

```text
Player prefers gatekeeper for trades.
Player often buys Soulshot D-grade in bulk.
Player haggles but usually accepts reasonable prices.
Player appreciates quick trades.
```

Status: **Future phase.**

---

#### Future: stable fake-player identities

Generated fake-player names/appearances are currently random across restart.

Future improvement:

- Add stable generated identity slots per population/town.
- Example:
  - `giran_market_001`
  - `giran_roamer_014`
- Persist:
  - name
  - appearance
  - personality seed
  - optional bot-specific memory

Then memory can be split into:

```text
player-global memory
bot-specific memory
```

Status: **Phases 1-3 done (2026-07-01), incl. promotion-on-befriend.** See §12b-§12f below. Phantom
populations can define fixed `<regular>` identities (stable name + appearance, optional class) that recur in
a zone; a stable name already yields a stable brain personality (`fpc_brain.py` `_voice()` hashes the name).
Regulars persist across reboots with a stable `charId` (Phase 2), can be befriended and privately chatted
with over the friends list at a human cadence (Phase 3a), and spawn at login / despawn at logout so they show
"online" while their owner is on, anywhere in the world (Phase 3b). **No XML authoring needed: friend-inviting
ANY phantom auto-promotes it into a persistent regular on the spot (§12f).** Only the brain "we're friends"
memory flag remains — see §10b / §12d / §12e / §12f.

---

## 1c. Latest Progress Update — Buff Kit Audit, Tag Hardening & Chat Realism (2026-07-01)

Work in this pass focused on (a) fixing the support buff kit that felt "thin" after the earlier re-buff-loop
fix, (b) closing the two open tag/deal-context rough edges, and (c) a first real step toward believable social
timing. All Java is slot-collision-safe and free of `Set.of` duplicates; the build still needs the Ant/JDK25
environment (hand-verified here).

### Buffer kit — proper data-driven audit (`PhantomBuffs.java`)

The curated auto-kit from the loop fix was correct in shape but had genuinely missing must-have buffs. Re-audited
**slot-by-slot against the actual skill data** (the rule that matters: one buff per `abnormalType` = one slot;
anything in its own slot is safe to auto-maintain and can't re-trigger the loop). Added, each in a distinct slot:

- **Bless Shield** (`1243`, `SHIELD_PROB_UP`) — was missing from the melee auto-kit *and* pre-buff. Standard
  Prophet buff, its own slot (distinct from Shield's `PD_UP`). Also added a `bless shield` / `blessed shield`
  request alias.
- **Shield** (`1040`, `PD_UP`) — was missing from the **caster** kit, so a mage main got no P.Def Shield. Casters
  take Shield for survivability; added to the maintained caster kit and caster pre-buff.
- **Regeneration** (`1044`, `HP_REGEN_UP`) → melee; **Mana Regeneration** (`1047`, `MP_REGEN_UP`) → caster.
- **Resist Shock** (`1259`, `RESIST_SHOCK`, 1200s, no reagent) → both archetypes (anti-stun).

`1044`/`1047`/`1259` only actually land if the buffer's class knows them (`wanted()` is gated by the buffer's own
skill list), so a pure Prophet won't try to cast an Elder-only regen.

**Deliberately still on-request** (not auto), because they are *not* slot-safe or are consumable:
- **Greater Might / Greater Shield / War Chant / Earth Chant** — all share the single `PA_PD_UP` slot in the data,
  so auto-maintaining two of them re-creates the flap/loop. Base Might (`PA_UP`) + base Shield (`PD_UP`) cover
  both P.Atk and P.Def in two stable slots instead.
- **Prophecy of Fire/Water/Wind** (`1355`–`1357`) — share one `MULTI_BUFF` slot, last only **300s**, and each eats
  5 Spirit Ore. Auto = flap + reagent drain. Cast on request (aliases exist).

### Malformed MEET tag leakage — fixed (`FakePlayerChatManager.java` + `fpc_brain.py`)

`MEET_TAG` now tolerates a malformed close (`[[MEET:gk])`, `[[MEET:gk]`, `[[MEET:gk)`): the closing accepts one or
two of `]` / `)` (`[\]\)]{1,2}`), so every Ollama variant is both acted on and stripped instead of leaking the raw
tag into visible chat. The whisper prompt in `fpc_brain.py` also now tells the model to always close with `]]` and
never use shorthand inside the tag (harden at the source).

### Active deal-context cleanup — fixed (`FakePlayerChatManager.java` + `FakePlayerBehaviorManager.java`)

`ACTIVE_DEALS` was only dropped on an explicit `[[MEET:cancel]]`, so a completed / timed-out deal's `X-Deal-*`
terms lingered and could be injected into a later unrelated whisper to the same bot. `endMeet(...)` — the single
chokepoint every deal ending funnels through (sold-out, meet hard-cap, grace-leave, cancel) — now calls the new
`FakePlayerChatManager.clearDeal(player, bot)` before it forgets the player. The pre-meet cancel removal stays for
the edge where a deal was offered but no meet ever started.

### Chat realism — human-like timing (`FakePlayerChatManager.java`)

First step toward believable social presence (timing, not wording):
- **Length-scaled "typing" latency** — every bot→player whisper (`sendChat`) and every public-channel line
  (`botSpeaks`) is deferred by `typingDelayMillis(...)`: a short base pause plus ~45ms/char (capped 4s, ±25%
  jitter). A one-word "np" pops out fast; a long sentence takes a beat. Replies no longer appear the instant the
  brain returns, at one fixed speed.
- **Replier stagger** — when both allowed repliers answer the same line, the 2nd waits `REPLY_STAGGER_MS` (4s)
  longer, so it reads as someone chiming in *after* the first, not a simultaneous bot chorus.

> Tuning note: whisper replies now stack the existing 5–15s "thinking" delay + LLM time + typing time (~7–21s
> total). If that feels sluggish, shrink `MIN_DELAY`/`MAX_DELAY` — the typing layer now carries the realism, so the
> base thinking delay can safely drop.

### Still open (next social-realism ideas, not yet built)
- Memory surfaced into whisper *openings* (continuity: "still farming ssd?").
- Per-bot ephemeral mood drift (tired after a long grind, hyped after a drop).
- Emitted typos + self-corrections (`teh` → `*the`).
- Allow disinterest — a bot that says "nah" and disengages reads more human than one that always helps.

---

## 2. System A — LLM Chat Brain

**`fpc_brain.py`** (repo root) — Flask sidecar the Java server calls over HTTP.
- Talks to DeepSeek or local Ollama; provider set by `PROVIDER` env var.
- `POST /chat` endpoint; Java sends message + headers (`X-FPC`, `X-Mode`, `X-Player`, `X-Speaker`).
- **Distinct per-bot voices** — each bot's name seeds a stable personality (tone / casing / filler / typo traits + its own temperature) so replies don't all read like the same person. Same name → same voice across restarts (md5 seed).
- **Guardrails** — a `GLOBAL_RULES` constitution prepended to every persona: act as a real human player (not the in-game character), never reveal being an AI, resist prompt-injection, no GM powers or free-stuff promises, PG-13, no real-world links/contact. A `sanitize()` post-filter drops out-of-character replies and strips URLs/emails.
- Bots reply `pass` to stay silent (natural pacing).

**Brain modes** (10 total):

| Mode | Purpose |
|---|---|
| WHISPER | Private per-(player,bot) memory conversation |
| SAY | Local area banter |
| TRADE | Trade channel ambient chatter |
| AMBIENT | Global ambient fallback |
| SHOUT | Global world channel (`!`) banter |
| SHOUTAMBIENT | Spontaneous LFM/world ads |
| ITEM | Translate trade-chat shorthand to canonical item name |
| OFFER | Bot's opening negotiation pitch |
| BUDDY | Personal support bot with action tags |
| BUDDYCHAT | Buddy spontaneous party-chat small talk |
| LFP | Classify a free-form shout into wanted party roles |

Action tags from brain replies (e.g. `[[MEET:gatekeeper]]`, `[[SHOP:SELL:Soulshot D-grade:500]]`, `[[BUFF]]`, `[[FOLLOW]]`, `[[TP:ruins of agony]]`) are parsed by Java to trigger behavior, then stripped before display.

**Java side: `managers/FakePlayerChatManager.java`**
- `overheardTradeChat` / `overheardSay` / `overheardShout` → picks nearby bots → HTTP to brain → broadcasts reply.
- **Shout** — bots banter on `!` channel; spontaneous shout ~every 5 min posts chit-chat or an LFM ad. A **real player's** shout reaches bots **world-wide** (global channel, not the `SOCIAL_RANGE` proximity used by trade/say) and is **guaranteed a reply** — the first eligible non-vendor bot always answers (brain told not to `pass` via `X-Human`), so shouting from inside a vendor crowd no longer gets ignored. Bot-to-bot shout stays local + free to `pass`.
- **Whisper** — whispering a bot by its generated name works; it replies and knows its nearest town.
- **"Come meet me"** — ask a roaming bot to meet you at a landmark (gatekeeper/warehouse/shop) and it walks there, then waits up to ~8 min.
- **Trade-ad negotiation** — WTS/WTB in trade chat makes a nearby bot PM you with a price. You haggle + pick the meet spot over whisper; only then does the bot walk there and open a real seated vendor store. Bot stays whisper-able mid-deal for renegotiation or cancel. AFK vendors are intentionally silent.

**Knowledge grounding** — `knowledge/*.txt` tagged fact files (Interlude zones, towns, buffs, classes, party basics, item shorthand). On startup the brain loads them; per chat it scores facts by tag overlap with the player's message (level numbers matched against a zone's `level lo hi` band get a boost) and injects the top few into the system prompt under a "Game facts you can rely on" block. Used by WHISPER/SAY/SHOUT/BUDDY (all facts) and TRADE/ITEM (filtered to `item`/`buff` for canonical item-name resolution). When nothing matches, nothing is injected — unrelated chat is unchanged. Stops bots inventing zones/levels/items. Adapted from the `l2-smartbot` project's tag-fact idea, corrected for Interlude. See `knowledge/README.md`.

Run: `python fpc_brain.py` (needs API key env). Listens on `127.0.0.1:5000`. Java falls back to canned replies if offline.

---

## 3. System B — NPC Fake Players

### Per-instance appearance
Mobius FPCs are NPCs. Normally one template = one fixed look. We override this:
- **`FakePlayerAppearance.java`** — per-bot identity (name, race, gender, class, hair, gear, store type, sitting, store message).
- **`Npc.java`** — carries optional appearance; `getName()` honors it; `sendInfo()` sends store-title packet.
- **`FakePlayerInfo.java`** — renders NPC as player, reads from appearance when present.

### Generator: `FakePlayerAppearanceFactory.java`
Unique pronounceable names, coherent race/gender/class combos, level-appropriate gear (C-grade cap, mixed wealth), store titles.

### Behavior manager: `FakePlayerBehaviorManager.java`
Loads `data/FakePlayerBehavior.xml`, deploys bots ~15s after boot, ticks every 3s.

**Behaviors:**
- **WANDER** — drift within radius or drawn polygon zone.
- **VISIT** — roam waypoints randomly (purposeful town movement).
- **PATROL** — walk waypoints in order, loop. Runs continuously (0 pause) unless a per-waypoint delay is set.
- **FARM** — seek and fight monsters in zone; `respawn="true"` replaces on death.
- **SHOP** — seated, immobilized SELL/BUY/CRAFT/PACKAGE vendors.

**Reliable route following** — moving bots are flagged as engine walkers (`setWalker` + random-walk off) so the core NPC AI no longer drags them back to spawn past `MaxDriftRange`. Waypoints go through the engine's own pathfinding (climbs stairs, rounds obstacles) with a real-arrival check + re-kick + teleport recovery so stalled hops are retried.

**Named routes** (`data/routes/*.xml`) — reusable waypoint lists shared across populations. Each population can set independent per-waypoint delays (honored for named/recorded routes too) and traverse order (`reversed="true"`).

### Functional shops
- **Real SELL/BUY/CRAFT/PACKAGE stores** — clicking a vendor opens an actual buy/sell window (`FakePlayerStoreFactory`, `FakePlayerStoreManager`).
- **Region-appropriate stock** — a population's level range caps the grade of gear AND consumables (shots/mats carry `crystal_type`), so Gludio sells SSD/BSSD, not S-grade. `fullStock="true"` marks a market hub (Giran) that stocks everything.
- Full transaction: validates purchase, moves adena, transfers item, decrements stock, closes store when sold out.
- CRAFT vendors offer recipes; MANUFACTURE stores are finished-goods SELL with recipe success rates.

### Config — `dist/game/config/Custom/FakePlayers.ini`
`EnableFakePlayers`, `FakePlayerChat`, `FakePlayerBehavior`, `FakePlayerDeployCount`, `FakePlayerBaseNpcId`.

---

## 4. System C — Real-Player Phantoms

Clientless `Player` objects (same pattern as Mobius offline traders) that farm via the engine's native **AutoPlay + AutoUse** system. For field zones where NPC FPCs can't do real combat.

**Key config required:** `EnableAutoPlay = True` in `config/Custom/AutoPlay.ini`.

- **Deployment**: loads `data/PhantomPopulations.xml` on boot; zones activate **on demand** (when a real player walks within range) and despawn ~30s after the last player leaves.
- **Identities**: random melee or mage (~30%) fighter class across races, pronounceable names, varied grade-appropriate gear.
- **Class progression**: advances to the class their level warrants (1st at 20+, 2nd at 40+, 3rd at 76+), learns full skill tree, registers buffs/attacks into AutoUse.
- **Gear**: sword + soulshots for fighters; magic weapon + robe + spiritshots for mages. Data-driven, no hard-coded item IDs.
- **Sustain**: mages and wounded fighters sit to regen; stands immediately (1s tick) when a threat appears. HP potions (×20000) with auto-potion for in-combat sustain.
- **Mage combat**: dedicated 1s tick positions mage at cast range. OOM → disengages and rests; after a kill walks to corpse so auto-pickup grabs the drop.
- **Dormancy**: pauses auto-hunt when no real player is in range (saves CPU). Hard cap: `MAX_PHANTOMS = 200`.
- **Initial dispersal**: on spawn, phantoms fan out toward their area's perimeter for ~4.5s before hunting so they spread instead of converging.
- **Roaming**: supervisor roams idle phantoms to fresh spots so they spread over a zone and don't freeze when local mobs run out.
- **Target deconfliction**: authoritative 1s pass keeps the closest phantom on a contested mob and assigns every other idle phantom the nearest unclaimed mob. Yields to real players fighting a mob (checks the mob's aggro list).
- **Post-kill breather**: phantom pauses ~1.2–3.2s after a kill before pulling the next mob.
- **Admin commands**: `//phantom spawn [count] [level]`, `//phantom clear`, `//phantom count`.

Caveats: requires geodata for pathfinding. No persistence across restarts.

---

## 5. System D — Personal Support Buddies

A **buddy** is a special phantom: a support-class clientless `Player` you party as a personal buffer/healer. Built on the phantom system but binds to one player instead of hunting.

**Three roles** (place via editor phantom mode → role dropdown, or `PhantomPopulations.xml` `role="..."`):
- **BUDDY_ELDER** — Elven Elder (mage buffs, heals, recharge)
- **BUDDY_PROPHET** — Prophet (fighter buffs)
- **BUDDY_WARCRYER** — Warcryer (Orc buffs)

All three carry a Heal so every buddy can top you up. Place at **level 40+** for a real 2nd-class buff kit. An idle buddy does nothing until you party it.

> Known issue: Orc Warcryer can render with no torso (separate magic tunics have no orc model) — to be revisited.

**Flow:** buddy idles where placed (spawns/despawns by player proximity). Whisper it → send party invite → `RequestJoinParty` auto-accepts server-side and binds it as your buddy. You can party several at once (Prophet + Elder + Warcryer together). Once partied it:
- Keeps **you and itself buffed** (re-casts a buff when it's missing or within ~20s of expiring).
- **Heals you** below ~50% HP (and itself below ~40%).
- **Follows** you (250px range).
- **Watches MP** → whispers "i'm low on mp" at <25%, sits to recover when safe (no mob within 700px, you're not fighting), stands at ≥70% MP, or instantly if threat appears.
- **Teleports** to a named gatekeeper destination (reads from `data/teleporters/town/*.xml`). Confirm-first: an explicit order goes immediately, a suggestion waits for your yes.
- **Grace period** if you teleport away or briefly log off; "brb" / "give me 5" extends it; disband/logout despawns it.

**Drive from whisper or party chat** — `ChatParty` forwards party-channel lines so "follow", "stay", "going to X", "brb" in party chat work. Replies go out after a ~1–2s human-like delay.

**Proactive small talk** — every 7–18 min (downtime only) a buddychat opener in party chat, so it doesn't feel like a silent bot.

**Works brain-off** (deterministic keyword parsing): party, follow, stay, `<place>`, brb, disband, buff, status, yes/no for teleport confirm. **With brain on** (BUDDY mode): free-form messages get a natural reply with action tags; abbreviations like "roa" → Ruins of Agony understood.

**Key files:** `PhantomBuddyManager.java` (1154 lines), `PhantomManager.java` (76KB), `PhantomPopulations.xml`, `ChatWhisper.java` / `ChatParty.java` dispatch, `RequestJoinParty.java` auto-accept.

**Tick interval:** 1s. Support range 900px, follow range 250px, danger range 700px.

---

## 5b. System E — Recruited Combat Parties

Shout an **LFM/LFP** and a full party assembles itself: for each role you call for, a level-matched combat phantom spawns just out of sight, **walks over**, asks for an invite, and joins you. It generalises the buddy (bind / follow / party / grace) into combat roles and adds a recruitment layer on top.

**Roles** (`PhantomManager.PartyRole`): `TANK`, `WARRIOR` (melee DD), `ARCHER` (bow), `DAGGER`, `NUKER` (mage DD), `HEALER` (Elder kit + **Resurrection**), `BUFFER` (Prophet kit). Combat roles get a fixed class per level tier (1st at 20+, 2nd at 40+), the full skill tree, and matching shots. A bad class id degrades to a plain fighter/mage rather than crashing.

**Recruited-member gear (`gearParty`)** — unlike the deliberately cheap, patchy *ambient* phantom loadout (cheapest items, randomly missing helmet/gloves/boots, no jewelry/shield), a recruited party member gears up for real content: a **best-in-grade** role weapon (sword / bow + arrows / dagger / magic staff), a **full** armor set with **no random gaps** (a `TANK` prefers HEAVY), **all five jewelry slots** (necklace + 2 earrings + 2 rings, for the P./M.Def the ambient bots lack), a **shield** for the tank, and a **chance the whole weapon+armor kit is enchanted** (~65% of members, +3 to +6). This applies to support members (healer/buffer) too, so the Elders survive content. Ambient town/field phantoms are unchanged.

**Two ways to find them (both work; the second needs no AI):**
- **Brain on** — a free-form call ("need a box + someone to tank cruma") is sent to the brain's new `LFP` mode, which classifies it into roles.
- **Brain off** — a keyword parse of the shout (`lfm/lfp/lf/need` + role words + counts, e.g. "lfm 2 dd healer") extracts the roles directly.

**Recruitment feel:** members spawn ~1.9–2.6k units away, out of line of sight, answer the shout on `!` ("healer here, omw"), and jog in via engine pathfinding — so it reads as real players who saw your call, not NPCs popping in. Arrivals are staggered. They match **your exact level**.

**Once partied:**
- **Assist (default)** — every member focus-fires *your* current target. Skills + soulshots fire through the native AutoUse (the member is flagged auto-playing so AutoUse casts, but the target is the leader's, not the engine scanner's). **Nukers hold at cast range and nuke** instead of being given a physical ATTACK intention (which dragged a caster into melee to auto-hit even on a full MP bar); out of MP they break off and sit to recharge rather than meleeing; they **spread laterally** so several don't stack on one tile and eat the same boss AoE. **Tanks actively hold threat** — pin a raid boss with taunts, or yank back any mob that slips onto the healer/nukers (Aggression / Aura of Hate).
- **Tank-initiated raid pull** — against a **raid** target the party *holds* instead of auto-attacking (so DPS can't pull the boss off the tank at the open). Order the tank to pull, then the rest follow once it has aggro:
  - **`tank attack`** (or `tank pull` / `tank go` / `tank engage` / `pull it` / `initiate`) — the tank engages; the party is released to assist the moment the tank holds the boss's threat.
  - **`all attack`** (or `everyone attack` / `all in` / `open fire` / `burn it`) — everyone engages the raid immediately, skipping the tank-initiate.
  - **`hold fire`** (or `hold dps` / `wait for tank` / `fall back` / `back off`) — re-arm the hold mid-fight. Once released, the party can freely switch between the boss and its adds; the hold re-arms automatically when the raid fight ends. Normal (non-raid) mobs are unaffected — everyone assists immediately as before.
- **Multiple healers coordinate** — a healer won't pile onto a target another healer is already healing (it picks the next-most-hurt instead), so 3 healers spread their casts rather than triple-healing the tank and wasting two on overheal. A *critically* low target (<50%) still gets stacked heals as an emergency.
- **Tank taunt throttle** — once the tank is top of the boss's aggro it stops re-taunting every tick (its auto-attacks hold the hate); it only re-taunts when aggro actually slips to a squishy or on a slow ~6s refresh. A trace showed the tank draining its *whole MP bar* spamming Aggression+Aura of Hate every second while already holding aggro, then being unable to taunt when a DPS spike finally pulled the boss — saving that MP keeps it taunting through the fight.
- **Recharge (mana sustain)** — an **Elder / Shillien Elder** in the party casts Recharge on the most mana-starved caster (healers first, then the tank, then other casters) below ~45% MP. This is the sustain that stops a long fight from ending in a mass-OOM wipe (the healers burn their whole pool keeping the tank up, hit 0 MP, and the party melts). **Bishops don't have Recharge** — bring at least one Elder/Shillien Elder for the mana battery.
- **`attack freely`** — flips a member to hunt nearby mobs on its own (real AutoPlay scanner); leashes back if it strays.
- **Healer** heals the most-hurt party member (<60%; under a raid it heals pre-emptively — emergencies first, then keeps the tank topped ~90%, then others <80% — and won't sit to meditate mid-boss) and **raises the fallen** — real players first, then dead bot members, which are now kept as a corpse for a ~60s window so they can be battle-rezzed instead of melting permanently; **buffer** keeps the whole party buffed.
- **Follows** you, survives an offline **grace** window ("brb" extends it), despawns on disband.

**Drive from whisper or party chat** (deterministic, brain-off): `assist`, `attack freely`, `follow`, `hold`/`stop`, `gather`, `brb`, `status`, `go to <place>`/`tp <place>` (teleports the member to the same gatekeeper spot a buddy uses, so the party regroups there), `bye`/`disband`. A party-chat **command** hits every member at once; free-form chatter gets a natural brain reply (`PARTY` mode, with `[[ASSIST]]`/`[[FREE]]`/`[[FOLLOW]]`/`[[STAY]]`/`[[TP:x]]`/`[[GRACE:n]]`/`[[DISBAND]]` action tags) from just one member so a full party doesn't all answer at once.

**Robustness:** a member that stalls mid-walk re-kicks its follow and teleports to catch up (no more "coming" while standing still); a wedged cast is aborted instead of freezing the member; casters (buffers/healers/nukers) sit to recharge MP *while the party fights* and pop up if a mob reaches them or you run off; a "go to X" order makes members travel to the spot and wait there for you rather than being yanked back by the follow watchdog.

**Loot & XP:** recruited members never pick up items (drops are left for you), and clientless party members (recruits + buddies) are excluded from the party XP/SP split, so bots don't steal or shrink your experience.

**LFP level:** an optional level can be given, e.g. `lfm 1 buffer lvl 57` - members spawn at that level instead of matching yours. `dd`/`dps` resolves to a random damage role (varied party); explicit `warrior`/`archer`/`mage`/`dagger` target exactly.

**One shout, a whole comp:** a single LFM can fill the **entire party** (up to 8 members, was capped at 6), and **plurals** parse, so `!lfm 1 tank, 2 melee, 2 healers, 1 prophet, 2 mages` recruits the lot. They spawn close by and assemble in a few seconds (small stagger) rather than trickling in one at a time.

**Support positioning under a raid:** support roles anchor their healing range on the **tank** (not only the leader) while a raid boss is engaged, so the tank — which fights the boss in melee while the leader hangs back — stays healable. They no longer follow the tank all the way into a 250px melee clump; idle support movement uses a spread backline lane around the raid target.

**Class-aware buffing** (`PhantomBuffs`): a buffer/healer no longer wastes physical buffs on casters (or magic buffs on fighters). The **player** and recruited party members get the full archetype-appropriate kit; the **buffer itself** keeps only movement + casting speed and **buffs itself first** (so Acumen lands and the rest cast faster). Applies to both recruited buffers and personal buddies.

**Specific class requests:** name an exact occupation, e.g. `LFM 1 Shillien Elder`, `lfm gladiator + hawkeye`, and that class spawns (2nd-class+ occupations recognised by name; base/1st-class words fall through to level-appropriate generic roles). Works in the deterministic keyword path.

**On-demand support orders** (whisper or party chat, brain-off): `rebuff` / `buff me` recasts the full kit on you (one buff per tick), **`buff all` / `buff the party` fully buffs every party member**, **`buff <name>` fully buffs one named member** (e.g. the tank), `heal me` heals you once even at full HP, `res` raises a fallen member. **Address a specific support by name to direct it** — `<healername> buff <targetname>` makes only that healer fully buff that target (its own name is excluded so the target resolves correctly). A full-party rebuff yields to res/heal each tick so nobody dies while it grinds through buffs. Applies to recruited healers/buffers and personal buddies.

**Members arrive pre-buffed** — a recruited member spawns with its full archetype kit already applied (Prophet/Elder buffs scaled to its level), so a fresh party isn't unbuffed. The party buffer then maintains the **full** kit on everyone (members now get the complete kit, not bare essentials — casters get Empower/Wild Magic/Acumen/etc., fighters get Might/Haste/Focus/etc.). Heals (incl. a continuous group heal/HoT) are excluded from the maintained-buff list, so a healer no longer recasts a group heal on cooldown "for no reason".

**Raid-fight review / Zaken-Tillion test pass (2026-06-28):** latest tests with `!lfm 1 tank, 1 bishop, 1 shillien elder, 1 prophet, 4 archers` changed the wipe pattern. The Shillien Elder battery now exists and `Recharge` fires, the Bishop uses `Greater Battle Heal`, and the Prophet can off-heal. The loss was no longer mainly "healers go OOM"; it was tank survival and add control. The tank died early while healer MP was still available, then the party kept feeding DPS into a fight with multiple active raid mobs/adds.

Implemented from that review:
- Raid release is now **per raid mob/add**, not whole-fight. Archers/casters cannot freely swap to an untanked Bat/Inferior just because the tank once held the first target. `all attack` still force-releases if the player wants to override.
- Tank death clears raid release. After a tank battle-res, DPS stays held for a short recovery window until the tank is healed back to a safer HP band and can regain hate.
- Resurrection has a short shared claim, so two healers should no longer double-cast res on the same corpse in the same tick.
- Support priority is closer to player behavior: emergency heal a living critical target first, then res, then `Recharge`, then normal/pre-emptive healing and buffs.
- Raid `Recharge` starts earlier and skips classes that know `Recharge`, matching the Interlude restriction that Recharge cannot be used on recharge-capable classes.
- Healers/buffers and ranged DPS use a spread raid backline instead of following the leader/tank into a tight melee clump. This should reduce shared AoE damage and cast interruption from constantly moving closer mid-fight.
- Raid debug snapshots now list all engaged raid mobs/adds and their hate targets, not just the first visible raid mob.
- Exact tank class requests now map to `TANK` behavior (`Paladin`, `Dark Avenger`, `Temple Knight`, `Shillien Knight`, and 3rd-class tank names), instead of spawning a tank class with warrior AI.

Honest human-play evaluation: the recruited party is getting closer to a real pickup group, but it is still not at "good human raid party" level. It can assist, heal, buff, recharge, res, taunt, and now spread/hold DPS better. What it still lacks is tactical intent: encounter-specific kill order, explicit add marking, healer assignment ("Bishop main heal, SE battery/off-heal"), tank cooldown policy, safe repositioning after actual AoE hits, and understanding when the raid stats/party level/gear simply make the pull bad. The next big step should be a small raid-tactics layer above the generic party brain: detect active boss + minions, choose kill order, mark current safe DPS target, keep ranged at max useful range, and assign support jobs per composition.

**Raid-tactics layer — first pass (generic, observed-mechanic; no per-boss table):** a deterministic Java layer in `PhantomPartyManager` that reacts to what the engaged raid is *actually doing*, so it works for any raid (including custom ones) with zero per-boss authoring. Three behaviors, all raid-gated so normal farming is untouched:
- **Add/minion kill order** — when a raid boss has live minions up (`isRaidMinion()`), DPS (melee + ranged) focus the adds before the boss, lowest-HP add first so kills finish fast. Only adds the tank already holds / that are released are considered (`raidKillOrderTarget` reuses `mayAttackRaid`), so this re-prioritises among in-play targets and never makes DPS pull an untanked add. The tank stays pinned on the boss and still grabs loose adds via its existing threat loop.
- **AoE / teleport step-out** — `dangerousCaster`/`isDangerousAoe` watch every engaged raid mob's in-progress cast (`getLastSkillCast`); a cast that is `isAOE()`, has a splash radius (`getAffectRange() >= AOE_MIN_RADIUS`), or is a teleport effect is treated as telegraphed. A ranged DPS or support standing inside the blast steps out past the radius (`avoidAoe` → spread backline) and skips that tick, then resumes. Members already clear keep fighting; melee stay in (no telegraph timing). For support, step-out sits *below* emergency heal / cleanse / res, so a dying or held member is saved first and a support only dodges when the party is otherwise stable.
- **Debuff cleanse** — a healer/buffer that knows Purify (1018) / Cure Poison (1012) / Cure Bleeding (61) strips a removable debuff (poison/bleed/paralyze/petrify) off a party member, the **tank first** (a paralyzed/held tank drops the boss and the party wipes). The carried debuff's `AbnormalType` is matched against what that specific cleanse can remove, so it never burns a cast on an uncurable debuff. Cleanse 1409 is deliberately skipped (it eats Einhasad Holy Water). Runs above res so a held tank is freed before it dies.

Still open from the review: encounter-specific kill order (vs. generic "adds first"), explicit healer assignment, tank cooldown policy, and a per-boss `RaidTactics.xml` override for the marquee fights. The brain (LLM) is intentionally **not** in the combat tick — these are 1s-deterministic Java decisions; the brain stays for language tasks (LFP/chat) and could later author the per-boss override file offline.

**Fix pass after the Tillion (Zaken's Chief Mate) test (2026-06-28):** first live raid with the tactics layer exposed two bugs and one wasteful behaviour:
- **Step-out only dodges AoE _damage_ now.** Tillion spams **Hold** (`abnormalType=ROOT_MAGICALLY`, several variants are `targetType=AREA`) — an AoE *root* with no damage. The old `isDangerousAoe` treated any area skill as dangerous, so the whole backline abandoned healing/fire every ~2s to "dodge" a snare that (a) doesn't hurt you for standing in it and (b) a rooted member can't move out of anyway. `isDangerousAoe` now also requires a damaging effect (`MAGICAL_ATTACK`/`PHYSICAL_ATTACK`/`HP_DRAIN`/`DEATH_LINK`), so real area nukes still trigger the step-out but roots/holds/snares no longer wreck support uptime. (Note: Interlude Purify can't cleanse Root anyway — only poison/bleed/paralyze/petrify — so Hold is simply a "wait it out / kill the caster" mechanic, as on retail.)
- **No buff upkeep during a raid.** Members arrive pre-buffed and a real party buffs *before* the pull. Mid-boss, the maintenance re-cast just chased buffs the boss's hits kept interrupting (it read in-game as a support stuck "re-buffing one person") and stole casts from heals/recharge. Buff upkeep is now skipped while a raid boss is engaged; the support stays on healing/battery duty and upkeep resumes after the fight.
- **Same-buff overlap guard.** A Prophet (buffer) and Elder (healer) both maintain their own kits, which overlap on common buffs (Wind Walk etc.); without coordination both could see one "missing" on the same tick and both cast it. `beingBuffedByAnother` (mirrors the existing `beingHealedByAnother`) skips a buff another support is already landing on that target — complementary buffs (fighter vs. caster kit) are untouched.

The wipe itself was mostly **throughput, not tactics**: the tank held all four raid mobs (boss + Inferior + 2 Bats) at once and got bursted faster than two level-55 B-grade Elders could heal, then the healers pulled aggro and fell. The real remaining gaps are crowd-control / off-tanking the adds, tank survivability cooldowns, and threat discipline for healers — the per-boss `RaidTactics.xml` and a tank-defensive layer are the next steps.

**DPS + healer-MP pass after a second Tillion test (2026-06-28):** the next run held together much better (tank threat rock-solid for ~78s, no more Hold-dodge spam) but lost a slow attrition race — the boss sat at 99% the whole fight, the adds barely dropped, and the healers eventually went mass-OOM and the party melted. Two root causes, both fixed:
- **Ranged/melee DPS now sustain auto-attacks at 0 MP.** Archers burned MP on bow skills via AutoUse, then went *idle* once dry instead of plinking with soulshots (which cost no MP) — so their whole damage contribution was gated on MP and cratered after ~30s. The assist loop only re-issued `ATTACK` when "out of combat", so a clientless archer that finished a shot just stood there. It now re-asserts the attack whenever the member isn't mid-swing or mid-cast, keeping a live soulshot auto-attack between skills and right through an empty MP bar. (Members already get bow + arrows + soulshots via `addAutoSoulShot`; the tools were there, the loop just wasn't keeping them firing.)
- **Healers no longer double/triple-heal the same target.** Two healers (plus the buffer off-healing) were all landing a *big* heal on the tank in the same tick — the `beingHealedByAnother` guard only checked `isCastingNow`, which misses same-tick simultaneity, so one heal did the job and the others were pure overheal, burning healer MP 2-3× too fast. A per-tick `_healedThisTick` claim set (cleared each tick, committed the moment a support casts) makes the others spread to the next-most-hurt instead. The guard now also counts the buffer's off-heals (`isSupport()`), not just `HEALER`s. **Plus** a cheap-heal tier: a small top-off (e.g. keeping the tank at 90%) uses Battle Heal/Heal instead of Greater Battle Heal, reserving the expensive heal for a real gap (≥35% missing) or a critical (<50%) target. Emergencies still stack heals (the de-dup is bypassed below the critical threshold).

**Buff-on-join drains the buffer's MP — fixed (pre-buff once, never auto-rebuff bots):** members spawn pre-buffed (`PhantomBuffs.applyFullBuffs`) with a base kit, but the party buffer was maintaining its *full* known kit on every member, so on join (and on every refresh) it re-cast the buffs the prebuff didn't already include — entering the boss fight already ~⅓ down on MP (the in-game complaint: buffers at ~64% at the pull). Since Interlude buffs last **20 minutes** (`abnormalTime 1200s`) — far longer than any fight — a pre-buffed member never needs re-buffing. `maintainPartyBuffs` now **skips recruited bot members** entirely (`_members.containsKey`): only the leader (a real human, not pre-buffed) and any real human party members get ongoing upkeep, and the buffer keeps its MP for healing/recharge. An explicit "buff all" / "buff <name>" order still tops up everyone via `forceRebuff`. (Buffs could be enriched at spawn — `applyFullBuffs` is a free, instant `applyEffects`, no MP — to give bots the Greater versions / Bless the Body / Prophecy too, since they're no longer maintained in-fight; deferred.)

**Tank stops attacking after a battle-res — fixed (taunt throttle + tank holds the boss):** a third Tillion test was the cleanest yet (tank held all four mobs ~70s, the Inferior add died, healers coordinated), but after the tank was battle-rezzed it only taunted and never auto-attacked again ("didn't resume hitting the boss"). Cause: a freshly-rezzed tank has lost all aggro, so `findLooseMobs` reports every mob as "loose" every tick, and the loose-mob taunt branch had **no throttle** — it taunted on every tick, returned "taunted this tick", and so never reached the auto-attack re-issue. Result: it taunt-spammed, did zero melee damage (built no melee hate, so it couldn't reclaim the boss from the healer it was stuck on) and stood idle between casts. Two fixes: (1) a `TAUNT_MIN_INTERVAL` (1.5s) floor between any two taunts, so the tank spends the in-between ticks auto-attacking — doing damage and sustaining the melee hate that keeps mobs on it; (2) the tank now **melees the engaged boss itself** (`engagedBoss`) instead of whatever add the player is shooting, so it holds and damages the boss directly (still gated behind the "tank attack" pull order, so the pre-pull hold is intact). Remaining wall from that test: raw DPS is still low (B-grade/lvl55 archers vs a raid's HP/P.Def) — the boss never gets below 99% because kill-order keeps DPS on the adds and the adds die slowly; the next thing to verify is whether the archers' soulshot auto-attacks are actually firing/landing (one archer auto-attacked fine, two others skill-dumped then idled).

**Melee DPS lock-out / "run away and come back" — fixed (per-add release was too strict):** a 3-warrior test exposed a bad bug in the raid-release gate. `mayAttackRaid` re-locked an add the instant a party member was most-hated on it (`!isPartyMember(hated)`). A melee DD out-aggros the tank on an add almost immediately, so: (a) the **other** DDs — all aimed at the same lowest-HP add by kill-order — were locked out and stood at 100%/100% idle (only one of three warriors ever fought), and (b) the engaged DD **bounced**: pull aggro → `mayAttackRaid` false → `holdForPull` → re-taunt by tank → re-engage → repeat (the visible "run to the add, run back to the leader, repeat"). Fixes: (1) once an **add** (`isRaidMinion`) is released it stays attackable for every DD regardless of who holds it — so all melee pile on and nobody bounces; the **boss** keeps the strict squishy-protection rule. (2) `holdForPull` only sends **ranged** roles to the spread backline — a melee waiting for the pull stays near the leader instead of running 720 out and back. (3) the tank's `findLooseMobs` now only rescues **squishies** (leader, healers, buffers, nukers) via `needsRescueFrom`; a WARRIOR/DAGGER holds its own add, so the tank stops stealing adds from the melee and stays on the boss. Net intent: tank holds the boss, melee hold + focus-kill the adds, so the boss damage is split off the tank instead of one tank soaking all four mobs.

**Archer raid DPS — fixed (held beyond bow range):** the best run so far (3 archers + 2 healers + buffer + tank) survived ~6.5 minutes — the defensive stack (tank holds boss + taunt throttle, heal de-dup, recharge battery, no buffer-MP drain) is clearly working now — but the boss only reached ~87% and the party eventually wiped to healer OOM: a pure DPS-rate stalemate. Root cause: archers were held at the generic `RAID_BACKLINE_RANGE` of **720**, but a B-grade bow only reaches **500** (`pAtkRange`), so they could only land hits when they happened to drift inside 500 ("lucked dps"). And the lateral spread (`RAID_SPREAD_STEP=140`, ±3 lanes) added on top of the radial range pushed outer lanes to `sqrt(range² + 420²)` — even at range 400 that's ~580, still out of reach. Fix: archers now hold at a distance **derived from the bow's actual reach** (`archerHoldRange` = `getPhysicalAttackRange() − 80`, clamped ≥350 and ≤720, so ~420 for the B bow) using a **tighter spread** (`ARCHER_SPREAD_STEP=70`) so even the outer lanes stay within ~470 — comfortably inside the 500 reach while still outside melee-AoE. `positionRaidBackline` gained an optional spread-step parameter (casters/support unchanged). Adds were already dying (kill-order works — the party downed all three, just slowly); this should sharply raise the rate and let ranged comps actually close the fight.

**Key files:** `PhantomPartyManager.java` (new), `PhantomManager.java` (`PartyRole`, `spawnPartyMember`, recruit API + supervisor guards), `FakePlayerChatManager.java` (`overheardShout` LFP parse + `LFP` brain call), `RequestJoinParty.java` / `ChatWhisper.java` / `ChatParty.java` dispatch, `fpc_brain.py` (`LFP` mode).

---

## 6. The Editor — `tools/fpc-editor/index.html`

Single HTML file, no install. Edits both `FakePlayerBehavior.xml` (NPC mode) and `PhantomPopulations.xml` (Phantom mode).

- **Load/Save XML** — populations editable; profiles/assigns/default preserved verbatim.
- **Geodata map** — point at `game/data/geodata`; parses `.l2j` files in-browser, renders height-relief map auto-aligned to coordinates.
- **Landmarks** — towns/villages at real coordinates for orientation.
- **Map image** (optional) — load a client world-map image over geodata, opacity slider.
- **Edit** — double-click to add population; drag circles; side form for count/level/profile/race/store/respawn.
- **Draw zone** — click points to outline a polygon area (saved as `<point>` vertices).
- **Named route drawing** — draw, edit and drag waypoints directly on the map; set per-waypoint delays; reverse flag; save as `routes/<name>.xml`.
- **Market hub toggle** — `fullStock` checkbox for Giran-style shops.
- **Phantom mode** — switch to edit `PhantomPopulations.xml`; role dropdown for buddy archetypes.
- **Show/hide** individual populations (eye toggle) or all at once.

Workflow: edit visually → **Save XML** → copy to `game/data/` → use `//reloadfakeplayers` in-game (no restart needed).

See `tools/fpc-editor/README.md` for full usage.

---

## 7. How to build & deploy

| What changed | How to deploy |
|---|---|
| Anything under `java/` | `ant` build → copy `GameServer.jar` to live server |
| Scripts under `dist/game/data/scripts/` | Copy files to live datapack (runtime-compiled, no jar needed) |
| `FakePlayerBehavior.xml`, `routes/*.xml`, config files | Copy to live `game/data/` |
| `FakePlayers.ini` | Copy to live `game/config/Custom/` |
| Editor `index.html` | Just open in browser — no build |
| `fpc_brain.py` | Restart the Python process |

Build environment: JDK 25 + Ant. Build cannot run in this dev environment (Java 21, no Ant) — Java changes are hand-verified. If a build error appears it's almost always a quick import/signature fix.

**Geodata:** the live server needs `.l2j` cell geodata in `game/data/geodata/` (`%d_%d.l2j` names). Without it, pathfinding is auto-disabled and field bots can't place on ground. Log line `GeoEngine: Loaded N regions.` with N>0 = good.

---

## 8. Admin tools & in-game panels

### Rates panel — `//rates`
Open with `//rates` or the **Rates** button on the Server menu (`//admin` → Server tab).

- Preset buttons (1x / 3x / 5x / 10x / 20x) for XP, SP, Party XP, Adena, Drop Amount, Drop Chance, Spoil, Raid Drop, Quest XP, Quest Adena.
- Custom row: type any key + value and hit Set.
- Changes apply **live** to running memory and are written back to `Rates.ini` immediately (survive `//reload config`).
- Direct command: `//setrate xp 5`
- Valid keys: `xp` `sp` `partyxp` `partysp` `adena` `drop` `dropamount` `dropchance` `spoil` `raidrop` `questxp` `questadena`

### Reload panel — `//reload`
Standard Mobius reloads plus a **Living World** section at the bottom:

| Button | Command | What it does |
|---|---|---|
| FPC Chat | `//reload fakeplayerchat` | Reloads `FakePlayerChatData.xml` (canned chat lines) — instant, no bot disruption |
| FPC World | `//reloadfakeplayers` | Despawns all managed bots, re-parses `FakePlayerBehavior.xml`, redeploys fresh bots (~15s delay) |
| Rates | — | Opens the Rates panel directly |

> **FPC World workflow:** edit XML in the visual editor → copy to `game/data/` → hit **FPC World** — no restart needed. (`FakePlayerBehaviorManager.reload()` handles the despawn + redeploy cycle. This is Java-side so requires a jar rebuild once.)

### Other admin commands

| Command | What it does |
|---|---|
| `//record_route <name>` | Start recording bot waypoints as you walk (~150-unit intervals) |
| `//stop_route` | Stop recording and save to `data/routes/<name>.xml` |
| `//list_routes` | List all saved named routes |
| `//fakechat <player> <fpcname> <message>` | Trigger a bot response to a player (testing) |
| `//phantom spawn [count] [level]` | Spawn phantoms manually |
| `//phantom clear` | Despawn all phantoms |
| `//phantom count` | Show active phantom count |
| `//phantom debug [on\|off]` | Toggle the raid combat trace (logs boss aggro / member HP-MP-action / taunt / heal / res / death to the gameserver console; raid-only, so silent during farming) |

---

## 9. What works

- ✅ Player-global persistent memory in `fpc_brain.py` (`memory/fpc_memory.json`)
- ✅ Quantity-aware WTB/WTS trade responder shops (`+WTB ssd 5k`, `+WTS ssd 5k`)
- ✅ Structured trade context headers from Java to Python (`X-Deal-*`)
- ✅ Meetup destinations offset beside gatekeeper/warehouse/merchant instead of inside the NPC model
- ✅ Support buff kit audited slot-by-slot: melee + caster auto-kits carry the standard Interlude must-haves (incl. Bless Shield, caster Shield, Regen/Mana Regen, Resist Shock); greater/consumable buffs on-request (see §1c)
- ✅ Malformed MEET tag stripping + active deal-context cleanup on deal end (see §1c)
- ✅ Human-like chat timing: length-scaled "typing" latency + 2nd-replier stagger so bots don't answer instantly or in a chorus (see §1c)
- ✅ WTB/WTS responder PMs planned/separated from public social spam cap
- ✅ LLM chat: whisper / say / trade / shout, silence, reply dedup, distinct per-bot voices, guardrails
- ✅ Trade negotiation: bot quotes price, you haggle + pick meet spot, deal vendor renders seated and stays talkable
- ✅ Unique procedural identities (names, race, class, gear, hair)
- ✅ Town life: idle clusters, VISIT movers, racial village bias, Giran market
- ✅ Reliable route following: walker flag, full engine pathfinding, arrival + re-kick/teleport recovery, per-waypoint delays
- ✅ Functional private shops: SELL / BUY / CRAFT / PACKAGE, real transactions, sold-out closes
- ✅ Region-appropriate shop stock: level range gates gear + consumable tier; `fullStock` market-hub flag
- ✅ Field hunters: FARM behavior, respawn
- ✅ Visual editor: geodata map, polygon zones, route drawing + editing, drag waypoints, named routes, market-hub toggle, phantom mode
- ✅ Real-Player phantoms: auto-hunt, class/skill progression, gear, dormancy, claim-based targeting, initial dispersal, post-kill breather, caster looting, OOM-mage rest
- ✅ Personal support buddies: party an Elder/Prophet/Warcryer that buffs, heals (<50%), follows, watches MP (sit <25% / stand ≥70%), teleports (confirm-first), grace period; drive from whisper or party chat; proactive small talk; works brain-off (deterministic)
- ✅ Recruited combat parties: shout LFM/LFP → level-matched tank/DD/archer/dagger/nuker/healer/buffer spawn off-screen, walk in, auto-join; assist-leader (default) or `attack freely`; nukers nuke from range (never melee); tanks actively hold threat (pin a raid boss, yank mobs off squishies); healer heals + resurrects (now battle-rezzes fallen bot members too — corpses persist ~60s and auto-accept the res), buffer buffs the party (`buff all` / `buff <name>`); whisper/party-chat group commands; brain-on (free-form `LFP` classify) and brain-off (keyword) both work
- ✅ Rates panel (`//rates`) with live apply + save to ini
- ✅ Reload panel Living World section (`//reloadfakeplayers`, `//reload fakeplayerchat`)
- ✅ Admin route recorder (`//record_route`)

---

## 10. Known issues / rough edges

- ✅ **[FIXED 2026-07-01] Malformed MEET tag leakage with Ollama** — `MEET_TAG` now tolerates a malformed close (`]]`/`])`/`]`/`)`) so `[[MEET:gk])` etc. are stripped, and the whisper prompt reinforces exact tags. See §1c.
- ✅ **[FIXED 2026-07-01] Active deal context cleanup** — `ACTIVE_DEALS` is now cleared through `endMeet` (sold-out / hard-cap / grace-leave / cancel) via `clearDeal(player, bot)`. See §1c.
- ✅ **[FIXED 2026-07-01] Orphaned phantom character rows after unclean shutdown** — phantoms/buddies/party members are real `characters` rows (`account_name='phantom'`) deleted only when `despawn()` runs deliberately (zone-empty timeout / `//phantom clear` / reload). A hard kill (the usual case) never runs despawn(), so every such session used to orphan its active phantom rows forever — DB bloat, permanent `CharInfoTable` RAM residents (whole table loads at boot), and worse name-collision pressure. Fixed with a boot-time sweep: `PhantomManager.sweepOrphanedPhantoms()` (called first in `load()`) bulk-deletes every `account_name='phantom'` row via `GameClient.deleteCharByObjId()` (same cascade as despawn). `load()` runs once per JVM (lazy singleton, untouched by `//reloadfakeplayers`), so it can never remove a live phantom.
- **Haggle price clamp** — done 2026-07-01, see §11.3.
- **Trade responder probability verification** — separate trade-offer cap and wider responder search need gameplay verification after rebuild.
- **Orc Warcryer torso** — can render with no torso (separate magic tunics have no orc model); robe-only gearing attempt reverted, to be revisited.
- **OOM-mage kite edge case** — if a same-speed mob never lets the mage break off, it keeps retreating and could die instead of resting.
- **Field hunting feel** — bots can still cluster / move sluggishly in some zones; pathfinding + combat tuning deprioritized.
- **Map image calibration** — must be supplied by user; calibration is bounds-based (a friendlier 2-click calibration was discussed but not built).
- **Buddy on disband** — despawns where it stands; could walk/TP back to town first (deferred).
- **Shout LFM** — now actually recruits a party (System E). Remaining rough edges: combat roles below ~lvl 20 fall back to a base fighter/mage (no role class yet); archer/dagger soulshot auto-fire depends on the swapped weapon registering shots (archers now also carry grade-matched arrows so the bow actually fires); melee assist re-issues `ATTACK` so very fast target-swapping by the leader can look twitchy; recruits despawn where they stand on disband.
- **Phantom/buddy tuning constants** — heal/MP/buff-refresh/roam/dispersal values are constants in the manager files; should be lifted into a config file for runtime tuning.

### 10b. Prior-session code-review audit (verified against live code 2026-07-01)

A previous session relayed a prioritized findings list. Re-verified each by reading the current
code — **the three "High" items were already fixed**, so **do not re-implement them**:

- ✅ *Already fixed* — "malformed action tags leak into buddy/party chat": `PhantomBuddyManager` and
  `PhantomPartyManager` already use the tolerant `[\]\)]{1,2}` close on `ANY_TAG` and every per-tag
  pattern (lines 116-122 / 156-163), same as the `MEET_TAG` fix.
- ✅ *Already fixed* — `respawn="false"` populations revived anyway: `PhantomManager` death handling now
  despawns-without-revive for `respawn="false"` and gates the ad-hoc revive on `population == null`
  (lines ~2596-2622, with a comment documenting the fix).
- ✅ *Already fixed* — permanent DPS lockout if the tank dies: `PhantomPartyManager` has a
  `NO_TANK_FAILOPEN_MS` fail-open (~lines 1416-1429) that announces "no tank up, going in — say 'hold'
  to stop" instead of freezing forever.
- ✅ *Fixed this session* — orphaned phantom rows after unclean shutdown (the boot sweep above).

**Still open (confirmed, lower priority) — candidates for a future pass:**
- `ACTIVE_DEALS` orphan-on-ignore: entry is `put` on the offer PM and only removed via `clearDeal`/cancel;
  a player who simply ignores a WTB/WTS PM leaves it indefinitely (no TTL/eviction). The 2026-07-01
  "active deal cleanup" fix only covered the `endMeet` paths, not the ignore path.
- Trade rate limiter (`MAX_TRADE_OFFERS_PER_MINUTE`) is check-then-increment, not atomic — a soft cap.
- **Not yet verified** (relayed but not re-read this session): `_released`/`_releasedRaidTargets` not
  cleared on disband; `fpc_brain.py` `sanitize()` gaps (bare domains / "dot com" / Discord invites);
  archer lateral-spread geometry adding ~58 units past bow range; `FakePlayerStoreManager.sell()`
  overflow clamp-vs-reject; buddy-despawn manager-drop ordering; inverted `RequestTrade.equals()`;
  unconfirmed `[[TP]]`/`[[DISBAND]]` from brain output.
- **Stable identity "regulars" — Phase 1 DONE (2026-07-01), Phases 2-3 pending.** See §12b for the full
  three-phase plan.
  - ✅ **Phase 1 (identity only):** phantom populations get recurring, recognizable faces — either
    auto-generated stable slots (`regularCount="N"`, no authoring) or hand-authored `<regular>` entries,
    with a `regularChance`. Stable name → stable brain voice. Phantom is still ephemeral (not persisted,
    not a friend).
  - ✅ **Phase 2 (persistence) DONE (2026-07-01):** regulars now get a distinct account
    (`phantom_regular`) that the boot sweep (§10 fix, scoped to `phantom` only) skips, and their row is
    created once so `charId` is stable across reboots. See §12c for the full mechanism.
  - ✅ **Phase 3 (friend tier) — Phases 3a + 3b DONE (2026-07-01).** See §12d/§12e for the full mechanism.
    - ✅ **3a (befriend + friend chat):** friend-inviting a regular auto-accepts server-side
      (`RequestFriendInvite` hook → `PhantomManager.befriendRegular`, persists to `character_friends` both
      ways; the Phase 2 stable `charId` is what makes the row durable across respawns). Friend PMs to a
      regular route into the brain and reply over the friend channel (`RequestSendFriendMsg` hook →
      `FakePlayerChatManager.handleFriendMessage` → `L2FriendSay`, in the regular's stable persona, at a
      human 1-4s cadence). Stock handler edits are thin (guard + delegate); logic is in the custom managers.
    - ✅ **3b (always-online login lifecycle):** friend-regulars now spawn at their own stored location on
      the owner's `EnterWorld` (so they show "online" even away from their zone — `FriendList` checks
      `World.getPlayer` != null) and despawn on the owner's logout (`Disconnection`), handed over if another
      online friend still wants them. See §12e.
    - ⬜ **3b remainder — brain friendship memory:** persist a "we're friends" flag in `fpc_brain.py` memory
      (a `FRIEND` brain mode) so the tone reflects the relationship. Not yet done — friend PMs currently use
      `WHISPER` mode (correct persona, no explicit friend memory). Small Python-side follow-up.
  - 🟡 **Phase 3 add-on — player-crafted phantoms (user idea):** the "adopt anyone you meet" half is DONE
    via promotion-on-befriend (§12f — friend-invite any phantom and it becomes a persistent regular, no
    authoring). Still open: the "create from scratch to your own spec" half (name + appearance, maybe
    class/level — e.g. recreate an old friend) via an in-game UI/command, which then just funnels into the
    same promotion machinery. Only the authoring front-end is missing now.

---

## 11. Suggested next steps

1. **Phantom tuning config** — lift post-kill/dispersal/roam/rest/buddy thresholds into a config file so they're adjustable without a rebuild.
2. **Field behavior tuning** — smarter hunting, polygon-bounded roaming, persistent respawn identity.
3. ✅ **[DONE 2026-07-01] Haggle price clamp** — the whisper-negotiated unit price is now bounded server-side in `FakePlayerStoreFactory.clampDealPrice(...)`, wired into `dealSellStock`/`dealBuyStock` (the single choke point where the LLM-agreed price enters). Band (moderate): when the bot **sells**, price is clamped to `0.5×–3×` reference (floor stops the "sell a rare for 1 adena" exploit); when it **buys**, to `0.1×–1.5×` reference (ceiling stops "buy junk for billions"). Haggling still works inside the band; only absurd values get pinned. Applies to the final per-unit price, so the `k`/`kk` multiplier trick in the `[[SHOP:...]]` tag is neutralized too. Auto-priced initial quotes (price arg `0`) are unaffected. Band factors are a one-line tuning knob.
4. **Editor niceties** — 2-click map calibration; edit profiles/assigns in-tool. (Live reload via `//reloadfakeplayers` already done.)
5. **Buddy role expansion** — add SE (Spirit Expert) and PP (Prophet variant) archetypes with their specific buff sets.
6. **Population tuning pass** — review 26 field zones + town clusters + buddy spawn points with real play feedback.

### Raid-readiness to-do (party can engage a raid boss, but won't survive/recover yet)

Assessment: assist already targets a raid boss correctly (`RaidBoss`/`GrandBoss extend Monster`) and damage/buffs work. The party no longer bleeds members permanently (battle-res landed, see below) but is still undergeared for boss-grade healing/threat. Ordered by priority:

1. ✅ **[DONE] Corpse persistence for party bots** — a fallen recruited member is now kept as a corpse for a ~60s window (`CORPSE_GRACE`) instead of despawning ~1s after death. `tick()`/`supervise()` route death through `handleDead()`, which only releases once the window elapses (or the party/owner is gone). `Member.deadSince` tracks the window; standing back up clears it and the member rejoins.
2. ✅ **[DONE] Healer battle-res of bot members** — the res loop (`findResTarget`) raises a dead real player first, then a dead recruited bot member kept as a corpse. A clientless phantom can't answer the resurrection ConfirmDlg, so `handleDead()` auto-accepts it server-side (`reviveAnswer(1)`). Respects the res reuse timer (`isSkillDisabled`), skips a corpse whose revive is already pending so multiple healers chain-res rather than double-cast. (Personal **buddies** don't yet get corpse persistence — easy follow-up using the same pattern.)
3. ✅ **[DONE] Real tank threat/aggro loop** — `TANK` members now run an active threat tick (`maintainThreat`). It is **not** raid-gated (a tank holding aggro helps in any group fight) but is situational so it doesn't waste taunt cooldowns on trash: on a **raid boss** (`isRaid()`) it keeps threat pinned by re-taunting whenever the taunt is off cooldown; on **normal mobs** it only taunts a mob that has slipped onto a non-tank party member (`getMostHated()` is a squishy). Uses **Aggression** (id 28, single-target, 400-800 range) when the victim is in range, else falls back to **Aura of Hate** (id 18, self-centred AoE) when the tank stands in the pack. Degrades gracefully (just melees) if the tank is too low-level to know a taunt yet.
4. ✅ **[DONE] Boss-grade healing** — two fixes. (a) **Healers no longer sit to meditate mid-raid**: `restForMp` treated "nobody is hitting *me*" as safe, so once the tank held aggro both Elders sat at <30% MP and the party wiped while they meditated. A live raid now counts as a threat (`raidEngaged()`), so supports/nukers never sit during a boss fight (normal-farm "sit between pulls" is unchanged). (b) **Pre-emptive, tank-first healing** (`pickHealTarget`): under a raid an emergency (<50%) is healed first, then the **tank is kept topped to ~90%** (it soaks the boss), then the most-hurt member below **80%** — instead of the old reactive "most-hurt below 60%", which let a boss spike drop the tank from comfortable to dead before a heal landed. Multiple healers both follow this, so they stack on the tank. *Remaining lever if still tight:* MP sustain (mana potions / Elder cross-Recharge) and faster heals under a spike.
5. ✅ **[PARTIAL] Boss mechanic awareness** — generic observed-mechanic layer landed (see §5b "Raid-tactics layer — first pass"): **add/minion kill order** (DPS clear tank-held adds before the boss, lowest-HP first), **AoE/teleport step-out** (ranged + support break out of a telegraphed boss cast, life-saving heals still win), and **debuff cleanse** (Purify/Cure Poison/Cure Bleeding strip poison/bleed/paralyze/petrify, tank first, matched to what the cleanse can actually remove). All raid-gated. Still open: per-boss `RaidTactics.xml` override (kill order/healer assignment/AoE notes for the marquee bosses, optionally LLM-authored offline from the boss AI scripts) and tank cooldown policy.
6. ✅ **[PARTIAL] Party spread positioning** — nukers now spread laterally at cast range (per-member offset) so they don't stack on one tile for a boss AoE. Healers still anchor on the tank (so they cluster near it); melee inherently stack on the boss. A fuller spread (healers/melee too) is still open.
7. **[VERIFY] BossZone interactions** — recruitment spawns bots off-screen and walks them in; some boss zones gate entry/teleport and may block the walk-in. Check before relying on in-zone recruiting.

---

## 12. File map (quick reference)

| Area | Path |
|---|---|
| Chat brain (Python) | `fpc_brain.py` |
| Chat knowledge base | `knowledge/*.txt` (+ `knowledge/README.md`) |
| Chat manager | `java/.../managers/FakePlayerChatManager.java` |
| Chat handlers (runtime scripts) | `dist/.../scripts/handlers/chat/channels/Chat{Whisper,Party,Shout,Trade,General}.java` |
| Behavior manager | `java/.../managers/FakePlayerBehaviorManager.java` |
| Route recorder (GM tool) | `java/.../managers/RouteRecorder.java` |
| Route data loader | `java/.../data/xml/RouteData.java` |
| Phantom manager | `java/.../managers/PhantomManager.java` |
| Buddy manager | `java/.../managers/PhantomBuddyManager.java` |
| Party recruit manager | `java/.../managers/PhantomPartyManager.java` |
| Appearance factory | `java/.../managers/FakePlayerAppearanceFactory.java` |
| Store factory / manager | `java/.../managers/FakePlayerStoreFactory.java`, `FakePlayerStoreManager.java` |
| Appearance holder | `java/.../model/actor/holders/npc/FakePlayerAppearance.java` |
| Store packets | `java/.../network/serverpackets/FakePlayerStoreList{Sell,Buy}.java`, `FakePlayerRecipeShop*.java` |
| Render packet | `java/.../network/serverpackets/FakePlayerInfo.java` |
| NPC integration | `java/.../model/actor/Npc.java` |
| Config | `java/.../config/custom/FakePlayersConfig.java`, `dist/.../FakePlayers.ini` |
| NPC behavior data | `dist/game/data/FakePlayerBehavior.xml` (+ `xsd/FakePlayerBehavior.xsd`) |
| Named routes | `dist/game/data/routes/*.xml` |
| Phantom populations | `dist/game/data/PhantomPopulations.xml` |
| Admin commands | `dist/.../scripts/handlers/chat/commands/admin/AdminFakePlayers.java`, `AdminFpcRoute.java`, `AdminRates.java` |
| Admin HTML panels | `dist/game/data/html/admin/rates.htm`, `reload.htm` (Living World section), `server_menu.htm` |
| Handler registry | `dist/.../scripts/handlers/MasterHandler.java` |
| Visual editor | `tools/fpc-editor/index.html` + `README.md` |

Development branch: `claude/progress-readme-review-bnpov2`

---

## 12b. Stable identity "regulars" (Phase 1 — 2026-07-01)

Goal: give a zone a few recurring, recognizable faces instead of an all-random crowd every visit, so
the LLM brain (which hashes the bot name into a persistent personality via `_voice()`) sounds the same
each time and the player recognizes the character. Phase 1 is **identity only** — the phantom is still
ephemeral (fresh DB row per spawn, swept at boot); persistence and the friend tier are Phases 2-3 (§10b).

How it works — two ways to define regulars (mixable):
- **`regularCount="N"` (auto, recommended, no authoring):** at load, `generateAutoRegulars()` builds N
  regulars seeded deterministically from the population name + slot index, so the same slot is the same
  name/appearance/class on **every restart**. Reuses the shared name pools (`FakePlayerAppearanceFactory
  .generateName(Random)`, a new seeded overload) and the fighter/mage class pools, so they blend in.
  Capped at `MAX_AUTO_REGULARS` (30).
- **`<regular>` children (hand-authored):** fixed `name`, `female`, `face` 0-2, `hairColor` 0-3,
  `hairStyle` 0-2, optional `classId`. For specific characters you want to pin exactly.
- `regularChance` (default 25) = % of spawns that use a regular (authored or auto) instead of random.
- On each spawn, `createAndSpawn()` calls `pickRegular(population)`: rolls `regularChance`, and if it hits
  picks a regular whose name isn't already live (a regular never appears twice at once), else returns null
  (random identity as before). A chosen regular pins name + appearance, and its class if `classId > 0`
  (else class rolls). Buddies keep their role class regardless.
- No brain changes were needed: `fpc_brain.py` `_voice()` already derives a stable persona from the name.
  No DB schema change; nothing to persist in Phase 1.

Code touchpoints: `PhantomManager` — `Regular` inner class; `Population.regulars` / `regularCount` /
`regularChance`; parsing in `parseDocument`; `generateAutoRegulars()`, `pickRegular()`, `isMageClass()`;
the identity block in `createAndSpawn()`; constants `REGULAR_CHANCE_DEFAULT`, `MAX_AUTO_REGULARS`.
`FakePlayerAppearanceFactory` — seeded `generateName(Random)` overload.

Deploy: Java (rebuild jar) **and** copy `dist/game/data/PhantomPopulations.xml` (only if you actually
add regular entries to it — the code change is what ships in the jar).

---

## 12c. Stable identity "regulars" — Phase 2 (persistence — 2026-07-01)

Goal: give a regular a real, persistent DB row with a **stable `charId`** across reboots, so a future
friend tier (Phase 3, §10b) can reference it by id. Only regulars get this — random phantoms stay
ephemeral exactly as before (fresh row per spawn, deleted on despawn, swept as orphans at boot).

How it works:
- New account `phantom_regular` (constant `ACCOUNT_NAME_REGULAR`), distinct from the random-phantom
  account `phantom` (`ACCOUNT_NAME`). `sweepOrphanedPhantoms()` only ever queried `account_name='phantom'`,
  so it already leaves `phantom_regular` rows alone untouched — no change needed there.
- `findRegularCharId(name)`: a direct, account-scoped `SELECT charId FROM characters WHERE char_name=? AND
  account_name=?` (mirrors `sweepOrphanedPhantoms`'s query style). Scoping to `phantom_regular` means a
  real player who happens to share the regular's name is never matched. Returns 0 if the regular has never
  been spawned before.
- `createAndSpawn()`'s regular branch now checks that lookup before creating anything:
  - **First-ever spawn** (`findRegularCharId` returns 0): behaves like before, except the row is created
    under `ACCOUNT_NAME_REGULAR` instead of `ACCOUNT_NAME` (`Player.create(template, ACCOUNT_NAME_REGULAR,
    regular.name, appearance)`), then runs the normal level/skill/gear pipeline (`outfit`/`outfitBuddy`).
  - **Returning spawn** (`findRegularCharId` returns a charId > 0): `Player.load(existingId)` restores the
    same character row instead of creating a new one — **same charId**, same persisted level/class/skills.
    Its inventory is then wiped (`destroyAllItems`) and the normal `outfit`/`outfitBuddy` pipeline is re-run
    so it comes back fully kitted. This is deliberate: consumables (soulshots/potions/buff reagents) were
    spent while hunting and the auto-use/soulshot registrations are runtime-only, not persisted — skipping
    the pipeline (an earlier cut did) would return an inert regular that never casts and fights without shots.
    The wipe-then-regear avoids stacking duplicate weapons/armor. The exp/class/skill steps in that pipeline
    are guarded no-ops on an already-leveled char, made safe by two safeguards:
    - `transferClass()` is now **idempotent**: it bails if the char is already at (or past) the class tier
      its level warrants (`getPlayerClass().level() >= targetTier`). Without this, re-running it on a loaded,
      already-transferred regular would walk it *further* forward and over-advance its class.
    - the caster flag (`mage`) for a loaded regular is derived from its **restored** class
      (`isMageClass(phantom.getPlayerClass().getId())`), not the pre-load roll — so gear/combat archetype
      matches the real class even for an authored `<regular>` with no explicit `classId`.
    `enterWorld()` then runs for everyone, restoring full HP/MP/CP and spawning it into the world.
  - Random phantoms (`regular == null`) are completely unaffected: still `Player.create(template,
    ACCOUNT_NAME, nextName(), appearance)` every time.
- `despawn()` now branches on `ACCOUNT_NAME_REGULAR.equals(data.player.getAccountName())`: a persistent
  regular is saved (`data.player.storeMe()`) before `deleteMe()` (world removal) and its row is **kept** —
  the `GameClient.deleteCharByObjId(objectId)` call is skipped entirely for it. A random phantom is
  unchanged: no store, row deleted as before.

Code touchpoints: `PhantomManager` — constant `ACCOUNT_NAME_REGULAR`; new `findRegularCharId(String)`;
the load-vs-create branch (`existingId`, `loadedRegular`, `effectiveMage`, inventory wipe) in
`createAndSpawn()`; the tier guard in `transferClass()`; the `persistent` branch in `despawn()`. No DB
schema change (reuses the stock `characters` table); no XML/config change.

Deploy: Java only (rebuild jar and move it to the live server) — no other files changed.

---

## 12d. Stable identity "regulars" — Phase 3a (friend tier: befriend + chat — 2026-07-01)

Goal: let a player treat a regular like a real friend — add it to the friends list and hold a private
conversation with it — building on the Phase 2 stable `charId`. Phase 3a lands the two lower-risk halves;
the always-online login lifecycle + brain memory flag are Phase 3b (§10b), deferred.

How it works (stock packet-handler edits are deliberately thin — a guard + one delegate call each; all
logic lives in the custom managers):
- **`PhantomManager.isRegular(Player)`**: `ACCOUNT_NAME_REGULAR.equals(getAccountName())`. The account is
  server-internal, so a real player can never match — no world lookup needed.
- **Auto-accept invite** — `RequestFriendInvite` hook: a regular is clientless and can't answer the
  `FriendAddRequest` dialog, so when a player invites one we call `PhantomManager.befriendRegular(player,
  regular)` and return. That mirrors `RequestAnswerFriendInvite`'s accept: `INSERT INTO character_friends`
  both directions, add to both in-memory lists, send the "added to friends" system message + an
  `L2Friend(regular, 1)` so it shows online immediately. Placed after the stock validations (self/olympiad/
  block/already-friend), before the busy-check/dialog send.
- **Friend PM → brain** — `RequestSendFriendMsg` hook: if the receiver `isRegular`, call
  `FakePlayerChatManager.handleFriendMessage(player, regular, message)` and return. That calls the brain with
  the regular's name (mode `WHISPER`, so it gets the same stable `_voice()` persona a whisper would) and
  replies back as an `L2FriendSay` at a human 1-4s cadence (see below); falls back to a short canned line if
  the brain is offline. Note the regular is a `Player` phantom, so it is *outside* the Npc-based whisper path
  (`resolveBot` only finds Npc fake players) — this is a dedicated friend path, not the whisper one.

Why respawn-safe: the friendship lives in `character_friends` keyed on the regular's stable `charId`; when a
regular is reloaded (Phase 2 `Player.load`), `restore()` calls `restoreFriendList()`, so the reloaded
phantom recognizes the player as a friend and `RequestSendFriendMsg`'s friend check passes again.

Friend-PM cadence: `handleFriendMessage` uses a short human "read + react" pause (`FRIEND_THINK_MIN..MAX`,
~0.8-2.6s) plus the length-scaled typing time, so a reply lands in roughly 1-4s — a direct 1:1 friend chat,
not the slow 5-15s ambient-whisper delay.

(Phase 3b, §12e, removes the earlier "only online near its zone" limitation by login-spawning friends.)

Code touchpoints: `PhantomManager` — `isRegular()`, `befriendRegular()` (+ imports `SystemMessageId`,
`SystemMessage`, `L2Friend`); `FakePlayerChatManager` — `handleFriendMessage()` (+ import `L2FriendSay`);
stock `RequestFriendInvite` and `RequestSendFriendMsg` — one guarded delegate call each. No DB schema change
(reuses stock `character_friends`); no XML/config/brain change.

Deploy: Java only (rebuild jar and move it to the live server) — no other files changed.

---

## 12e. Stable identity "regulars" — Phase 3b (always-online friend lifecycle — 2026-07-01)

Goal: a befriended regular shows "online" in the friends list whenever its owner is logged in — even far
from that regular's home zone — instead of only while it happens to be spawned nearby. The online check is
`World.getPlayer(charId) != null`, so "online" literally means "spawned in the world"; 3b spawns the friend
at login and despawns it at logout.

How it works:
- **Login** — `EnterWorld` hook (after `onPlayerEnter()`) → `PhantomManager.onOwnerLogin(player)`. To keep
  the login/packet thread free it schedules the work after `FRIEND_SPAWN_DELAY` (3s), re-resolves the player
  by objectId, then for each befriended regular (`findFriendRegulars`: `character_friends` ⋈ `characters`
  where `account_name='phantom_regular'`) calls `spawnFriendRegular(charId, ownerId)` and sends the owner an
  `L2Friend(regular, 1)` to flip it online in the already-sent list. So a friend pops online a few seconds
  after you do — like a real friend logging in behind you.
- **`spawnFriendRegular(charId, ownerId)`**: `Player.load(charId)` (stable Phase 2 charId), spawn at the
  regular's own stored `x/y/z` (Geo-snapped), then the shared `finishSpawn(...)` (extracted from
  `createAndSpawn`) gears + world-drops it and starts the auto-hunt. No-op if it is already live (a population
  or a prior login already spawned it — dedup by charId in `_phantoms`) or the phantom cap is hit. The
  `PhantomData.friendOwnerId` field tags it with the owner for logout despawn.
- **Logout** — `Disconnection.storeAndDelete` hook (before the player's `deleteMe()`) →
  `PhantomManager.onOwnerLogout(player)`: despawns every `_phantoms` entry tagged with that `friendOwnerId`
  (despawn = Phase 2 store + keep row). If another online player is also friends with that regular
  (`otherOnlineFriendOf`), it is handed over (re-tag `friendOwnerId`) instead of despawned.

Interactions/edge cases:
- **Dedup vs population spawns:** a friend-regular and a population-spawned regular are the same charId;
  whichever spawns first wins (`_phantoms.containsKey(charId)` / `pickRegular` excludes live names), so it is
  never doubled. A population-spawned one has `friendOwnerId=0`, so the population owns its despawn; a
  login-spawned one is despawned on logout.
- **Death:** a friend-regular has `population==null`, so the supervisor's ad-hoc branch revives it in place
  (stays online) rather than rotating its identity.
- **Solo-server scope:** this is an offline/solo "Living World" (effectively one real player). The handover
  guard covers a second online friend, but complex multi-owner sharing is not otherwise specially handled.

Code touchpoints: `PhantomManager` — `onOwnerLogin()`, `onOwnerLogout()`, `spawnFriendRegular()`,
`findFriendRegulars()`, `otherOnlineFriendOf()`, extracted `finishSpawn()`, `PhantomData.friendOwnerId`,
constant `FRIEND_SPAWN_DELAY`; stock `EnterWorld` (login hook) and `Disconnection` (logout hook), one
delegate call each. No DB schema change; no XML/config/brain change.

Deploy: Java only (rebuild jar and move it to the live server) — no other files changed.

---

## 12f. Promotion-on-befriend: ANY phantom becomes a regular when friended (2026-07-01)

Goal (user decision): **no XML authoring needed for regulars at all.** Friend-inviting *any* live phantom
(field hunter, recruited party member) auto-accepts AND **promotes it on the spot** into a persistent
regular — its row flips to the `phantom_regular` account (`UPDATE characters SET account_name`), so its
name/look/class/charId become permanent, the boot sweep skips it, despawn keeps it, and the Phase 3b
always-online lifecycle picks it up immediately. "You liked this one — now it's a character."

Key mechanics / safeguards (all verified against source):
- `Player._accountName` is **final** — a live instance can't change accounts in memory. The `_promoted` set
  (objectIds) bridges that: `isRegular()` = account check OR `_promoted`. After any reload the DB account
  covers it; `despawn()` clears the set entry.
- **Durable**: `Player.storeMe()`'s `UPDATE_CHARACTER` does NOT include `account_name`, so nothing ever
  reverts a promotion; and since the DB flip happens at invite time, even a hard kill can't let the boot
  sweep eat a befriended friend.
- **Kept online with you**: `befriendPhantom` registers the charId in `_friendRegularsByOwner`; a throttled
  (15s) supervisor pass (`ensureFriendRegulars`) respawns any missing friend-regular while its owner is
  online (covers death, population despawn, failed spawns). It also **prunes** ids the owner has since
  friend-deleted (stock `RequestFriendDel` updates the in-memory list), so an ex-friend isn't resurrected.
- **Friend-deleted regulars are cleaned at boot**: second sweep pass removes `phantom_regular` rows with no
  `character_friends` reference. (Unbefriended XML/auto regular rows get swept too — harmless: their
  identity is deterministic from seed; only the unreferenced charId changes.)
- **Buddies are declined** ("too busy working to add friends"): a befriended buddy would login-spawn as a
  hunter next to its own replacement at the post. Proper buddy-befriending is a follow-up.
- **Recruited party members are the prime use case** — `despawnRecruit` routes through `despawn()`, whose
  persistence check now uses `isRegular()`, so a promoted recruit's row survives party disband.
- Spawn log now distinguishes `regular` / `friend-regular` / `phantom` / buddy, so testing is readable.

Known rough edge: a promoted **support-class** recruit (Bishop/PP/etc.) re-spawns geared as a melee fighter
(`isMageClass` only knows the DD-mage pool) — functional, looks off. Follow-up if it matters.

Code touchpoints: `PhantomManager` — `_promoted`, `_friendRegularsByOwner`, `_lastFriendEnsure`;
`isPhantom()`, `befriendPhantom()` (replaces `befriendRegular`), `ensureFriendRegulars()`, supervise ensure
pass, `despawn()` persistence via `isRegular()`, boot-sweep second pass, spawn-log kind. Stock
`RequestFriendInvite` — hook now fires for any phantom. No DB schema change.

Deploy: Java only (rebuild jar and move it to the live server) — no XML changes needed anymore.
