# FPC Editor

A standalone visual editor for `data/FakePlayerBehavior.xml` (NPC fake-player populations) **and
`data/PhantomPopulations.xml`** (real-Player phantom populations). No install, no build, no server —
it's a single HTML file that runs in your browser.

## NPC fake players vs Phantoms (mode switch)
The two buttons at the top of the side panel switch what you're editing:

- **NPC fake players** → `FakePlayerBehavior.xml` (the original NPC-rendered crowd: profiles, race,
  store type, etc.).
- **Phantoms** → `PhantomPopulations.xml` (real `Player` phantoms that farm via the engine's auto-hunt:
  just count, level range and respawn — no profile/race/store).

Switching modes **hides the other set** and shows only the active one (drawn in a distinct teal), so
you place each cleanly. The **map, geodata and landmarks stay loaded** across both modes, so you can
position phantom zones against the same world view. **Save** writes whichever mode you're in to its own
file; switch and Save again to write the other.

## Run it
Double-click `index.html` (or right-click → Open with → your browser).

## Quick start — one folder, one save
1. Click **📂 Open data folder** and pick the folder that holds your fake-player files —
   usually your server's `game/data`. The editor scans it and loads:
   - `FakePlayerBehavior.xml` (the populations),
   - the `geodata/*.l2j` tiles (the real world map),
   - the image catalog from your map-image folders.
2. The folder is **remembered**. Next time you open the editor it **reloads that folder
   automatically** — no clicking, no re-picking. Use **change…** (next to the folder name)
   to point it somewhere else.
3. Edit your populations (see below), then click **💾 Save** to write the XML back to that
   same folder. Restart/reload the server to apply.

The status chips at the top always show what's loaded or cataloged:
**XML ✓ · Geodata ✓ · Images catalog**.

> Saving directly back to the file needs Chrome/Edge (File System Access API). In Firefox the
> same **Open data folder** button reads the folder, and **Save** downloads the updated XML.

## Editing populations
- **Double-click** the map to drop a new population there (or **+ Population**).
- **Drag** a circle to move it; the X/Y update live.
- **Click** a circle (or a list row) to edit its fields: name, count, level range, **behavior**,
  race, respawn.
- **Wheel** to zoom, **drag empty space** to pan. The geodata map is crisp when zoomed in.
- **Landmarks** — towns and villages are drawn as purple diamonds at their real coordinates, and
  zooming in reveals key NPC spots (gatekeeper, warehouse, shops, …) as small labeled dots so you
  can drop route waypoints right onto them. Toggle them with the **landmarks** checkbox.
- **👁 / 🚫** on each list row shows/hides that population; **Hide all / Show all** toggles every one.

## Map image layers
Map images are loaded intentionally from three sources after you open the data folder:

- **Load world map** loads the detected world-map image, then changes to **Hide world map** /
  **Show world map**. Use a filename like `world map.png` or place it in a `World Map` folder.
- **Cities** reads images from a folder named `Cities`. Its dropdown starts with **Load all**, then each image name.
- **World locations** reads images from a folder named `world locations`. Its dropdown starts with **Load all**, then each image name.

Turn on **edit images** to drag/resize loaded image layers. Their positions are saved into
`fpc-map-images.json` when you click **Save**, and the next load applies the saved placement
when you load that world map, city, or world-location image again. You can still drag-and-drop
loose image files onto the map for quick manual overlays. Loaded city/world-location images appear
in a list with per-image show/hide toggles plus **Hide all / Show all**.

## Behaviors (what each population does)
Every population has one **behavior**, chosen from the dropdown in its panel. The editor writes the
matching movement profile into the XML for you — you never edit profiles by hand:

- **Idle / loiter** — bots drift around at random within the **radius** (or the **drawn zone**) of
  their spawn. The standing-around-town crowd.
- **Runner** — bots roam a **route** of waypoints you place, picking the next one at **random**.
  This is the "wander between landmarks" behavior. Click **✦ Draw route**, then click the map to
  drop each waypoint; drag one to move it; **Esc** to finish; **Clear route** to start over.
- **Patrol** — same routes, but walked **in order** and looped, like guards or travellers.
- **Farm** — bots seek out and fight monsters within the radius (field hunters). Tick **respawn**.
- **Shop** — private-store vendors. Pick a **store type** (`SELL`/`BUY`/`CRAFT`/`PACKAGE`); they
  barely move around the spawn.

Each behavior comes with sensible run/walk and pause defaults; tweak them under **Movement timing
(advanced)** if you want.

### Areas: radius or a drawn zone
- **Draw zone** — select a population, click **Draw zone**, then click points on the map to outline
  an area. Bots spawn **inside the shape** instead of a circle (great for Idle crowds and shop
  districts). Drag vertices to adjust; double-click or **Esc** to finish; **Clear zone** reverts to
  a circle. Saved as `<point>` vertices under the population.

### Drawing a route
Select a Runner or Patrol population and click **✦ Draw route**, then click the map to place each
waypoint. Routes are drawn as numbered, dashed purple chains anchored to the spawn (the selected
one turns orange). Waypoint z is seeded from the population's elevation so the server snaps each
point to the right ground layer.

## Colors
- blue = Idle / loiter · purple = Runner / Patrol routes · red = Farm (field hunters)
- gold = Shop vendors · orange = the selected route

## Notes
- You work entirely in **populations + behaviors**; the editor generates the underlying `<profile>`
  for each population on save (named `mv_<population>`) and reads it back on load. Any **assign** and
  **default** entries already in the file are preserved verbatim.
- The exported file keeps the `xsd/FakePlayerBehavior.xsd` reference, so server-side schema
  validation stays clean.
