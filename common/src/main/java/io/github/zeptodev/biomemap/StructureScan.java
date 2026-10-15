package io.github.zeptodev.biomemap;

import com.mojang.math.OctahedralGroup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Finds the structures in a map area the way world generation decides them
 * ({@code ChunkGenerator.createStructures}), without generating any chunks: which chunks are candidates for
 * each structure set, which structure of the set gets picked there, and whether it can generate. That last
 * step runs the structure's own placement code, so modded structures, biome rules and terrain checks behave
 * exactly as in the game.
 */
final class StructureScan {
    /** Structure checks per map. Beyond this the most common types are left out (they'd only clutter it). */
    static final int MAX_CHECKS = 25_000;
    /** Placement regions (or chunks) to walk per structure type before it counts as too common for the map. */
    private static final long MAX_REGIONS = 4_000_000L;

    record Candidate(Holder<StructureSet> set, int chunkX, int chunkZ) {}

    /** {@code position} is the structure's generation point, the same anchor {@code /locate} uses. */
    record Found(Holder<StructureSet> set, Holder<Structure> structure, BlockPos position) {}

    /** The mod or datapack that adds a structure set: a key to group by and a name to show. */
    record Source(String id, String name) {}

    private final ServerLevel level;
    private final ChunkGeneratorStructureState state;
    private final RegistryAccess registries;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final StructureTemplateManager templates;
    private final long seed;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    /** Structure sets left out because they are too common for this map size. */
    private final Set<Holder<StructureSet>> skipped = new LinkedHashSet<>();
    private final Map<Holder<StructureSet>, Source> sources = new HashMap<>();

