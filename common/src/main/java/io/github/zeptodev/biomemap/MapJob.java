package io.github.zeptodev.biomemap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * One biome map export. Sampling runs on background threads and only calls the world generator's pure
 * functions (biome source, climate sampler, noise router), so it never loads or generates chunks and
 * never blocks the server thread. Vanilla worldgen calls the same functions from many threads at once,
 * so biome mods have to support that already.
 */
final class MapJob {
    enum Mode { SURFACE, EXACT, FIXED }

    record Settings(int centerX, int centerZ, int radius, int step, Mode mode, int fixedY) {}

    private static final AtomicReference<MapJob> RUNNING = new AtomicReference<>();
    private static final int NO_SURFACE = Integer.MIN_VALUE;
    private static final short NO_HEIGHT = Short.MIN_VALUE;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
    private static final DateTimeFormatter SHOWN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final CommandSourceStack source;
    private final MinecraftServer server;
    private final ServerLevel level;
    /** Null when run from the console or a command block. */
    private final ServerPlayer player;
    private final Settings settings;
    private final long seed;
    private final BlockPos spawn;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int height;

    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final Climate.Sampler sampler;
    private final Climate.Sampler surfaceSampler;
    private final DensityFunction depth;
    private final int seaLevel;
    private final int minY;
    private final int maxY;

    private final StructureScan structureScan;

    private final short[] grid;
    private final short[] heights;
    private final Map<Holder<Biome>, Integer> biomeIds = new ConcurrentHashMap<>();
    private final List<Holder<Biome>> biomes = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    /** Structure sets whose check threw an error; left off the map and named in it. */
    private final Set<Holder<StructureSet>> failedSets = ConcurrentHashMap.newKeySet();

    /** Runs on the server thread, so it captures everything the background threads will need. */
    private MapJob(CommandSourceStack source, Settings settings) {
        this.source = source;
        this.server = source.getServer();
        this.level = source.getLevel();
        this.player = source.getPlayer();
        this.settings = settings;
        this.seed = level.getSeed();
        this.spawn = level.getSharedSpawnPos();

        int step = settings.step();
        this.width = (2 * settings.radius() + step - 1) / step; // rounded up
        this.height = width;
        // Samples sit on a global lattice of the step size, so maps of overlapping areas line up.
        this.originX = Math.floorDiv(settings.centerX() - settings.radius(), step) * step;
        this.originZ = Math.floorDiv(settings.centerZ() - settings.radius(), step) * step;

        ServerChunkCache chunks = level.getChunkSource();
        this.generator = chunks.getGenerator();
        this.randomState = chunks.randomState();
        this.biomeSource = generator.getBiomeSource();
        this.sampler = randomState.sampler();
        // Surface biomes are what the climate parameters pick at depth 0. Cave biomes (lush caves, dripstone
        // caves, deep dark) only win at larger depths, so a sampler whose depth is fixed at 0 sees exactly the
        // surface layer without having to work out the terrain height first. The End's biome source doesn't
        // use depth, and mods can attach state to the game's own sampler there (Fabric API seeds its End biome
        // noise from it), so the End keeps the real one.
        this.surfaceSampler = biomeSource instanceof TheEndBiomeSource ? sampler
                : new Climate.Sampler(sampler.temperature(), sampler.humidity(), sampler.continentalness(),
                        sampler.erosion(), DensityFunctions.zero(), sampler.weirdness(), sampler.spawnTarget());
        this.depth = randomState.router().depth();
        this.seaLevel = generator.getSeaLevel();
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight() - 1;

        this.structureScan = new StructureScan(level, originX, originZ,
                originX + width * step - 1, originZ + height * step - 1);

        this.grid = new short[width * height];
        this.heights = new short[width * height];
    }

