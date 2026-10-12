package io.github.zeptodev.biomemap;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Shared by the NeoForge and Fabric versions, which only differ in their entry point and {@link Platform}.
 * The mod only adds the {@code /biomemap} command: no blocks, items, packets or mixins, so it can be added
 * to or removed from any world at any time.
 */
public final class BiomeMap {
    public static final String MOD_ID = "biomemap";
    static final Logger LOGGER = LogUtils.getLogger();
    private static Platform platform;

    private BiomeMap() {}

    /** Called first by the loader's entry point. */
    static void init(Platform loader) {
        platform = loader;
    }

    static Platform platform() {
        return platform;
    }
}
