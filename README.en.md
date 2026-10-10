# BetterLocate

A small mod that enhances the vanilla `/locate` command. On top of the original functionality, it adds three optional parameters, improves search progress feedback, and uses a faster search algorithm.

## How the vanilla locate algorithm works

- Vanilla `/locate structure <structure>` works on the placement grid. Each structure set has a set of parameters; the game uses the world seed as a salt for randomization to determine which chunks are potential structure starts. During a search, it expands outward ring by ring from the chunk containing the center, checking only the candidate chunks on each ring, and returns the first valid structure found — so it usually reports "the nearest one." The default search radius is 1600 blocks.

- Vanilla `/locate biome <biome>` places checkpoints centered on the player at intervals of 32 blocks horizontally and 64 blocks vertically, checks whether each position is the target biome, then returns the nearest one, searching up to 6400 blocks by default.

- Vanilla `/locate poi <poi>` looks up the POI index for the nearest point of interest within 256 blocks.

- The vanilla locate command only outputs coordinates and distance. Clicking the coordinates fills `/tp` into the chat input box.

## How this mod's algorithm works

- Biome sampling is pure computation, so it can be executed in parallel per chunk slice.

- Structure search is split into two steps: the first step computes in parallel, on a background thread, which chunks could generate the structure; the second step validates each candidate chunk one by one using the vanilla `findNearestMapStructure`.

- POI search follows the vanilla `PoiManager`; the mod only implements conditional filtering, sorting, and statistics.

## New parameters added by this mod

All of the following parameters are optional, and their order does not matter.

- `radius <blocks>`: allowed range is 1–100000. Within the specified radius: if the target is a biome, it counts the number of chunks it occupies and reports the coordinates of the nearest chunk; if the target is a structure or POI, it counts the results and lists coordinates with visited status, ordered from nearest to farthest. If omitted, the search range is 6400 blocks, same as vanilla.

- `center <x y z>`: specifies the coordinates of the search center. If omitted, the player's current coordinates are used, same as vanilla.

- `chunks`: `visited` / `unvisited` / `any`: specifies whether chunks have been visited by the player. `visited` only looks at chunks the player has visited, `unvisited` only looks at chunks the player has never visited, and `any` does not filter. If omitted, it defaults to `any`, same as vanilla.

## Safe teleport

This mod adds the `/safetp` command, which finds a safe teleport height near the specified coordinates — avoiding teleports that leave you inside blocks, falling from a height, or landing in fluid or powder snow.

```
/safetp [target selector] [x y z] [top/bottom] [onwater] [next] [normal/force] [radius]
```

- The target selector can be omitted and defaults to the executor; the coordinates can be omitted and default to the target entity's current position.

- Coordinates use vanilla notation, supporting both absolute and relative coordinates such as `~ ~ ~`; pressing Tab fills in the current coordinates at once.

- `top` searches downward from the build limit; `bottom` searches upward from the minimum build height. If omitted, the entity's last-used direction is kept; if there is no record, `top` is used.

- `onwater` also accepts the water surface as a valid teleport spot. Ocean biomes contain nothing but water down to the seabed, so landing directly on the ocean floor leaves you submerged; with `onwater` you land on the surface first, and can then use `/safetp next` to continue searching downward for a truly safe spot. Without it, the water surface does not count as a teleport spot. Once specified, it is carried over by subsequent `next` calls. The [safe teleport] button in `/locate` ocean-biome results adds this parameter automatically.

- `next` continues the search from the previously found position. With `top` it continues downward; with `bottom` it continues upward.

- `normal` only teleports to a safe position; `force` allows teleporting to a fallback position that meets basic standing requirements when no safe position is found.

- `radius` is the search radius on the X/Z plane, defaulting to 3. The search starts at the center column and checks columns in progressively larger square rings.

Examples:

```
# Find a safe position near the target coordinates
/safetp 114 64 514 top normal 3

# Use the current position's X/Y/Z and search top-down
/safetp ~ ~ ~ top normal 3

# Continue searching downward for the next position
/safetp next
```

## Usage examples

The following examples behave identically to vanilla.

```
# Nearest plains village
/locate structure minecraft:village_plains

# The above is equivalent to:
/locate structure minecraft:village_plains radius 1600 center ~ ~ ~ chunks any

# Nearest desert
/locate biome minecraft:desert

# The above is equivalent to:
/locate structure minecraft:village_plains center ~ ~ ~ radius 6400 chunks any

# Nearest armorer job site
/locate poi minecraft:armorer

# The above is equivalent to:
/locate structure minecraft:village_plains chunks any center ~ ~ ~ radius 256
```

The following examples use the parameters added by this mod.

```
# All plains villages within a 3000-block radius
/locate structure minecraft:village_plains radius 3000

# Count how many plains chunks are within a 2000-block radius
/locate biome minecraft:plains radius 2000

# Count desert chunks within 3000 blocks among already-visited chunks
/locate biome minecraft:desert radius 3000 chunks visited

# Search for villages in never-visited chunks
/locate structure minecraft:village_plains radius 1500 chunks unvisited

# Search for armorers in visited chunks within 300 blocks of (0,64,0)
/locate poi minecraft:armorer center 0 64 0 radius 300 chunks visited
```

A sample output looks like this:

```
Searching for structure minecraft:village_plains ...
Search complete: found 5 minecraft:village_plains within a 2000-block radius centered at (0, 77, 0) (took 812 ms)
2 of them are in [visited] chunks, 3 in [unvisited] chunks
  #1  (288, ~, 1984)  distance 2006.3 blocks  [visited]    [safe teleport]
  #2  (256, ~, -2560) distance 2573.9 blocks  [unvisited]  [safe teleport]
  ...
```

---

## Notes on visited-chunk statistics

- Visited-chunk statistics are computed by reading the 4KB header table of the save's region files. Chunks the player has never visited have no corresponding record in the save, which is how a chunk's visited status is determined.

- Biome and POI searches are read-only; searching for them does not write chunk storage to the map, so they do not affect visited status.

- For structure searches, validating a candidate chunk loads/generates that chunk, so any candidate chunk that gets validated will no longer be [unvisited]. This is an inherent cost of how vanilla implements the locate command and is unrelated to this mod's own checks.

- Specifically, when the mod detects a structure search with a `chunks` parameter other than `any`, it creates a "visited index": it first reads the save header table, then filters chunks one by one — checking placement rules first, then biome/terrain (this is the step that generates chunks). After filtering, results are labeled by the visited status recorded in the index. Because the index was built before chunk generation, structures found by that search are reported as [unvisited]; when the chunks generated during the search are unloaded by the server, they are written to the save, so the next search will report them as [visited].

- Whenever a player searches for any structure by any means — even without specifying the `chunks` parameter — as long as a candidate chunk reaches the biome/terrain check, the visited status of that structure will become [visited] once the search ends. Any other structures in that same chunk (even ones that were not the search target) will also report as [visited] in later searches.
