package io.github.zeptodev.biomemap;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

/** Forge entry point. Also runs on NeoForge for 1.20.1, which keeps Forge's API. */
@Mod(BiomeMap.MOD_ID)
public final class BiomeMapForge {
    public BiomeMapForge() {
        BiomeMap.init(new ForgePlatform());
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                BiomeMapCommand.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> MapJob.cancelRunning());
    }

    private static final class ForgePlatform implements Platform {
        @Override
        public Path gameDir() {
            return FMLPaths.GAMEDIR.get();
        }

        @Override
        public Path configDir() {
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public Optional<String> modName(String modId) {
            return ModList.get().getModContainerById(modId).map(mod -> mod.getModInfo().getDisplayName());
        }

        @Override
        public Optional<String> modVersion(String modId) {
            return ModList.get().getModContainerById(modId).map(mod -> mod.getModInfo().getVersion().toString());
        }

        /**
         * Forge names a mod's data pack after the mod's jar file, such as
         * "BiomesOPlenty-forge-1.20.1-19.0.0.96.jar".
         */
        @Override
        public String modOfPack(String packId) {
            for (IModFileInfo file : ModList.get().getModFiles()) {
                if (!file.getMods().isEmpty() && file.getFile().getFileName().equals(packId)) {
                    return file.getMods().get(0).getModId();
                }
            }
            return null;
        }

        /** The climate after Forge's biome modifiers, as world generation sees it. */
        @Override
        public Climate climate(Biome biome) {
            Biome.ClimateSettings settings = biome.getModifiedClimateSettings();
            return new Climate(settings.temperature(), settings.downfall(), settings.hasPrecipitation());
        }

        /** In 1.20.1 the integrated server passes links that open files to its own host. */
        @Override
        public boolean sendToHost(CommandSourceStack source, Component message) {
            source.sendSuccess(() -> message, false);
            return true;
        }
    }
}