    /** Starts a job, or returns why it could not start. */
    static String start(CommandSourceStack source, Settings settings) {
        if (RUNNING.get() != null) {
            return "A biome map is already being generated. Use /biomemap cancel to stop it.";
        }
        MapJob job = new MapJob(source, settings);
        if (settings.mode() == Mode.FIXED && (settings.fixedY() < job.minY || settings.fixedY() > job.maxY)) {
            return "Y must be between " + job.minY + " and " + job.maxY + " in this dimension.";
        }
        if (!RUNNING.compareAndSet(null, job)) {
            return "A biome map is already being generated. Use /biomemap cancel to stop it.";
        }
        job.announce();
        Thread thread = new Thread(job::run, "BiomeMap");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    static boolean cancelRunning() {
        MapJob job = RUNNING.get();
        if (job == null) {
            return false;
        }
        job.cancelled.set(true);
        return true;
    }

    private void announce() {
        String what = switch (settings.mode()) {
            case SURFACE -> "surface biomes";
            case EXACT -> "surface biomes at the exact terrain height (slower)";
            case FIXED -> "biomes at Y " + settings.fixedY();
        };
        String where = level.dimension() == Level.OVERWORLD ? "" : " in " + level.dimension().location();
        // Players see progress above the hotbar; the console has no action bar.
        String progress = player != null ? "" : " Progress goes to the log.";
        Component message = Component.literal(String.format(Locale.ROOT,
                "Mapping %s%s: %d x %d blocks around X %d, Z %d, %d blocks per pixel.%s /biomemap cancel stops it.",
                what, where, width * settings.step(), height * settings.step(),
                settings.centerX(), settings.centerZ(), settings.step(), progress));
        source.sendSuccess(() -> message, false);
    }

    private void run() {
        long start = System.nanoTime();
        try {
            parallel("Biome map", height, this::sampleRow);
            List<StructureScan.Found> structures = cancelled.get() ? List.of() : findStructures();
            Throwable error = failure.get();
            if (error != null) {
                throw error;
            }
            if (cancelled.get()) {
                reply(Component.literal("Biome map cancelled.").withStyle(ChatFormatting.YELLOW), false);
                return;
            }
            actionBar("Biome map: saving...");
            Path dir = BiomeMap.platform().gameDir().resolve("biomemaps");
            Files.createDirectories(dir);
            String name = String.format(Locale.ROOT, "biomemap_%d_%s_%s", seed,
                    level.dimension().location().getPath().replace('/', '_'), LocalDateTime.now().format(FILE_TIME));
            Path png = dir.resolve(name + ".png");
            Path html = dir.resolve(name + ".html");

            List<MapExport.Biome> legend = legend(BiomeColors.loadOverrides(level.registryAccess()));
            int[] colors = legend.stream().mapToInt(MapExport.Biome::color).toArray();
            MapExport.writePng(png, width, height, grid, colors);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            MapExport.writeHtml(html, loadViewer(), MapExport.toJson(info(elapsedMs), legend, grid, finishedHeights(),
                    structuresExport(structures)));
            sendDone(elapsedMs, legend.size(), structures.size(), html, png);
            BiomeMap.LOGGER.info("Biome map saved to {}", html.toAbsolutePath());
        } catch (Throwable t) {
            BiomeMap.LOGGER.error("Biome map failed", t);
            reply(Component.literal("Biome map failed: " + t).withStyle(ChatFormatting.RED), true);
        } finally {
            RUNNING.compareAndSet(this, null);
        }
    }

    /** Runs task(0) .. task(count - 1) on the worker threads and reports progress as "label: N%". */
    private void parallel(String label, int count, IntConsumer task) throws InterruptedException {
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        Thread[] workers = new Thread[threadCount()];
        for (int t = 0; t < workers.length; t++) {
            workers[t] = new Thread(() -> {
                try {
                    int i;
                    while (!cancelled.get() && (i = next.getAndIncrement()) < count) {
                        task.accept(i);
                        done.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    cancelled.set(true);
                }
            }, "BiomeMap worker " + (t + 1));
            workers[t].setDaemon(true);
            workers[t].setPriority(Thread.NORM_PRIORITY - 1);
            workers[t].start();
        }
        long start = System.nanoTime();
        int logged = 0;
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                worker.join(1000);
                logged = reportProgress(label, done.get(), count, start, logged);
            }
        }
    }

