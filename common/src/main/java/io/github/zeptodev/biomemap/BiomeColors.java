package io.github.zeptodev.biomemap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Map colors. Vanilla biomes use the familiar Amidst/Chunkbase-style palette. Modded biomes get the color
 * of the closest vanilla category (read from biome tags and climate), nudged by a hash of their id so
 * that different modded biomes in the same category stay distinguishable. Players can override any
 * color in {@code config/biomemap-colors.txt}.
 */
final class BiomeColors {
    private static final String CONFIG_FILE = "biomemap-colors.txt";

    private static final Map<String, Integer> VANILLA = Map.ofEntries(
            Map.entry("minecraft:the_void", 0x000000),
            Map.entry("minecraft:plains", 0x8db360),
            Map.entry("minecraft:sunflower_plains", 0xb5db88),
            Map.entry("minecraft:snowy_plains", 0xffffff),
            Map.entry("minecraft:ice_spikes", 0xb4dcdc),
            Map.entry("minecraft:desert", 0xfa9418),
            Map.entry("minecraft:swamp", 0x07f9b2),
            Map.entry("minecraft:mangrove_swamp", 0x2ccc8e),
            Map.entry("minecraft:forest", 0x056621),
            Map.entry("minecraft:flower_forest", 0x2d8e49),
            Map.entry("minecraft:birch_forest", 0x307444),
            Map.entry("minecraft:dark_forest", 0x40511a),
            Map.entry("minecraft:old_growth_birch_forest", 0x589c6c),
            Map.entry("minecraft:old_growth_pine_taiga", 0x596651),
            Map.entry("minecraft:old_growth_spruce_taiga", 0x818e79),
            Map.entry("minecraft:taiga", 0x0b6659),
            Map.entry("minecraft:snowy_taiga", 0x31554a),
            Map.entry("minecraft:savanna", 0xbdb25f),
            Map.entry("minecraft:savanna_plateau", 0xa79d64),
            Map.entry("minecraft:windswept_hills", 0x606060),
            Map.entry("minecraft:windswept_gravelly_hills", 0x888888),
            Map.entry("minecraft:windswept_forest", 0x507050),
            Map.entry("minecraft:windswept_savanna", 0xe5da87),
            Map.entry("minecraft:jungle", 0x537b09),
            Map.entry("minecraft:sparse_jungle", 0x628b17),
            Map.entry("minecraft:bamboo_jungle", 0x768e14),
            Map.entry("minecraft:badlands", 0xd94515),
            Map.entry("minecraft:eroded_badlands", 0xff6d3d),
            Map.entry("minecraft:wooded_badlands", 0xb09765),
            Map.entry("minecraft:meadow", 0x60a445),
            Map.entry("minecraft:cherry_grove", 0xff91c8),
            Map.entry("minecraft:grove", 0x47726c),
            Map.entry("minecraft:snowy_slopes", 0xc4c4c4),
            Map.entry("minecraft:frozen_peaks", 0xb0b3ce),
            Map.entry("minecraft:jagged_peaks", 0xdcdcc8),
            Map.entry("minecraft:stony_peaks", 0x7b8f74),
            Map.entry("minecraft:river", 0x0000ff),
            Map.entry("minecraft:frozen_river", 0xa0a0ff),
            Map.entry("minecraft:beach", 0xfade55),
            Map.entry("minecraft:snowy_beach", 0xfaf0c0),
            Map.entry("minecraft:stony_shore", 0xa2a284),
            Map.entry("minecraft:warm_ocean", 0x0000ac),
            Map.entry("minecraft:lukewarm_ocean", 0x000090),
            Map.entry("minecraft:deep_lukewarm_ocean", 0x000040),
            Map.entry("minecraft:ocean", 0x000070),
            Map.entry("minecraft:deep_ocean", 0x000030),
            Map.entry("minecraft:cold_ocean", 0x202070),
            Map.entry("minecraft:deep_cold_ocean", 0x202038),
            Map.entry("minecraft:frozen_ocean", 0x7070d6),
            Map.entry("minecraft:deep_frozen_ocean", 0x404090),
            Map.entry("minecraft:mushroom_fields", 0xff00ff),
            Map.entry("minecraft:dripstone_caves", 0x4e332e),
            Map.entry("minecraft:lush_caves", 0x283c00),
            Map.entry("minecraft:deep_dark", 0x031f29),
            Map.entry("minecraft:nether_wastes", 0xbf3b3b),
            Map.entry("minecraft:warped_forest", 0x49907b),
            Map.entry("minecraft:crimson_forest", 0xdd0808),
            Map.entry("minecraft:soul_sand_valley", 0x522921),
            Map.entry("minecraft:basalt_deltas", 0x403636),
            Map.entry("minecraft:the_end", 0x8080ff),
            Map.entry("minecraft:end_highlands", 0xb5b536),
            Map.entry("minecraft:end_midlands", 0xc9c959),
            Map.entry("minecraft:small_end_islands", 0x4b4bab),
            Map.entry("minecraft:end_barrens", 0x7070cc));

    private BiomeColors() {}

    static String id(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("unknown:unregistered");
    }

