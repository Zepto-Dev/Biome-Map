package io.github.zeptodev.biomemap;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;

/** Fabric entry point. */
public final class BiomeMapFabric implements ModInitializer {
    /** Adds a message to this game's own chat. Set by {@link BiomeMapFabricClient}; null on a dedicated server. */
    static volatile Consumer<Component> localChat;

    @Override
    public void onInitialize() {
        BiomeMap.init(new FabricPlatform());
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> BiomeMapCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MapJob.cancelRunning());
    }

    private static final class FabricPlatform implements Platform {
        @Override
        public Path gameDir() {
            return FabricLoader.getInstance().getGameDir();
        }

        @Override
        public Path configDir() {
            return FabricLoader.getInstance().getConfigDir();
        }

        @Override
        public Optional<String> modName(String modId) {
            return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getName());
        }

        @Override
        public Optional<String> modVersion(String modId) {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString());
        }

        /** Fabric API names a mod's data pack after the mod. */
        @Override
        public String modOfPack(String packId) {
            return FabricLoader.getInstance().isModLoaded(packId) ? packId : null;
        }

        /** Opened up by biomemap.accesswidener. Fabric API's biome modifications change this field itself. */
        @Override
        public Climate climate(Biome biome) {
            Biome.ClimateSettings settings = biome.climateSettings;
            return new Climate(settings.temperature(), settings.downfall(), settings.hasPrecipitation());
        }

        /**
         * The host plays in this same game, so the message goes straight into its chat, as vanilla does with
         * its own "saved screenshot" link, rather than through the integrated server's connection.
         */
        @Override
        public boolean sendToHost(CommandSourceStack source, Component message) {
            Consumer<Component> chat = localChat;
            if (chat == null) {
                return false;
            }
            chat.accept(message);
            return true;
        }
    }
}
