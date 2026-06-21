# FPC Editor

A standalone visual editor for `data/FakePlayerBehavior.xml` (fake player populations).
No install, no build, no server — it's a single HTML file that runs in your browser.

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

## Colors
- blue = town loiterers/default · green = movers (mill/stroller) · purple = visit
- red = field hunters/farmers · gold = private-store vendors

## Notes
- The editor only edits **populations** (the spatial groups). Movement **profiles**,
  per-bot **assigns** and the **default** are read and written back unchanged — edit those
  by hand in the XML if needed.
- The exported file keeps the `xsd/FakePlayerBehavior.xsd` reference, so server-side schema
  validation stays clean.