    /** Runs on the server thread. Block coordinates are inclusive. */
    StructureScan(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        this.level = level;
        this.state = level.getChunkSource().getGeneratorState();
        this.registries = level.registryAccess();
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.templates = level.getServer().getStructureManager();
        this.seed = state.getLevelSeed();
        this.minChunkX = minX >> 4;
        this.minChunkZ = minZ >> 4;
        this.maxChunkX = maxX >> 4;
        this.maxChunkZ = maxZ >> 4;
        ResourceManager resources = level.getServer().getResourceManager();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            sources.put(set, sourceOf(resources, set));
        }
    }

    /**
     * Who adds a structure set, for filtering by mod. The namespace alone can mislead (Dungeons and Taverns
     * uses "nova_structures"), so this asks which pack holds the set's file. It takes the lowest such pack,
     * the one that added the set, so a mod that only tweaks a vanilla set doesn't claim it.
     */
    private static Source sourceOf(ResourceManager resources, Holder<StructureSet> set) {
        Identifier id = set.unwrapKey().map(key -> key.identifier()).orElse(null);
        if (id == null) {
            return new Source("unknown", "Unknown");
        }
        List<Resource> stack = resources.getResourceStack(id.withPath(path -> "worldgen/structure_set/" + path + ".json"));
        if (!stack.isEmpty()) {
            Resource first = stack.get(0);
            String pack = first.sourcePackId();
            if (pack.equals("vanilla")) {
                return new Source("minecraft", "Minecraft");
            }
            String modId = BiomeMap.platform().modOfPack(pack);
            if (modId != null) {
                return new Source(modId, modName(modId));
            }
            String title = first.source().location().title().getString(); // a datapack, e.g. "file/towers.zip"
            return new Source(pack, title.isBlank() ? pack : title);
        }
        return new Source(id.getNamespace(), modName(id.getNamespace()));
    }

    /** Display name of a mod, e.g. "Repurposed Structures". */
    private static String modName(String modId) {
        if (modId.equals("minecraft")) {
            return "Minecraft";
        }
        return BiomeMap.platform().modName(modId).orElseGet(() -> StructureStyle.prettify(modId));
    }

    Source source(Holder<StructureSet> set) {
        Source source = sources.get(set);
        return source != null ? source : new Source("unknown", "Unknown");
    }

    /**
     * Call once, on one thread, before checking structures from several threads. Vanilla fills the rotation
     * tables used to place structure templates lazily and not thread-safely ({@code OctahedralGroup.rotate}):
     * when several threads rotate a template for the first time at once, one can read a half-filled table
     * and crash. Normal play rarely hits this; parallel checks of a never-seen structure do.
     */
    static void warmUp() {
        for (OctahedralGroup group : OctahedralGroup.values()) {
            for (Direction direction : Direction.values()) {
                group.rotate(direction);
            }
        }
    }

    /**
     * Chunks where a structure set may place a structure. Cheap compared to the checks that follow, so it is
     * done up front for every set; the most common sets are then dropped if the total would be too many.
     */
    List<Candidate> candidates() {
        List<List<Candidate>> perSet = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            List<Candidate> list = new ArrayList<>();
            StructurePlacement placement = set.value().placement();
            if (placement instanceof RandomSpreadStructurePlacement spread && onePerRegion(set, spread)) {
                // One candidate per spacing x spacing region: no need to test every chunk.
                int spacing = spread.spacing();
                int rx0 = Math.floorDiv(minChunkX, spacing);
                int rx1 = Math.floorDiv(maxChunkX, spacing);
                int rz0 = Math.floorDiv(minChunkZ, spacing);
                int rz1 = Math.floorDiv(maxChunkZ, spacing);
                if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > MAX_REGIONS) {
                    skipped.add(set);
                    continue;
                }
                for (int rx = rx0; rx <= rx1; rx++) {
                    for (int rz = rz0; rz <= rz1; rz++) {
                        ChunkPos pos = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                        if (inside(pos.x, pos.z) && spread.isStructureChunk(state, pos.x, pos.z)) {
                            list.add(new Candidate(set, pos.x, pos.z));
                        }
                    }
                }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                // Strongholds: the game works out all ring positions when the world loads.
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions != null) {
                    for (ChunkPos pos : positions) {
                        if (inside(pos.x, pos.z) && rings.isStructureChunk(state, pos.x, pos.z)) {
                            list.add(new Candidate(set, pos.x, pos.z));
                        }
                    }
                }
            } else {
                // A placement type added by a mod (or one that spreads differently): ask about every chunk.
                if ((long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1) > MAX_REGIONS) {
                    skipped.add(set);
                    continue;
                }
                for (int x = minChunkX; x <= maxChunkX; x++) {
                    for (int z = minChunkZ; z <= maxChunkZ; z++) {
                        if (placement.isStructureChunk(state, x, z)) {
                            list.add(new Candidate(set, x, z));
                        }
                    }
                }
            }
            perSet.add(list);
        }
        // Keep the expensive checks affordable: rarer structure types first, the most common ones may not fit.
        perSet.sort(Comparator.comparingInt(List::size));
        List<Candidate> all = new ArrayList<>();
        for (List<Candidate> list : perSet) {
            if (all.size() + list.size() > MAX_CHECKS) {
                skipped.add(list.get(0).set());
            } else {
                all.addAll(list);
            }
        }
        return all;
    }

    /**
     * The shortcut above relies on vanilla's spread: at most one chunk per spacing x spacing region, the one
     * {@code getPotentialStructureChunk} names. Mods can subclass the placement (Repurposed Structures does, and
     * keeps that rule), so for subclasses spot-check a few regions near the map center for other chunks.
     */
    private boolean onePerRegion(Holder<StructureSet> set, RandomSpreadStructurePlacement spread) {
        if (spread.getClass() == RandomSpreadStructurePlacement.class) {
            return true;
        }
        int spacing = spread.spacing();
        long mapChunks = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (spacing <= 0 || 9L * spacing * spacing >= mapChunks) {
            return false; // checking every chunk of the map costs no more than the spot check
        }
        int centerX = Math.floorDiv(minChunkX + (maxChunkX - minChunkX) / 2, spacing);
        int centerZ = Math.floorDiv(minChunkZ + (maxChunkZ - minChunkZ) / 2, spacing);
        for (int rx = centerX - 1; rx <= centerX + 1; rx++) {
            for (int rz = centerZ - 1; rz <= centerZ + 1; rz++) {
                ChunkPos expected = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                for (int x = rx * spacing; x < (rx + 1) * spacing; x++) {
                    for (int z = rz * spacing; z < (rz + 1) * spacing; z++) {
                        if ((x != expected.x || z != expected.z) && spread.isStructureChunk(state, x, z)) {
                            BiomeMap.LOGGER.info("Structure set {} spreads differently from vanilla; checking every chunk",
                                    set.getRegisteredName());
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether a structure of the candidate's set generates in its chunk. Same steps and same random choices
     * as {@code ChunkGenerator.createStructures}. Safe to call from several threads.
     */
    Found check(Candidate candidate) {
        List<StructureSet.StructureSelectionEntry> entries = candidate.set().value().structures();
        ChunkPos pos = new ChunkPos(candidate.chunkX(), candidate.chunkZ());
        if (entries.size() == 1) {
            return generate(candidate.set(), entries.get(0), pos);
        }
        // Several structures share the set (fortresses and bastions, for example): pick by weight, and try
        // the next one when the picked structure can't generate here.
        List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(entries);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, pos.x, pos.z);
        int total = 0;
        for (StructureSet.StructureSelectionEntry entry : left) {
            total += entry.weight();
        }
        while (!left.isEmpty() && total > 0) {
            int roll = random.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry entry : left) {
                roll -= entry.weight();
                if (roll < 0) {
                    break;
                }
                index++;
            }
            StructureSet.StructureSelectionEntry entry = left.get(index);
            Found found = generate(candidate.set(), entry, pos);
            if (found != null) {
                return found;
            }
            left.remove(index);
            total -= entry.weight();
        }
        return null;
    }

    /**
     * The structure's own placement code: terrain, biome and layout checks, as in a real chunk. The game keeps
     * a structure when it finds a valid generation point and the piece layout isn't empty. Every vanilla
     * structure type always lays out at least one piece once it has a point (jigsaw structures start with
     * their start piece), so the costly layout step is only run for structure types added by mods.
     */
    private Found generate(Holder<StructureSet> set, StructureSet.StructureSelectionEntry entry, ChunkPos pos) {
        Structure structure = entry.structure().value();
        Structure.GenerationContext context = new Structure.GenerationContext(registries, generator,
                generator.getBiomeSource(), randomState, templates, seed, pos, level, structure.biomes()::contains);
        Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(context);
        if (stub.isEmpty()) {
            return null;
        }
        Identifier type = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
        if ((type == null || !type.getNamespace().equals("minecraft")) && stub.get().getPiecesBuilder().build().isEmpty()) {
            return null;
        }
        return new Found(set, entry.structure(), stub.get().position());
    }

    /** Every structure set this dimension can place. */
    List<Holder<StructureSet>> sets() {
        return state.possibleStructureSets();
    }

    Set<Holder<StructureSet>> skipped() {
        return skipped;
    }

    private boolean inside(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }
}