    /** Structures in the map area, in a stable order. Runs after the biomes, on the same worker threads. */
    private List<StructureScan.Found> findStructures() throws InterruptedException {
        List<StructureScan.Candidate> candidates = structureScan.candidates();
        StructureScan.Found[] results = new StructureScan.Found[candidates.size()];
        Queue<Integer> retry = new ConcurrentLinkedQueue<>();
        StructureScan.warmUp();
        parallel("Finding structures", candidates.size(), i -> {
            try {
                results[i] = structureScan.check(candidates.get(i));
            } catch (RuntimeException e) {
                retry.add(i);
            }
        });
        // Code that isn't thread-safe (in the game or in mods) can still fail when several threads reach it at
        // once, so failures get one more try here, on this thread alone.
        for (int i : retry) {
            if (cancelled.get()) {
                break;
            }
            StructureScan.Candidate candidate = candidates.get(i);
            try {
                results[i] = structureScan.check(candidate);
            } catch (RuntimeException e) {
                // One structure mod failing outside normal world generation shouldn't cost the whole map.
                if (failedSets.add(candidate.set())) {
                    BiomeMap.LOGGER.warn("Could not check {} at chunk {}, {}; leaving it off the map",
                            keyOf(candidate.set()), candidate.chunkX(), candidate.chunkZ(), e);
                }
            }
        }
        List<StructureScan.Found> found = new ArrayList<>();
        for (StructureScan.Found result : results) {
            if (result != null && !failedSets.contains(result.set())) {
                found.add(result);
            }
        }
        return found;
    }

