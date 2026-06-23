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
- **Click** a circle (or a list row) to edit its fields: name, count, level range, profile,
  race, store type (`SELL`/`BUY`/`CRAFT`/`PACKAGE`), respawn.
- **Draw zone** — select a population, click **Draw zone**, then click points on the map to
  outline an area. Bots spawn **inside the shape** instead of a circle (great for sell/buy/craft
  shop districts). Drag vertices to adjust; double-click or **Esc** to finish; **Clear zone** to
  revert to a circle. Saved as `<point>` vertices under the population.
- **Wheel** to zoom, **drag empty space** to pan. The geodata map is crisp when zoomed in.
- **Landmarks** — towns/villages are drawn as purple diamonds at their real coordinates so you
  can orient without a map image. Toggle them with the **landmarks** checkbox.
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

## Movement profiles (what bots *do*)
A **profile** is the behavior assigned to a population — it decides how those bots move. You can
now create and edit profiles directly in the editor (the **Profiles** section below the population
list), instead of hand-editing the XML.

Profile types:
- **WANDER** — bots drift around randomly within a **radius** of their spawn. Use for loiterers and
  idlers standing around town.
- **VISIT** — bots tour a set of **waypoints**, picking the next one at **random** each time, with
  long idles on arrival so it looks purposeful. This is the "wander between landmarks" behavior.
- **PATROL** — same waypoints, but walked **in order**, looping the route like guards/travellers.
- **FARM** — bots seek out and fight nearby monsters within the radius (field hunters).

Each profile also has **run/walk** and a **pause** range (seconds idled between moves).

### Assigning a profile to a population
Select a population and type (or pick) a profile name in its **profile** field. Every bot in that
population will use it. Multiple populations can share one profile.

### Drawing a route (waypoints)
Two ways:
1. **From a population** — select it and click **✦ Add / edit route**. The editor creates a VISIT
   profile named `route_<population>`, assigns it, and drops you into waypoint mode. Click the map to
   place each waypoint the bots should visit; drag a waypoint to move it. **Esc** or **Finish** ends.
2. **From the Profiles section** — **+ Profile**, set type to VISIT or PATROL, then **✦ Add waypoints**.

Routes are drawn on the map as numbered, dashed purple chains; the selected route is highlighted.
Waypoint z is seeded from the population's elevation so the server snaps each point to the right
ground layer.

## Colors
- blue = town loiterers/default (WANDER) · green = movers (mill/stroller) · purple = routes (VISIT/PATROL)
- red = field hunters/farmers (FARM) · gold = private-store vendors · orange = the selected route

## Notes
- Profiles, populations, **and** waypoints are all editable in-tool now. Per-bot **assigns** and the
  **default** entry are still read and written back unchanged (edit those by hand if needed).
- The exported file keeps the `xsd/FakePlayerBehavior.xsd` reference, so server-side schema
  validation stays clean.
