package io.github.zeptodev.biomemap;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge entry point. */
@Mod(BiomeMap.MOD_ID)
public final class BiomeMapNeoForge {
    public BiomeMapNeoForge() {
        BiomeMap.init(new NeoForgePlatform());
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> BiomeMapCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> MapJob.cancelRunning());
    }

    private static final class NeoForgePlatform implements Platform {
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

        /** NeoForge calls a mod's data pack "mod/<id>", or "mod/<id>,<id>" for a jar holding several mods. */
        @Override
        public String modOfPack(String packId) {
            return packId.startsWith("mod/") ? packId.substring(4).split(",")[0] : null;
        }

        /** The climate after NeoForge's biome modifiers, as world generation sees it. */
        @Override
        public Climate climate(Biome biome) {
            Biome.ClimateSettings settings = biome.getModifiedClimateSettings();
            return new Climate(settings.temperature(), settings.downfall(), settings.hasPrecipitation());
        }

        /** NeoForge lets links that open files through on an integrated server. */
        @Override
        public boolean sendToHost(CommandSourceStack source, Component message) {
            source.sendSuccess(() -> message, false);
            return true;
        }
    }
}
