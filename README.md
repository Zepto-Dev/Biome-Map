# Biome Map

One command maps every biome and structure around you, modded ones included, into an interactive map
you open in your browser. For Minecraft 1.20.1 on Forge, NeoForge and Fabric. Versions for Minecraft
1.21.1 and 1.21.11 (NeoForge and Fabric) are on this repository's `main` and `1.21.11` branches.

The mod adds a `/biomemap` command that asks your world's real generator which biome goes where and
which structures generate, then saves:

- a **PNG** of the biomes (one pixel per sample), and
- an **interactive HTML map** you open in your browser. You can pan, zoom, hover for biome and
  coordinates, highlight biomes, show or hide structures by mod and type, jump to the nearest one,
  and click to copy a `/tp` command.

Because it runs inside your game with your mod list, modded biomes (TerraBlender mods like Biomes
O' Plenty and Regions Unexplored, Terralith, and so on) and modded structures (Repurposed
Structures, Dungeons and Taverns, and so on) show up exactly where they will generate.
It never generates or loads chunks, so it doesn't change your world.

## Install

Copy the jar for your mod loader into your instance's `mods` folder:

| Loader | Jar | Also needs |
| --- | --- | --- |
| Forge 47 (any Forge for 1.20.1) | `biomemap-forge-1.20.1-1.1.1.jar` | nothing else |
| NeoForge for 1.20.1 (47.1) | `biomemap-forge-1.20.1-1.1.1.jar`, the same jar | nothing else |
| Fabric Loader 0.14.21 or later | `biomemap-fabric-1.20.1-1.1.1.jar` | [Fabric API](https://modrinth.com/mod/fabric-api) |

NeoForge started as a fork of Forge 47.1, and for 1.20.1 it still runs Forge mods, so one jar covers
both. All versions do exactly the same thing and make the same maps.

## Build the jars

To build the jars yourself you need a Java Development Kit (JDK) 17 or newer. Gradle downloads JDK 17,
which Minecraft 1.20.1 uses, if it isn't installed.

```
gradlew build          (Windows: gradlew.bat build)
```

The first build downloads Minecraft, Forge and Fabric and takes a few minutes. The jars end up in
`forge/build/libs/biomemap-forge-1.20.1-1.1.1.jar` and `fabric/build/libs/biomemap-fabric-1.20.1-1.1.1.jar`.

## Use it

1. **Create a world with the seed you want to preview.** A throwaway singleplayer world is fine,
   and cheats can stay off: the world's owner can always use the command in singleplayer. On a
   server you need operator permission (level 2).
2. Run `/biomemap`. Progress shows above your hotbar.
3. When it finishes, click **[Open map]** in chat.

The files go to a `biomemaps` folder in your game directory, next to `mods` and `saves`.

| Command | What it does |
| --- | --- |
| `/biomemap` | Surface biomes and structures in a 4096 × 4096 block square around you |
| `/biomemap <radius>` | Square from your position minus `radius` to plus `radius` |
| `/biomemap <radius> <step>` | `step` = blocks per pixel (a multiple of 4). Left out, it's chosen to give about 1024 × 1024 pixels |
| `... surface` | Surface biomes, fast (the default) |
| `... exact` | Surface biomes at the real terrain height. Much slower, so it defaults to about 512 × 512 pixels |
| `... y <level>` | Biomes at one height, e.g. `y -40` for lush caves, dripstone caves and the deep dark |
| `/biomemap at <x> <z> <radius> ...` | Centered on given coordinates instead of you |
| `/biomemap cancel` | Stops a map that is being generated |

Examples:

```
/biomemap 5000                    10,000 × 10,000 blocks around you
/biomemap 2048 4                  full detail (4 blocks per pixel) close to you
/biomemap at 0 0 3000 8 y -40     cave biomes around 0, 0
/execute in minecraft:the_nether run biomemap 2048      the Nether
```

The largest map is 4096 × 4096 pixels, because bigger images don't display in some browsers. For
huge areas use a bigger step: `/biomemap 30000 16` covers 60,000 blocks across.

## The viewer

The HTML file is self-contained, so it works offline and you can send it to someone. It needs a
browser from 2023 or later.

- Drag to pan; scroll or pinch to zoom. `0` fits the whole map.
- Hover (or tap) to see the biome, coordinates, chunk and approximate height.
- **Biomes** tab: click a biome to highlight it, and click more to compare. ⌖ jumps to the nearest
  one. `Esc` clears the highlight.
- **Structures** tab: structures are listed by mod, then by type (Villages, Ruined Portals, ...),
  and types with variants (Village Plains, Village Desert, ...) fold open. Tick or untick a whole
  mod, a type or a single variant. The search box filters by structure or mod name, and ⌖ jumps to
  the nearest one.
- Hover a structure marker to see what it is, which mod adds it and its coordinates. Click it to
  copy a `/tp` to it. `S` shows or hides all markers.
- Markers have letters when there's room for them (V for villages, and so on) and turn into dots
  when many are on screen; zoom in to read them. Types that would bury the map, normally
  mineshafts, start hidden. Tick them in the list to show them.
- Click the map, or long-press on a phone, to copy `/tp @s <x> ~ <z>`.
- Relief shading, a chunk/region grid, and spawn and map-center markers can each be turned off.
- The address bar keeps the current view, so a bookmark brings you back to the same spot.

## How accurate is it?

- **Biome placement is exact**: it's the same function the game calls when it generates chunks.
  In tests against chunks the game really generated (vanilla, and with TerraBlender + Biomes O'
  Plenty), the map matched 8,605 of 8,608 cells in surface mode and 4,095 of 4,096 in exact mode.
  The few differences sat exactly on the border between two biomes' climate ranges, where the game
  itself can pick either one.
- **Structures are worked out the way world generation does it**: the same candidate chunks for
  each structure type, the same random pick when several structures share a type, and then the
  structure's own placement code with its biome and terrain checks. That's why modded structures
  and modded placement rules work too. In tests against chunks the game really generated, the map
  found every structure that generated and listed none that didn't:
  - vanilla: 196 of 196 in the Overworld, 148 of 148 in the Nether, 3 of 3 in the End;
  - with Biomes O' Plenty, Repurposed Structures and Dungeons and Taverns: 333 of 333 in the
    Overworld, 210 of 210 in the Nether and 9 of 9 in the End, covering 90 different
    structures, on Forge and on Fabric.
- **Forge, NeoForge and Fabric give the same maps.** With the same seed, the three made identical
  maps in tests. With Biomes O' Plenty, Repurposed Structures and Dungeons and Taverns, the biomes
  were identical too, but the Forge world had 5 underground houses from Dungeons and Taverns (out of
  about 1,000 structures) that the Fabric world didn't. Each map matched its own loader's world.
- **A marker shows where a structure starts**, the same point `/locate` gives. Big structures such
  as villages and ancient cities spread out from there.
- **Very common structure types can be left out of big maps.** The map checks at most 25,000
  possible structure spots, rarest types first. When a type doesn't fit, it's named under the
  structure list. Make a smaller map to include it.
- **It maps the seed of the world you're in.** It can't preview another seed from inside a world,
  because some biome mods (TerraBlender, for example) set up their biome regions from the world
  seed when the world loads. That's why step 1 is to create a world with the seed you want.
- **`surface`** shows the biome you'd see standing on the ground. Biome selection uses a "depth"
  value, and cave biomes only win once you are some way below the surface. This layer asks for depth
  0, which is the surface by definition, so it doesn't need to work out the terrain height first.
- **`exact`** works out the real terrain height at every point first, so it also shows the rare
  spots where a cave biome reaches the surface, for example in deep ravines. In tests that changed
  about 0.01% of the map, so `surface` is the better choice almost always.
- The height shown when you hover is an estimate (`Y ≈`) except in `exact` mode: typically within 3
  blocks of the real ground. It's used for the relief shading.

## Mapping a server's world

The mod has to run where the world is generated. A server without the mod can't be mapped from
your game directly, because servers never send the seed or their world generation setup to
players. You can rebuild the same world in singleplayer instead, and the map then matches the
server:

1. **Get the seed.** Ask an admin, or run `/seed` if you're an operator.
2. **Use the same modpack**, with the same mod versions and settings: biome and structure mods
   often have options. Copy the server's `config` folder into your instance. If it's a Forge or
   NeoForge server with a `world/serverconfig` folder, copy that into your singleplayer world's folder after
   creating the world, then reopen the world.
3. **Add the server's datapacks.** Copy the files from the server's `world/datapacks` folder into
   the Data Packs screen when you create the world.
4. **Use the same world type.** `level-type` in the server's `server.properties` says whether it's
   default, large biomes or amplified.
5. Create the world with that seed and run `/biomemap` in it.

Parts of the server's world that were generated before a modpack update keep the terrain they got
back then, so those areas can differ from the map.

## Colors

Vanilla biomes use the familiar Amidst/Chunkbase-style colors. Modded biomes get the color of the
closest vanilla type, worked out from their biome tags (forest, desert, ocean, snowy, and so on),
plus a small shift so that neighboring modded biomes stay distinguishable. Modded structure types
get a color and letters of their own, picked so that no two types share the same letters.

To change a biome color, edit `config/biomemap-colors.txt`. It's created the first time you make a
map and lists every biome in your game, commented out:

```
# minecraft:plains = #8db360        <- commented out: uses the default
biomesoplenty:lavender_field = #b57edc
```

Remove the `#` at the start of a line and edit the color. Changes apply to the next map, with no
restart needed. Delete the file to regenerate the list after adding mods.

## Performance

- Mapping runs on background threads, using all CPU cores but one or two, so the game keeps
  running. To use fewer, add `-Dbiomemap.threads=2` to your Java arguments.
- Biome time grows with the number of pixels: (2 × radius ÷ step)². Doubling the step makes the
  biome map four times faster.
- Structure time grows with the area and with how many structure types your mods add.
- Measured on a 4-core test machine, for the default map (4096 × 4096 blocks, 1024 × 1024 pixels):
  - vanilla: about 15 seconds on Forge, NeoForge and Fabric (445 structures);
  - with Biomes O' Plenty, Repurposed Structures and Dungeons and Taverns: about 50 seconds on
    Fabric and 80 to 100 seconds on Forge, nearly all of it for structures (about 645 structures of
    67 types). Structure checks work out the terrain height, and with these mods the game does
    that more slowly on Forge.
  Most gaming PCs have more cores and will be faster.
- `exact` mode runs the full terrain generator for every pixel, about 150 times slower: on the same
  machine a 256 × 256 pixel map took about a minute. Start with a small radius or a large step.

## Project layout

Almost all of the mod lives in `common`, which both loaders build from. It compiles against plain
Minecraft, so it can't use Forge or Fabric code by accident; the few things that differ go
through `Platform`.

```
common/src/main/java/io/github/zeptodev/biomemap/
  BiomeMap.java          mod id, logger, the loader in use
  Platform.java          what each loader provides: folders, mod names, biome climate, chat links
  BiomeMapCommand.java   /biomemap syntax and checks
  MapJob.java            sampling (multi-threaded), progress, saving
  StructureScan.java     which structures generate in the map area
  StructureStyle.java    structure names, marker colors and letters (no Minecraft code)
  BiomeColors.java       colors for vanilla and modded biomes, color config file
  ColorMath.java         color helpers (no Minecraft code)
  MapExport.java         PNG and HTML writer (no Minecraft code)
common/src/main/resources/io/github/zeptodev/biomemap/viewer.html   the map viewer template
forge/      Forge entry point (BiomeMapForge.java) and mods.toml
fabric/     Fabric entry points (BiomeMapFabric.java, BiomeMapFabricClient.java), fabric.mod.json
            and an access widener that lets the mod read biome climates
```

`gradlew :forge:runClient` or `gradlew :fabric:runClient` starts a development copy of the game
with the mod loaded.
