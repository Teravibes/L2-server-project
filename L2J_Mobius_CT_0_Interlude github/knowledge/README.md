# LLM Knowledge Base (chat grounding)

Plain-text fact files that ground the chat brain (`fpc_brain.py`) so bots answer with real
Lineage 2 **Interlude** facts (zones, level ranges, classes, buffs, item shorthand) instead of
inventing them. Adapted from the tag-fact idea in the `l2-smartbot` project, corrected for Interlude.

## How it works

On startup `fpc_brain.py` loads every `*.txt` file in this folder. For each incoming chat, it scores
facts by how many of their **tag tokens** appear in the player's message and injects the top few into
the system prompt under a "Game facts you can rely on" block. When nothing matches, nothing is injected
— so chat behaves exactly as before for unrelated messages.

Used by these brain modes: WHISPER, SAY, SHOUT, BUDDY (all facts), and TRADE / ITEM (filtered to
`item` and `buff` facts for canonical item-name resolution).

## File format

One fact per line:

```
[tag tokens here] The sentence the bot can rely on and will not contradict.
```

- The bracketed part is the **searchable tags** (lowercase words/numbers), not shown to the player.
- The text after the `]` is what gets injected into the prompt.
- Lines starting with `#` and blank lines are ignored.
- For locations, include `level <lo> <hi>` in the tags — a level number in the player's message that
  falls inside that band gets a relevance boost (e.g. "where at 40?" → zones covering 40).

Example:

```
[location level 35 50 cruma tower giran] Cruma Tower near Giran is a multi-floor dungeon around levels 35-50.
```

## Current files

| File | Covers |
|---|---|
| `00_general.txt` | Towns, gatekeepers, warehouses, shops, currency, grade basics |
| `10_locations.txt` | Hunting zones by level range |
| `20_buffs.txt` | Buffs and the buffer classes (PP / EE / SE / WC, etc.) |
| `30_roles.txt` | Classes and combat roles per race |
| `40_party_combat.txt` | Party composition and combat basics |
| `50_items.txt` | Shot/scroll/material shorthand and trade slang |

## Editing

Add or correct lines and restart `fpc_brain.py` (it reloads the folder on boot). Keep each fact short
and true; the goal is to keep bots from inventing specifics, so only add facts you trust.