    private void sampleRow(int row) {
        int step = settings.step();
        int z = originZ + row * step;
        int quartZ = QuartPos.fromBlock(z);
        int offset = row * width;
        for (int col = 0; col < width; col++) {
            if (cancelled.get()) {
                return;
            }
            int x = originX + col * step;
            Climate.Sampler climate = sampler;
            int surface;
            int y;
            switch (settings.mode()) {
                case SURFACE -> {
                    surface = estimateSurface(x, z);
                    // Mods that place biomes by height (rather than depth) still need a sensible Y.
                    y = surface == NO_SURFACE ? seaLevel : Math.max(surface, seaLevel);
                    climate = surfaceSampler;
                }
                case EXACT -> {
                    // Same height calculation structures use; the real terrain before trees and ores.
                    surface = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    y = Math.max(surface, seaLevel);
                }
                default -> {
                    surface = estimateSurface(x, z);
                    y = settings.fixedY();
                }
            }
            int quartY = QuartPos.fromBlock(Mth.clamp(y, minY, maxY));
            Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(x), quartY, quartZ, climate);
            grid[offset + col] = (short) idOf(biome);
            heights[offset + col] = surface == NO_SURFACE ? NO_HEIGHT : (short) Mth.clamp(surface, -30000, 30000);
        }
    }

    /**
     * Height where the noise router's depth reaches 0, i.e. the smooth terrain surface before 3D noise,
     * peaks and caves. Depth falls linearly with height in vanilla-style worldgen, so two samples are
     * enough. Good for shading and a rough Y readout; exact mode reports the real height instead.
     */
    private int estimateSurface(int x, int z) {
        double low = depth.compute(new DensityFunction.SinglePointContext(x, 0, z));
        double high = depth.compute(new DensityFunction.SinglePointContext(x, 128, z));
        double perBlock = (high - low) / 128.0;
        if (!(perBlock < -1.0E-6)) {
            return NO_SURFACE; // No terrain surface (the Nether, the End, flat or void worlds).
        }
        return (int) Math.floor(Mth.clamp(-low / perBlock, minY, maxY));
    }

    private int idOf(Holder<Biome> biome) {
        Integer id = biomeIds.get(biome);
        if (id != null) {
            return id;
        }
        return biomeIds.computeIfAbsent(biome, key -> {
            synchronized (biomes) {
                if (biomes.size() > Short.MAX_VALUE) {
                    throw new IllegalStateException("More than " + Short.MAX_VALUE + " different biomes");
                }
                biomes.add(key);
                return biomes.size() - 1;
            }
        });
    }

    private int reportProgress(String label, int done, int total, long start, int logged) {
        int percent = total == 0 ? 100 : (int) (100L * done / total);
        double seconds = (System.nanoTime() - start) / 1.0E9;
        String eta = done == 0 ? "" : ", about " + duration(seconds * (total - done) / done) + " left";
        if (player != null) {
            actionBar(label + ": " + percent + "%" + eta);
            return logged;
        }
        if (percent >= logged + 10) {
            BiomeMap.LOGGER.info("{}: {}%{}", label, percent, eta);
            return percent / 10 * 10;
        }
        return logged;
    }

    /** Groups (structure sets), kinds (structures) and a compact point list for the viewer. */
    private MapExport.Structures structuresExport(List<StructureScan.Found> found) {
        Map<Holder<StructureSet>, Integer> groupIds = new LinkedHashMap<>();
        Map<Holder<?>, Integer> kindIds = new LinkedHashMap<>();
        List<MapExport.StructureGroup> groups = new ArrayList<>();
        List<MapExport.StructureKind> kinds = new ArrayList<>();
        Map<String, StructureStyle.Style> generated = StructureStyle.generated(
                structureScan.sets().stream().map(MapJob::keyOf).toList());
        // Stable order: by mod, then type, then structure name.
        List<StructureScan.Found> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing((StructureScan.Found f) -> keyOf(f.set())).thenComparing(f -> keyOf(f.structure())));
        int[] points = new int[sorted.size() * 4];
        int p = 0;
        for (StructureScan.Found f : sorted) {
            int group = groupIds.computeIfAbsent(f.set(), set -> {
                String id = keyOf(set);
                StructureScan.Source source = structureScan.source(set);
                groups.add(new MapExport.StructureGroup(id, StructureStyle.prettify(id), source.id(), source.name()));
                return groups.size() - 1;
            });
            int kind = kindIds.computeIfAbsent(f.structure(), structure -> {
                String id = keyOf(structure);
                StructureStyle.Style style = StructureStyle.of(id, keyOf(f.set()), generated);
                kinds.add(new MapExport.StructureKind(id, StructureStyle.prettify(id), group, style.color(), style.label()));
                return kinds.size() - 1;
            });
            points[p++] = kind;
            points[p++] = f.position().getX();
            points[p++] = f.position().getY();
            points[p++] = f.position().getZ();
        }
        List<String> skipped = structureScan.skipped().stream().map(MapJob::keyOf).map(StructureStyle::prettify).toList();
        List<String> failed = failedSets.stream().map(MapJob::keyOf).map(StructureStyle::prettify).sorted().toList();
        return new MapExport.Structures(groups, kinds, points, skipped, failed);
    }

    private static String keyOf(Holder<?> holder) {
        return holder.unwrapKey().map(key -> key.location().toString()).orElse("unknown:unregistered");
    }

    /**
     * The legend, sorted by biome id. Workers number biomes in the order they happen to meet them, so the grid
     * is renumbered to match: running the same map again then gives the same PNG and, timestamps aside, the
     * same data.
     */
    private List<MapExport.Biome> legend(Map<String, Integer> overrides) {
        // All workers have been joined, so their writes to grid and biomes are visible here.
        long[] counts = new long[biomes.size()];
        for (short id : grid) {
            counts[id]++;
        }
        List<MapExport.Biome> found = new ArrayList<>(biomes.size());
        for (int i = 0; i < biomes.size(); i++) {
            Holder<Biome> biome = biomes.get(i);
            String id = BiomeColors.id(biome);
            Integer override = overrides.get(id);
            int color = override != null ? override : BiomeColors.auto(biome, id);
            found.add(new MapExport.Biome(id, displayName(id), color, counts[i], BiomeColors.isWater(biome)));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> found.get(i).id()));
        short[] renumber = new short[found.size()];
        List<MapExport.Biome> legend = new ArrayList<>(found.size());
        for (int i : order) {
            renumber[i] = (short) legend.size();
            legend.add(found.get(i));
        }
        for (int p = 0; p < grid.length; p++) {
            grid[p] = renumber[grid[p]];
        }
        return legend;
    }

    /** Uses the game's language when it has a name for the biome (always true in singleplayer). */
    private static String displayName(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location != null) {
            String key = "biome." + location.getNamespace() + "." + location.getPath().replace('/', '.');
            Language language = Language.getInstance();
            if (language.has(key)) {
                return language.getOrDefault(key, id);
            }
        }
        String path = id.substring(id.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("_")) {
            if (!word.isEmpty()) {
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return name.isEmpty() ? id : name.toString();
    }

    /** Heights for the viewer, with gaps filled from the left, or null if this dimension has no surface. */
    private short[] finishedHeights() {
        boolean any = false;
        for (short h : heights) {
            if (h != NO_HEIGHT) {
                any = true;
                break;
            }
        }
        if (!any) {
            return null;
        }
        short[] filled = heights.clone();
        for (int row = 0; row < height; row++) {
            short last = (short) seaLevel;
            for (int col = 0; col < width; col++) {
                int i = row * width + col;
                if (filled[i] == NO_HEIGHT) {
                    filled[i] = last;
                } else {
                    last = filled[i];
                }
            }
        }
        return filled;
    }

    private MapExport.MapInfo info(long elapsedMs) {
        boolean overworld = level.dimension() == Level.OVERWORLD;
        String mode = switch (settings.mode()) {
            case SURFACE -> "surface";
            case EXACT -> "exact";
            case FIXED -> "y";
        };
        return new MapExport.MapInfo(Long.toString(seed), level.dimension().location().toString(), mode,
                settings.fixedY(), settings.mode() == Mode.EXACT, originX, originZ, settings.step(), width, height,
                settings.centerX(), settings.centerZ(), overworld ? spawn.getX() : null,
                overworld ? spawn.getZ() : null, SharedConstants.getCurrentVersion().getName(), modVersion(),
                LocalDateTime.now().format(SHOWN_TIME), elapsedMs);
    }

    private static String modVersion() {
        return BiomeMap.platform().modVersion(BiomeMap.MOD_ID).orElse("unknown");
    }

    private static String loadViewer() throws IOException {
        try (InputStream in = MapJob.class.getResourceAsStream("viewer.html")) {
            if (in == null) {
                throw new IOException("viewer.html is missing from the mod jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Links that open the files go to the singleplayer host, the only player whose game has them, and need
     * the loader's help to get there (see {@link Platform#sendToHost}). Anyone else gets links that copy
     * the path.
     */
    private void sendDone(long elapsedMs, int biomeCount, int structureCount, Path html, Path png) {
        Component copyLinks = doneMessage(elapsedMs, biomeCount, structureCount, html, png, false);
        if (player == null || server.isDedicatedServer() || !server.isSingleplayerOwner(player.getGameProfile())) {
            reply(copyLinks, false);
            return;
        }
        Component openLinks = doneMessage(elapsedMs, biomeCount, structureCount, html, png, true);
        server.execute(() -> {
            if (!BiomeMap.platform().sendToHost(source, openLinks)) {
                source.sendSuccess(() -> copyLinks, false);
            }
        });
    }

    private Component doneMessage(long elapsedMs, int biomeCount, int structureCount, Path html, Path png,
                                  boolean openLinks) {
        MutableComponent message = Component.literal(String.format(Locale.ROOT,
                "Biome map done in %s: %d x %d pixels, %d biomes, %d structures.",
                duration(elapsedMs / 1000.0), width, height, biomeCount, structureCount));
        if (player == null) {
            return message.append(" Saved to " + html.toAbsolutePath());
        }
        // Links on their own line, so chat never wraps one in the middle.
        return message.append("\n")
                .append(fileLink("[Open map]", html, "Open the interactive map in your browser", openLinks))
                .append("  ")
                .append(fileLink("[PNG]", png, "Open the map image", openLinks));
    }

    private static MutableComponent fileLink(String label, Path file, String tooltip, boolean canOpen) {
        String path = file.toAbsolutePath().toString();
        ClickEvent click = canOpen
                ? new ClickEvent(ClickEvent.Action.OPEN_FILE, path)
                : new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path);
        Component hover = Component.literal(canOpen ? tooltip : "Click to copy the path: " + path);
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    private void actionBar(String text) {
        if (player != null) {
            Component message = Component.literal(text);
            server.execute(() -> player.displayClientMessage(message, true));
        }
    }

    private void reply(Component message, boolean isFailure) {
        server.execute(() -> {
            if (isFailure) {
                source.sendFailure(message);
            } else {
                source.sendSuccess(() -> message, false);
            }
        });
    }

    private static int threadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        // Leave room for the server thread (and the client, in singleplayer). -Dbiomemap.threads=N overrides.
        int threads = Integer.getInteger("biomemap.threads", Math.max(1, cores - (cores > 4 ? 2 : 1)));
        return Mth.clamp(threads, 1, 64);
    }

    private static String duration(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) {
            return seconds < 10 ? String.format(Locale.ROOT, "%.1f s", seconds) : s + " s";
        }
        if (s < 3600) {
            return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d h %02d min", s / 3600, s % 3600 / 60);
    }
}
