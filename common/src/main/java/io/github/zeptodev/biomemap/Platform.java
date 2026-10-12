package io.github.zeptodev.biomemap;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;

/** The few things the mod needs from the mod loader. */
interface Platform {
    /** A biome's climate, including changes other mods made to it. */
    record Climate(float temperature, float downfall, boolean hasPrecipitation) {}

    /** The game directory. Maps are saved in its {@code biomemaps} folder. */
    Path gameDir();

    Path configDir();

    /** Display name of an installed mod, e.g. "Repurposed Structures". */
    Optional<String> modName(String modId);

    Optional<String> modVersion(String modId);

    /**
     * The mod whose built-in data a data pack holds, from the id the loader gave the pack, or null when the
     * pack isn't a mod's (vanilla, or a world's datapacks).
     */
    String modOfPack(String packId);

    /** Vanilla keeps a biome's climate private; each loader has its own way to read it. */
    Climate climate(Biome biome);

    /**
     * Shows the singleplayer host a message with links that open files, or returns false if it can't.
     * Vanilla's integrated server refuses to send such links, even to its own host. Runs on the server thread.
     */
    boolean sendToHost(CommandSourceStack source, Component message);
}