    /** The color a biome gets when the config file does not override it. */
    static int auto(Holder<Biome> biome, String id) {
        Integer vanilla = VANILLA.get(id);
        return vanilla != null ? vanilla : ColorMath.vary(category(biome), id);
    }

    /** Oceans and rivers get softer terrain shading in the viewer. */
    static boolean isWater(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)
                || is(biome, Tags.Biomes.IS_OCEAN) || is(biome, Tags.Biomes.IS_RIVER);
    }

    private static int category(Holder<Biome> b) {
        Platform.Climate climate = BiomeMap.platform().climate(b.value());
        float temperature = climate.temperature();
        boolean cold = temperature < 0.15F || is(b, Tags.Biomes.IS_SNOWY) || is(b, Tags.Biomes.IS_ICY);
        boolean warm = temperature > 0.8F;

        if (b.is(BiomeTags.IS_NETHER) || is(b, Tags.Biomes.IS_NETHER)) {
            return is(b, Tags.Biomes.IS_NETHER_FOREST) ? 0x9e2a4a : 0xa83a32;
        }
        if (b.is(BiomeTags.IS_END) || is(b, Tags.Biomes.IS_END)) {
            return 0x9a86d8;
        }
        if (is(b, Tags.Biomes.IS_CAVE) || is(b, Tags.Biomes.IS_UNDERGROUND)) {
            return is(b, Tags.Biomes.IS_LUSH) ? 0x3d5a12 : 0x5a3f36;
        }
        if (b.is(BiomeTags.IS_DEEP_OCEAN) || is(b, Tags.Biomes.IS_DEEP_OCEAN)) {
            return cold ? 0x404090 : warm ? 0x00005a : 0x000038;
        }
        if (b.is(BiomeTags.IS_OCEAN) || is(b, Tags.Biomes.IS_OCEAN)) {
            return cold ? 0x7070d6 : warm ? 0x0000ac : 0x000070;
        }
        if (b.is(BiomeTags.IS_RIVER) || is(b, Tags.Biomes.IS_RIVER)) {
            return cold ? 0xa0a0ff : 0x1f3fff;
        }
        if (is(b, Tags.Biomes.IS_STONY_SHORES)) {
            return 0xa2a284;
        }
        if (b.is(BiomeTags.IS_BEACH) || is(b, Tags.Biomes.IS_BEACH)) {
            return cold ? 0xfaf0c0 : 0xfade55;
        }
        if (is(b, Tags.Biomes.IS_MUSHROOM)) {
            return 0xd400d4;
        }
        if (b.is(BiomeTags.IS_BADLANDS) || is(b, Tags.Biomes.IS_BADLANDS)) {
            return 0xd94515;
        }
        if (is(b, Tags.Biomes.IS_DESERT)) {
            return 0xfa9418;
        }
        if (is(b, Tags.Biomes.IS_MOUNTAIN_PEAK)) {
            return cold ? 0xdcdcc8 : 0x7b8f74;
        }
        if (is(b, Tags.Biomes.IS_SWAMP)) {
            return 0x2ccc8e;
        }
        if (b.is(BiomeTags.IS_JUNGLE) || is(b, Tags.Biomes.IS_JUNGLE)) {
            return 0x537b09;
        }
        if (b.is(BiomeTags.IS_SAVANNA) || is(b, Tags.Biomes.IS_SAVANNA)) {
            return 0xbdb25f;
        }
        if (is(b, Tags.Biomes.IS_ICY)) {
            return 0xb4dcdc;
        }
        if (b.is(BiomeTags.IS_TAIGA) || is(b, Tags.Biomes.IS_TAIGA) || is(b, Tags.Biomes.IS_CONIFEROUS_TREE)) {
            return cold ? 0x31554a : 0x0b6659;
        }
        if (is(b, Tags.Biomes.IS_BIRCH_FOREST)) {
            return 0x307444;
        }
        if (is(b, Tags.Biomes.IS_FLOWER_FOREST)) {
            return 0x2d8e49;
        }
        if (is(b, Tags.Biomes.IS_SPOOKY) || is(b, Tags.Biomes.IS_DEAD) || is(b, Tags.Biomes.IS_WASTELAND)) {
            return 0x6b5f4a;
        }
        if (b.is(BiomeTags.IS_FOREST) || is(b, Tags.Biomes.IS_FOREST)) {
            return cold ? 0x2f5d4a : 0x056621;
        }
        if (b.is(BiomeTags.IS_MOUNTAIN) || is(b, Tags.Biomes.IS_MOUNTAIN) || is(b, Tags.Biomes.IS_MOUNTAIN_SLOPE)
                || b.is(BiomeTags.IS_HILL) || is(b, Tags.Biomes.IS_WINDSWEPT)) {
            return cold ? 0xc4c4c4 : 0x707070;
        }
        if (cold) {
            return 0xeef2f5;
        }
        if (is(b, Tags.Biomes.IS_PLAINS)) {
            return 0x8db360;
        }
        if (is(b, Tags.Biomes.IS_SANDY) || (!climate.hasPrecipitation() && temperature > 1.0F)) {
            return 0xe8c070;
        }
        return ColorMath.climate(temperature, climate.downfall());
    }

    /**
     * Reads {@code config/biomemap-colors.txt}. On first use the file is created with every registered
     * biome and its current color, commented out, so players can see what to change.
     */
    static Map<String, Integer> loadOverrides(RegistryAccess registries) {
        Path file = BiomeMap.platform().configDir().resolve(CONFIG_FILE);
        try {
            if (Files.notExists(file)) {
                writeTemplate(file, registries.registryOrThrow(Registries.BIOME));
            }
            return ColorMath.parseOverrides(Files.readAllLines(file, StandardCharsets.UTF_8),
                    line -> BiomeMap.LOGGER.warn("Ignoring line in {}: {}", file.getFileName(), line));
        } catch (IOException | RuntimeException e) {
            BiomeMap.LOGGER.warn("Could not read biome colors from {}", file, e);
            return Map.of();
        }
    }

    private static void writeTemplate(Path file, Registry<Biome> registry) throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "# Biome Map colors. Each line is:  <biome id> = #RRGGBB",
                "# Every biome in your game is listed below with the color it gets now, commented out.",
                "# To change one, delete the '#' at the start of its line and edit the color.",
                "# Changes apply to the next /biomemap (no restart needed).",
                "# Delete this file to regenerate the list, for example after adding mods.",
                ""));
        registry.holders()
                .map(holder -> {
                    String id = holder.key().location().toString();
                    return "# " + id + " = " + ColorMath.hex(auto(holder, id));
                })
                .sorted()
                .forEach(lines::add);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * Convention tags, which biome mods add their biomes to: they're what tells a modded swamp from a modded
     * desert. In 1.20.1 Forge and Fabric name them differently ("forge:is_swamp", "c:swamp"), so each
     * category checks both. Vanilla's own tags ({@link BiomeTags}) are checked alongside where they exist.
     */
    private static final class Tags {
        private static final class Biomes {
            static final Tag IS_BADLANDS = tag("c:badlands", "c:mesa");
            static final Tag IS_BEACH = tag("c:beach");
            static final Tag IS_BIRCH_FOREST = tag("c:birch_forest");
            static final Tag IS_CAVE = tag("forge:is_cave", "c:caves");
            static final Tag IS_CONIFEROUS_TREE = tag("forge:is_coniferous", "c:tree_coniferous");
            static final Tag IS_DEAD = tag("forge:is_dead", "c:dead");
            static final Tag IS_DEEP_OCEAN = tag("c:deep_ocean");
            static final Tag IS_DESERT = tag("forge:is_desert", "c:desert");
            static final Tag IS_END = tag("c:in_the_end");
            static final Tag IS_FLOWER_FOREST = tag("c:flower_forests");
            static final Tag IS_FOREST = tag("c:forest");
            static final Tag IS_ICY = tag("c:icy");
            static final Tag IS_JUNGLE = tag("c:jungle");
            static final Tag IS_LUSH = tag("forge:is_lush");
            static final Tag IS_MOUNTAIN = tag("forge:is_mountain", "c:mountain");
            static final Tag IS_MOUNTAIN_PEAK = tag("forge:is_peak", "c:mountain_peak");
            static final Tag IS_MOUNTAIN_SLOPE = tag("forge:is_slope", "c:mountain_slope");
            static final Tag IS_MUSHROOM = tag("forge:is_mushroom", "c:mushroom");
            static final Tag IS_NETHER = tag("c:in_nether");
            static final Tag IS_NETHER_FOREST = tag("c:nether_forests");
            static final Tag IS_OCEAN = tag("c:ocean");
            static final Tag IS_PLAINS = tag("forge:is_plains", "c:plains");
            static final Tag IS_RIVER = tag("c:river");
            static final Tag IS_SANDY = tag("forge:is_sandy");
            static final Tag IS_SAVANNA = tag("c:savanna");
            static final Tag IS_SNOWY = tag("forge:is_snowy", "c:snowy");
            static final Tag IS_SPOOKY = tag("forge:is_spooky");
            static final Tag IS_STONY_SHORES = tag("c:stony_shores");
            static final Tag IS_SWAMP = tag("forge:is_swamp", "c:swamp");
            static final Tag IS_TAIGA = tag("c:taiga");
            static final Tag IS_UNDERGROUND = tag("forge:is_underground", "c:underground");
            static final Tag IS_WASTELAND = tag("forge:is_wasteland", "c:wasteland");
            static final Tag IS_WINDSWEPT = tag("c:windswept", "c:extreme_hills");

            private static Tag tag(String... ids) {
                List<TagKey<Biome>> keys = new ArrayList<>();
                for (String id : ids) {
                    keys.add(TagKey.create(Registries.BIOME, ResourceLocation.tryParse(id)));
                }
                return new Tag(List.copyOf(keys));
            }
        }
    }

    /** One category's tags. A biome belongs to it when it's in any of them. */
    private record Tag(List<TagKey<Biome>> keys) {}

    private static boolean is(Holder<Biome> biome, Tag tag) {
        for (TagKey<Biome> key : tag.keys()) {
            if (biome.is(key)) {
                return true;
            }
        }
        return false;
    }
}
