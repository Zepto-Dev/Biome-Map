package io.github.zeptodev.biomemap;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Marker colors and labels for structures, and readable names from ids. Free of Minecraft classes. */
final class StructureStyle {
    record Style(int color, String label) {}

    /** Vanilla structure sets: every structure of a set shares its marker. */
    private static final Map<String, Style> VANILLA_SETS = Map.ofEntries(
            Map.entry("minecraft:villages", new Style(0xf0a830, "V")),
            Map.entry("minecraft:pillager_outposts", new Style(0x8a8f96, "P")),
            Map.entry("minecraft:woodland_mansions", new Style(0x7a4a24, "M")),
            Map.entry("minecraft:desert_pyramids", new Style(0xe6cb86, "D")),
            Map.entry("minecraft:jungle_temples", new Style(0x4f8a2c, "J")),
            Map.entry("minecraft:swamp_huts", new Style(0x5d6b33, "W")),
            Map.entry("minecraft:igloos", new Style(0xd4ecf7, "I")),
            Map.entry("minecraft:shipwrecks", new Style(0x9b6f42, "Sh")),
            Map.entry("minecraft:ocean_monuments", new Style(0x2bb3b1, "Mo")),
            Map.entry("minecraft:ocean_ruins", new Style(0x5f8ea6, "OR")),
            Map.entry("minecraft:ruined_portals", new Style(0x9246d8, "RP")),
            Map.entry("minecraft:buried_treasures", new Style(0xf7d046, "T")),
            Map.entry("minecraft:mineshafts", new Style(0xa8875c, "Ms")),
            Map.entry("minecraft:strongholds", new Style(0x6a55a8, "S")),
            Map.entry("minecraft:ancient_cities", new Style(0x0f5560, "AC")),
            Map.entry("minecraft:trail_ruins", new Style(0xc95f7d, "TR")),
            Map.entry("minecraft:trial_chambers", new Style(0xc8743c, "TC")),
            Map.entry("minecraft:nether_fossils", new Style(0xe8e2c8, "NF")),
            Map.entry("minecraft:end_cities", new Style(0xc9a8d6, "E")));

    /** Structures that share a set but deserve their own marker. */
    private static final Map<String, Style> VANILLA_STRUCTURES = Map.of(
            "minecraft:fortress", new Style(0x7a2424, "F"),
            "minecraft:bastion_remnant", new Style(0x3a3436, "B"));

    private StructureStyle() {}

    /**
     * Markers for the structure sets that have no curated one (those added by mods): a color picked from the
     * id and a label from the name. Labels are kept apart from every other set's, so "Mineshafts Ocean" and
     * "Mineshafts Overworld" become MO and MV rather than both MO. Pass every set of the dimension, not only
     * those on the map, so a type keeps its label from one map to the next.
     */
    static Map<String, Style> generated(Collection<String> setIds) {
        Set<String> taken = new HashSet<>();
        VANILLA_SETS.values().forEach(style -> taken.add(key(style.label())));
        VANILLA_STRUCTURES.values().forEach(style -> taken.add(key(style.label())));
        Map<String, Style> styles = new HashMap<>();
        for (String setId : new TreeSet<>(setIds)) {
            if (!VANILLA_SETS.containsKey(setId)) {
                String label = uniqueLabel(prettify(setId), taken);
                taken.add(key(label));
                styles.put(setId, new Style(ColorMath.hashColor(setId), label));
            }
        }
        return styles;
    }

    /** {@code generated} is the result of {@link #generated}. */
    static Style of(String structureId, String setId, Map<String, Style> generated) {
        Style style = VANILLA_STRUCTURES.get(structureId);
        if (style == null) {
            style = VANILLA_SETS.get(setId);
        }
        if (style == null) {
            style = generated.get(setId);
        }
        if (style == null) {
            style = new Style(ColorMath.hashColor(setId), initials(prettify(setId)));
        }
        return style;
    }

    /**
     * The name's initials, or if another type has them, its first letter and a later one: from the last word
     * first, since that usually tells similar names apart. Kept as is when nothing is free.
     */
    static String uniqueLabel(String name, Set<String> taken) {
        String label = initials(name);
        if (!taken.contains(key(label))) {
            return label;
        }
        String[] words = name.trim().split("\\s+");
        char first = Character.toUpperCase(words[0].charAt(0));
        String letters = words.length > 1 ? words[words.length - 1] + String.join("", words) : words[0].substring(1);
        for (char c : letters.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                String candidate = "" + first + (words.length > 1 ? Character.toUpperCase(c) : Character.toLowerCase(c));
                if (!taken.contains(key(candidate))) {
                    return candidate;
                }
            }
        }
        return label;
    }

    /** Labels count as the same when they differ only in case: Mo and MO are too alike on a map. */
    private static String key(String label) {
        return label.toUpperCase(Locale.ROOT);
    }

    /** "minecraft:village_plains" becomes "Village Plains". */
    static String prettify(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("[_\\-]")) {
            if (!word.isEmpty()) {
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return name.isEmpty() ? id : name.toString();
    }

    /** "Ocean Outposts" becomes "OO", "Towers" becomes "To". */
    static String initials(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length >= 2) {
            return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase();
        }
        String word = words[0];
        return word.length() >= 2 ? Character.toUpperCase(word.charAt(0)) + word.substring(1, 2).toLowerCase()
                : word.toUpperCase();
    }
}
