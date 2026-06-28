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

- **Orc Warcryer torso** — can render with no torso (separate magic tunics have no orc model); robe-only gearing attempt reverted, to be revisited.
- **OOM-mage kite edge case** — if a same-speed mob never lets the mage break off, it keeps retreating and could die instead of resting.
- **Field hunting feel** — bots can still cluster / move sluggishly in some zones; pathfinding + combat tuning deprioritized.
- **Map image calibration** — must be supplied by user; calibration is bounds-based (a friendlier 2-click calibration was discussed but not built).
- **Buddy on disband** — despawns where it stands; could walk/TP back to town first (deferred).
- **Shout LFM** — now actually recruits a party (System E). Remaining rough edges: combat roles below ~lvl 20 fall back to a base fighter/mage (no role class yet); archer/dagger soulshot auto-fire depends on the swapped weapon registering shots (archers now also carry grade-matched arrows so the bow actually fires); melee assist re-issues `ATTACK` so very fast target-swapping by the leader can look twitchy; recruits despawn where they stand on disband.
- **Phantom/buddy tuning constants** — heal/MP/buff-refresh/roam/dispersal values are constants in the manager files; should be lifted into a config file for runtime tuning.

---

## 11. Suggested next steps

1. **Phantom tuning config** — lift post-kill/dispersal/roam/rest/buddy thresholds into a config file so they're adjustable without a rebuild.
2. **Field behavior tuning** — smarter hunting, polygon-bounded roaming, persistent respawn identity.
3. **Haggle price clamp** — bound the negotiated price server-side (currently trust-based on LLM prompt).
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
