# FPC Editor

A standalone visual editor for `data/FakePlayerBehavior.xml` (fake player populations).
No install, no build, no server — it's a single HTML file that runs in your browser.

## Run it
Double-click `index.html` (or right-click → Open with → your browser).

## Workflow
1. **Load XML** — pick your `FakePlayerBehavior.xml`. All `<population>` groups appear as
   circles on the map (circle = anchor + radius). Profiles, assigns and the default are
   preserved untouched.
2. **Landmarks** — towns and villages (Giran, Aden, Dion, the starting villages…) are drawn as
   purple diamonds at their real coordinates, so you can orient and place bots relative to them
   **without needing a map image at all**. Toggle them with the *landmarks* checkbox.
3. **Load geodata** (recommended) — point it at your server's `game/data/geodata` folder. It parses
   the `.l2j` files in the browser and renders the actual world map (height relief; towns/dungeons
   tinted) **behind** the landmarks, perfectly aligned to coordinates — no calibration, no external
   image. Parsing a full pack takes a few seconds. This is the easiest way to see the real world.
4. *(optional)* **Map image** — alternatively, if you have a community L2 world-map image, load it as a background, then open
   **Map calibration** and nudge the `minX/minY/maxX/maxY` world bounds until landmarks line
   up with the population circles. (Only X/Y matter — with geodata loaded the server snaps Z
   to the ground.)
3. **Edit:**
   - **Double-click** the map to drop a new population there.
   - **Drag** a circle to move it; the X/Y update live.
   - **Click** a circle (or a list row) to edit its fields: count, level range, profile,
     race, store type (`SELL`/`BUY`/`PACKAGE`), respawn.
   - **Wheel** to zoom, **drag empty space** to pan.
4. **Save XML** — downloads an updated `FakePlayerBehavior.xml`. Drop it into your live
   server's `game/data/` and restart (or reload).

## Colors
- blue = town loiterers/default · green = movers (mill/stroller) · purple = visit
- red = field hunters/farmers · gold = private-store vendors

## Notes
- The editor only edits **populations** (the spatial groups). Movement **profiles**,
  per-bot **assigns** and the **default** are read and written back unchanged — edit those
  by hand in the XML if needed.
- The exported file keeps the `xsd/FakePlayerBehavior.xsd` reference, so server-side schema
  validation stays clean.
