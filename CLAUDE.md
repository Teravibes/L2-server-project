# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

A fork of **L2J Mobius CT_0 Interlude** (a Lineage 2 game-server emulator) turned
into an offline/solo "Living World": you log in and the server feels populated —
towns full of NPCs, private shops, trade chat, field hunters, and recruitable
combat parties — without other humans online.

Two halves work together:

- **Java game server** (`L2J_Mobius_CT_0_Interlude github/java/`) — the L2J Mobius
  core plus custom `FakePlayer*` / phantom / recruited-party systems.
- **Python "brain"** (`L2J_Mobius_CT_0_Interlude github/fpc_brain.py`) — a Flask
  service the Java server calls over HTTP to generate in-character bot dialogue via
  an LLM (DeepSeek API or local Ollama).

## READ THIS FIRST: handover + progress docs

Three docs, distinct roles — read them in this order at session start:

1. **`HANDOVER.md`** (repo root) — a short, always-current snapshot: what just
   happened, current state, what's next, in-flight gotchas. Read this first to get
   caught up fast without re-reading everything.
2. **`L2J_Mobius_CT_0_Interlude github/PROGRESS.md`** — the full living handoff log:
   what's built, what works, known issues, next steps, file map, admin commands.
   Follow its dated "Latest Progress Update" pattern when you land a change.
   (`PROGRESS_old.md` is archived — ignore unless asked.)
3. **This `CLAUDE.md`** — static onboarding (architecture, build, conventions).

### Standing rule: update HANDOVER.md on every commit

**Every commit must also update `HANDOVER.md`** so the next session starts caught up.
Keep it short (a snapshot, not a log — that's PROGRESS.md's job): refresh
*Last updated*, *Branch*, *Current state*, *What was just done*, *In flight / next up*,
and *Watch out for*. Land the HANDOVER.md edit in the **same commit** as the change.

## Repository layout

```
L2J_Mobius_CT_0_Interlude github/   # the server project (note the space + "github" in the name)
  java/org/l2jmobius/                # game/login server source (JDK 25 + Ant build)
  dist/game/data/scripts/           # runtime-compiled datapack scripts (no jar rebuild needed)
  dist/game/config/                 # server .ini config (Custom/FakePlayers.ini, Rates.ini, ...)
  fpc_brain.py                      # Python Flask LLM brain (port 5000)
  knowledge/*.txt                   # brain knowledge base (game lore/locations/buffs/roles)
  memory/fpc_memory.json            # brain's persistent player-global memory
  tools/fpc-editor/index.html       # visual editor for bot zones/routes (open in browser, no build)
  setup_brain.sh / setup_brain.bat  # one-step brain setup+launch (Ollama path)
  build.xml                         # Ant build
  PROGRESS.md                       # <-- read this first
fpc data/                           # geodata, world map image, city data for the editor
```

## Key custom systems (all detailed in PROGRESS.md)

- **A — LLM Chat Brain**: `fpc_brain.py` + `managers/FakePlayerChatManager.java`.
  Bots hold whisper/say/trade/shout conversations; guardrails ("constitution")
  keep them in-character and injection-resistant.
- **B — NPC Fake Players**: data-driven town/hunting NPCs with procedural identities
  and functional private shops (`FakePlayerBehaviorManager.java`, `FakePlayerStoreFactory.java`).
- **C — Real-Player Phantoms**: clientless `Player` objects that auto-hunt field zones.
- **D — Personal Support Buddies**: a phantom you party as your own buffer/healer.
- **E — Recruited Combat Parties**: shout LFM/LFP and a level-matched party spawns,
  walks over, and joins you.

## Build & deploy

Deploy depends on *what* changed (see PROGRESS.md §7 for the full table):

| Changed | How to deploy |
|---|---|
| `java/**` | Ant build (`ant`) → copy `GameServer.jar` to live server |
| `dist/game/data/scripts/**` | Copy to live datapack — runtime-compiled, no jar |
| XML behavior/routes/config | Copy to live `game/data/` (or `game/config/Custom/` for `FakePlayers.ini`) |
| `fpc_brain.py` | Restart the Python process |
| Editor `index.html` | Just open in a browser |

**Build caveat (important):** the Ant build needs **JDK 25 + Ant** and does **not**
run in this dev environment (Java 21, no Ant). So **Java changes are hand-verified,
not compiled here.** Be extra careful with imports/signatures/types; a build error
is almost always a quick import or signature fix. Do not claim a Java change
"builds" — say it's hand-verified.

## Running the Python brain

```bash
cd "L2J_Mobius_CT_0_Interlude github"
./setup_brain.sh                 # Ollama path: installs Ollama, pulls model, venv, launches
# or manually:
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt  # flask, openai, python-dotenv
python fpc_brain.py              # serves http://127.0.0.1:5000
```

Config via `.env` (git-ignored): `PROVIDER=deepseek` (needs `DEEPSEEK_API_KEY`) or
`PROVIDER=ollama` (`OLLAMA_MODEL`, default `llama3.1`). Endpoint: `POST /chat`.

## Conventions & gotchas

- The project directory name **`L2J_Mobius_CT_0_Interlude github` contains a space** —
  always quote paths in shell commands.
- Match L2J Mobius house style in Java (existing package layout, naming, formatting).
  Custom code lives alongside stock code under `org.l2jmobius.gameserver.*`.
- `.env`, `.venv`, `build/` are git-ignored. `memory/fpc_memory.json` is player-global
  on purpose (generated bot names are random across restarts).
- In-game admin tooling for testing changes without a full restart: `//reloadfakeplayers`,
  `//reload fakeplayerchat`, `//rates`, `//phantom ...`, `//record_route` (PROGRESS.md §8).

## Model workflow (preferred)

Use the strongest model where judgment matters and a faster one for the grind:

1. **Plan on Opus 4.8** — design in plan mode; present the plan and get approval
   before editing anything non-trivial.
2. **Implement on Sonnet** — either switch with `/model claude-sonnet-5`, or (better)
   stay on Opus as lead and delegate the implementation to a **Sonnet subagent** so
   context isn't lost.
3. **Review on Opus 4.8 before commit** — run `/code-review` (and `/security-review`
   when relevant) on the diff; only commit after it passes.

Note: model choice is per-session and resets to the default in a new session —
re-establish this rhythm each session.

## Cost / usage awareness

Claude cannot see the user's plan usage or the 5-hour rate-limit state — there's no
tool for it, so never claim to be "monitoring" it or predict when a request crosses
the limit. The user checks real usage with `/usage` and `/status`. What Claude should
do instead: **flag up front when a request is inherently heavy** (many files, full
review pass, large refactor) so the user can decide before it starts, and prefer the
cheaper path (Sonnet subagent for mechanical work, batch edits, avoid needless
re-reads).

## Ask, don't guess

- **Big changes: always ask first.** Before a large or far-reaching change (many
  files, a refactor, anything touching architecture or stock Mobius core), present
  the plan and get approval before editing.
- **Not sure? No guessing.** If something is unclear, verify it — read the relevant
  code, check online/docs, and/or ask the user. Don't invent behavior, APIs, or
  signatures.

## Workflow expectations

- Development happens on the designated feature branch. Commit with clear messages;
  don't open a PR unless explicitly asked.
- Keep `PROGRESS.md` current — it's how this project stays handoff-ready.
